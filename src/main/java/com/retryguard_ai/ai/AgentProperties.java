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
    public AgentProperties {
        stuckOrderMinutes = stuckOrderMinutes == 0 ? 30 : stuckOrderMinutes;
        maxToolCalls = maxToolCalls == 0 ? 4 : maxToolCalls;
        maxRunsPerScan = maxRunsPerScan == 0 ? 10 : maxRunsPerScan;
        timeoutSeconds = timeoutSeconds == 0 ? 15 : timeoutSeconds;
        groq = groq == null ? new Groq("", "", "") : groq;
    }

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