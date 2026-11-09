package dev.knowledge;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.atomic.AtomicReference;
import static org.assertj.core.api.Assertions.*;

class OllamaExcerptProviderTest {
    @Test void localHttpContractWithoutRealModelInference() throws Exception {
        var captured=new AtomicReference<String>();
        var server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
        server.createContext("/api/chat",exchange->{
            captured.set(new String(exchange.getRequestBody().readAllBytes(),StandardCharsets.UTF_8));
            byte[] body="{\"message\":{\"content\":\"{\\\"segments\\\":[{\\\"chunkId\\\":\\\"real-id\\\",\\\"quote\\\":\\\"exact excerpt\\\"}]}\"},\"prompt_eval_count\":12,\"eval_count\":8}".getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200,body.length); exchange.getResponseBody().write(body); exchange.close();
        }); server.start();
        try {
            var provider=new OllamaExcerptProvider("http://127.0.0.1:"+server.getAddress().getPort(),"local-test",new ObjectMapper());
            var result=provider.select("question",List.of());
            assertThat(result.segments().getFirst().chunkId()).isEqualTo("real-id");
            assertThat(result.inputTokens()).isEqualTo(12);
            var payload=new ObjectMapper().readTree(captured.get());
            assertThat(payload.path("stream").asBoolean()).isFalse();
            assertThat(payload.path("model").asText()).isEqualTo("local-test");
            assertThat(payload.path("messages").get(0).path("content").asText()).contains("untrusted data");
        } finally { server.stop(0); }
    }
    @Test void refusesRemoteOriginCloudModelAndMissingOptIn() {
        var mapper=new ObjectMapper();
        assertThatThrownBy(()->new OllamaExcerptProvider("https://example.com","model",mapper)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(()->new OllamaExcerptProvider("http://127.0.0.1:11434","model:cloud",mapper)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(()->new ProviderConfiguration().excerptProvider("ollama",false,"http://127.0.0.1:11434","model",mapper)).isInstanceOf(IllegalArgumentException.class);
    }
    @Test void providerFailureIsExplicit() throws Exception {
        var server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
        server.createContext("/api/chat",exchange->{exchange.sendResponseHeaders(500,-1);exchange.close();});server.start();
        try { assertThatThrownBy(()->new OllamaExcerptProvider("http://127.0.0.1:"+server.getAddress().getPort(),"local-test",new ObjectMapper()).select("q",List.of()))
            .isInstanceOf(ApiException.class).hasMessageContaining("returned an error"); }
        finally {server.stop(0);}
    }
}
