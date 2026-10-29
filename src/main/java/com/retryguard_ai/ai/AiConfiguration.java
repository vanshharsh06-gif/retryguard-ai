package com.retryguard_ai.ai;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.ObjectMapper;

@Configuration
@EnableConfigurationProperties(AgentProperties.class)
public class AiConfiguration {

    @Bean
    public GroqAgentProvider groqAgentProvider(RestClient.Builder builder, AgentProperties properties, ObjectMapper objectMapper) {
        return new GroqAgentProvider(builder, properties, objectMapper);
    }
}