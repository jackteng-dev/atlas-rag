package dev.knowledge.platform.core;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.*;
import java.util.*;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

/** JSON helper for jsonb columns. Lenient on read: stored documents may gain fields over time. */
@Component @Profile("platform")
public class Json {
    private final ObjectMapper mapper;
    public Json(ObjectMapper base) { mapper = base.copy().configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false); }
    public String write(Object value) {
        try { return mapper.writeValueAsString(value); } catch (Exception e) { throw new IllegalStateException("JSON serialisation failed", e); }
    }
    public JsonNode tree(String value) {
        try { return value == null ? mapper.nullNode() : mapper.readTree(value); } catch (Exception e) { throw new IllegalStateException("Invalid stored JSON", e); }
    }
    public Map<String, Object> map(String value) {
        try { return value == null ? Map.of() : mapper.readValue(value, new TypeReference<LinkedHashMap<String, Object>>() {}); }
        catch (Exception e) { throw new IllegalStateException("Invalid stored JSON", e); }
    }
    public Object any(String value) {
        try { return value == null ? null : mapper.readValue(value, Object.class); } catch (Exception e) { throw new IllegalStateException("Invalid stored JSON", e); }
    }
    public <T> T convert(Object value, Class<T> type) { return mapper.convertValue(value, type); }
    public <T> T read(String value, Class<T> type) {
        try { return mapper.readValue(value, type); } catch (Exception e) { throw new IllegalStateException("Invalid stored JSON", e); }
    }
    public ObjectMapper mapper() { return mapper; }
}
