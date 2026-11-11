package dev.knowledge;

import com.fasterxml.jackson.databind.*;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import static dev.knowledge.Models.*;
import static org.assertj.core.api.Assertions.*;

/** Deterministic HTTP stubs for contract/error paths. Actual model runs are recorded separately. */
class LocalModelContractsTest {
    final ObjectMapper mapper=new ObjectMapper();
    @Test void embeddingsRestoreRequestedOrderAndRejectInvalidVectors() throws Exception {
        var response=new String[]{"{\"data\":[{\"index\":1,\"embedding\":[0,1]},{\"index\":0,\"embedding\":[1,0]}]}"};
        var server=stub(response);try {
            var client=models(server);
            assertThat(client.embed(List.of("one","two"))).containsExactly(List.of(1.0,0.0),List.of(0.0,1.0));
            response[0]="{\"data\":[{\"index\":0,\"embedding\":[0,0]}]}";
            assertThatThrownBy(()->client.embed(List.of("one"))).isInstanceOf(ApiException.class).hasMessageContaining("norm");
            response[0]="{\"data\":[{\"index\":0,\"embedding\":[1,0]},{\"index\":0,\"embedding\":[1,0]}]}";
            assertThatThrownBy(()->client.embed(List.of("one","two"))).isInstanceOf(ApiException.class).hasMessageContaining("index");
        } finally { server.stop(0); }
    }
    @Test void chatValidatesStructuredCitationBoundsRefusalAndTokenUsage() throws Exception {
        String[] response={chat("{\"answerable\":true,\"statements\":[{\"text\":\"Uses Java\",\"citations\":[1]}]}")};
        var server=stub(response);try {
            var client=models(server);var source=new Source("canonical","guide","Guide",1,"Stack","Java",2,2,0,"/api/documents/guide/chunks/canonical?version=1");
            var answer=client.generate("stack",List.of(source));
            assertThat(answer.statements().getFirst().chunkIds()).containsExactly("canonical");assertThat(answer.inputTokens()).isEqualTo(42);
            response[0]=chat("{\"answerable\":true,\"statements\":[{\"text\":\"Forged\",\"citations\":[2]}]}");
            assertThatThrownBy(()->client.generate("stack",List.of(source))).isInstanceOf(ApiException.class).hasMessageContaining("outside the authorized");
            response[0]=chat("{\"answerable\":false,\"statements\":[]}");
            assertThat(client.generate("salary",List.of(source)).statements()).isEmpty();
            response[0]=chat("{\"answerable\":false,\"statements\":[{\"text\":\"bad\",\"citations\":[1]}]}");
            assertThatThrownBy(()->client.generate("salary",List.of(source))).isInstanceOf(ApiException.class).hasMessageContaining("inconsistent");
        } finally { server.stop(0); }
        assertThatThrownBy(()->new LocalJsonHttp("https://api.openai.com",mapper)).isInstanceOf(IllegalArgumentException.class).hasMessageContaining("loopback");
    }
    @Test void qualityGenerationUsesTheOptionalModelEndpointAndAlias() throws Exception {
        String[] fast={chat("{\"answerable\":true,\"statements\":[{\"text\":\"fast\",\"citations\":[1]}]}")};
        String[] quality={chat("{\"answerable\":true,\"statements\":[{\"text\":\"quality\",\"citations\":[1]}]}")};
        var bodies=new ArrayList<String>();
        var fastServer=stub(fast);var qualityServer=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
        qualityServer.createContext("/",exchange->{bodies.add(new String(exchange.getRequestBody().readAllBytes(),StandardCharsets.UTF_8));byte[] bytes=quality[0].getBytes(StandardCharsets.UTF_8);exchange.sendResponseHeaders(200,bytes.length);exchange.getResponseBody().write(bytes);exchange.close();});
        qualityServer.start();
        try {
            var plain=models(fastServer);
            assertThat(plain.hasQuality()).isFalse();
            var source=new Source("canonical","guide","Guide",1,"Stack","Java",2,2,0,"/api/documents/guide/chunks/canonical?version=1");
            assertThat(plain.generate("stack",List.of(source),true).statements().getFirst().text()).isEqualTo("fast");
            var client=models(fastServer).withQuality(origin(qualityServer),"qwen3-8b");
            assertThat(client.hasQuality()).isTrue();
            assertThat(client.generate("stack",List.of(source)).statements().getFirst().text()).isEqualTo("fast");
            assertThat(client.generate("stack",List.of(source),true).statements().getFirst().text()).isEqualTo("quality");
            assertThat(mapper.readTree(bodies.getFirst()).path("model").asText()).isEqualTo("qwen3-8b");
        } finally { fastServer.stop(0);qualityServer.stop(0); }
        assertThatThrownBy(()->new LlamaRagModels("http://127.0.0.1:1","http://127.0.0.1:1","http://127.0.0.1:1",mapper).withQuality("https://api.openai.com","x"))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("loopback");
        assertThatThrownBy(()->new LocalJsonHttp("http://127.0.0.1:1",mapper,java.time.Duration.ofSeconds(901))).isInstanceOf(IllegalArgumentException.class);
    }
    @Test void qdrantQuerySendsCurrentAuthorizedVersionFilterAndCleanupFollowsUpsert() throws Exception {
        var requests=new ArrayList<JsonNode>();var paths=new ArrayList<String>();
        var server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
        server.createContext("/",exchange->{
            paths.add(exchange.getRequestMethod()+" "+exchange.getRequestURI());
            byte[] request=exchange.getRequestBody().readAllBytes();if(request.length>0)requests.add(mapper.readTree(request));
            String result;
            if(exchange.getRequestURI().getPath().endsWith("/points/query")) result="{\"result\":{\"points\":[{\"payload\":{\"documentId\":\"allowed\",\"version\":2,\"chunkId\":\"chunk\"},\"score\":0.8}]}}";
            else if(exchange.getRequestMethod().equals("GET")) result="{\"result\":{\"config\":{\"params\":{\"vectors\":{\"size\":2}}}}}";
            else result="{\"result\":{\"status\":\"completed\"}}";
            byte[] bytes=result.getBytes(StandardCharsets.UTF_8);exchange.sendResponseHeaders(200,bytes.length);exchange.getResponseBody().write(bytes);exchange.close();
        });server.start();try {
            var vector=new QdrantVectorIndex(origin(server),"test_owned",mapper);
            var chunk=new Chunk("chunk","Stack","Java",2,2);var doc=new Document("allowed","Allowed",2,"hash","now","Java",List.of(chunk));
            vector.replace(List.of(new VectorIndex.Point(doc,chunk,List.of(1.0,0.0))));
            var found=vector.query(List.of(1.0,0.0),List.of(doc),10);
            assertThat(found.getFirst().version()).isEqualTo(2);
            assertThat(paths.get(1)).startsWith("PUT ");assertThat(paths.get(2)).contains("/points/delete");
            var query=requests.getLast();var filters=query.path("filter").path("should").path(0).path("must");
            assertThat(filters.path(0).path("match").path("value").asText()).isEqualTo("allowed");
            assertThat(filters.path(1).path("match").path("value").asInt()).isEqualTo(2);
        } finally { server.stop(0); }
    }
    private HttpServer stub(String[] response) throws Exception {
        var server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
        server.createContext("/",exchange->{exchange.getRequestBody().readAllBytes();byte[] bytes=response[0].getBytes(StandardCharsets.UTF_8);exchange.sendResponseHeaders(200,bytes.length);exchange.getResponseBody().write(bytes);exchange.close();});server.start();return server;
    }
    private LlamaRagModels models(HttpServer server) { return new LlamaRagModels(origin(server),origin(server),origin(server),mapper); }
    private String origin(HttpServer server) { return "http://127.0.0.1:"+server.getAddress().getPort(); }
    private String chat(String content) throws Exception { return mapper.writeValueAsString(Map.of("choices",List.of(Map.of("finish_reason","stop","message",Map.of("content",content))),"usage",Map.of("prompt_tokens",42,"completion_tokens",9))); }
}
