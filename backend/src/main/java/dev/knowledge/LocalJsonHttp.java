package dev.knowledge;

import com.fasterxml.jackson.databind.*;
import java.net.*;
import java.net.http.*;
import java.time.Duration;
import java.io.IOException;
import java.util.Set;

/** This free local profile cannot silently send documents or money to a remote model service. */
public final class LocalJsonHttp {
    private final URI origin;
    private final ObjectMapper mapper;
    private final HttpClient client=HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3))
            .followRedirects(HttpClient.Redirect.NEVER).build();
    public LocalJsonHttp(String origin,ObjectMapper mapper) {
        this.origin=URI.create(origin.endsWith("/")?origin.substring(0,origin.length()-1):origin);
        if(!Set.of("http","https").contains(this.origin.getScheme()) || this.origin.getUserInfo()!=null
                || this.origin.getQuery()!=null || this.origin.getFragment()!=null
                || !Set.of("127.0.0.1","localhost","[::1]","::1").contains(this.origin.getHost())
                || (!this.origin.getPath().isEmpty() && !this.origin.getPath().equals("/")))
            throw new IllegalArgumentException("The free local profile requires a loopback service origin");
        this.mapper=mapper;
    }
    public JsonNode request(String method,String path,Object body) { return request(method,path,body,false); }
    public JsonNode request(String method,String path,Object body,boolean allowMissing) {
        try {
            var builder=HttpRequest.newBuilder(URI.create(origin+path)).timeout(Duration.ofSeconds(120))
                    .header("Content-Type","application/json");
            builder.method(method,body==null?HttpRequest.BodyPublishers.noBody():HttpRequest.BodyPublishers.ofString(mapper.writeValueAsString(body)));
            var response=client.send(builder.build(),HttpResponse.BodyHandlers.ofInputStream());
            try(var stream=response.body()) {
                if(allowMissing && response.statusCode()==404) return mapper.nullNode();
                if(response.statusCode()<200 || response.statusCode()>=300)
                    throw new ApiException(502,"LOCAL_SERVICE_FAILURE","A local retrieval/model service rejected the request. No answer was produced.");
                byte[] bytes=stream.readNBytes(4*1024*1024+1);
                if(bytes.length>4*1024*1024) throw new ApiException(502,"LOCAL_SERVICE_OUTPUT","Local service output exceeds the allowed size.");
                return mapper.readTree(bytes);
            }
        } catch(HttpTimeoutException e) { throw new ApiException(504,"MODEL_TIMEOUT","Local inference timed out. Retry a shorter question."); }
        catch(InterruptedException e) { Thread.currentThread().interrupt();throw new ApiException(503,"MODEL_INTERRUPTED","Local inference was interrupted."); }
        catch(IOException e) { throw new ApiException(502,"LOCAL_SERVICE_FAILURE","Cannot read a valid response from a local service."); }
    }
}
