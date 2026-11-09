package dev.knowledge;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.context.annotation.*;
import org.springframework.beans.factory.annotation.Value;

@Configuration
public class ProviderConfiguration {
    @Bean ExcerptProvider excerptProvider(@Value("${knowledge.provider}") String provider,
            @Value("${knowledge.allow-local-model}") boolean allowed,@Value("${knowledge.ollama-url}") String url,
            @Value("${knowledge.ollama-model}") String model,ObjectMapper mapper) {
        if(provider.equals("offline")) return new OfflineExcerptProvider();
        if(provider.equals("ollama") && allowed) return new OllamaExcerptProvider(url,model,mapper);
        throw new IllegalArgumentException("Use offline, or explicitly opt in to a local Ollama model.");
    }
}
