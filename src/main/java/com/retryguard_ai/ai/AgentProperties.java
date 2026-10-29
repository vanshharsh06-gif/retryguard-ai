package com.retryguard_ai.ai;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "retryguard.ai.agent")
public record AgentProperties(
        boolean enabled,
        boolean schedulerEnabled,
        int stuckOrderMinutes,
        int maxToolCalls,
        int maxRunsPerScan,
        int timeoutSeconds,
        Groq groq
) {
    public record Groq(
            String apiKey,
            String model,
            String baseUrl
    ) {
        public boolean isConfigured() {
            return apiKey != null && !apiKey.isBlank();
        }
    }
}