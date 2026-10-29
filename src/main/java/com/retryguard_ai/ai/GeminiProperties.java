package com.retryguard_ai.ai;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "retryguard.ai.gemini")
public record GeminiProperties(String apiKey, String model, String endpoint) {

    public boolean isConfigured() {
        return apiKey != null && !apiKey.isBlank();
    }
}
