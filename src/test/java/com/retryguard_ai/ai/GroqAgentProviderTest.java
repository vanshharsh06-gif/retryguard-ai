package com.retryguard_ai.ai;

import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.*;
import static org.springframework.test.web.client.response.MockRestResponseCreators.*;

class GroqAgentProviderTest {

    private GroqAgentProvider provider;
    private MockRestServiceServer mockServer;
    private ObjectMapper objectMapper;
    private AgentProperties properties;
    private RestClient restClient;
    private RestClient.Builder builder;

    @BeforeEach
    void setUp() {
        objectMapper = new ObjectMapper();
        
        properties = new AgentProperties(
                true, false, 30, 4, 10, 15,
                new AgentProperties.Groq("test-key", "llama-3.1-70b-versatile", "https://api.groq.com/openai/v1")
        );

        builder = RestClient.builder()
                .baseUrl(properties.groq().baseUrl())
                .defaultHeader(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE)
                .defaultHeader(HttpHeaders.AUTHORIZATION, "Bearer " + properties.groq().apiKey());

        mockServer = MockRestServiceServer.bindTo(builder).build();
        this.restClient = builder.build();
        provider = new GroqAgentProvider(this.builder, properties, objectMapper);
    }

    @Test
    void returnsNotConfiguredWhenAgentDisabled() {
        AgentProperties disabledProps = new AgentProperties(
                false, false, 30, 4, 10, 15,
                new AgentProperties.Groq("test-key", "llama-3.1-70b-versatile", "https://api.groq.com/openai/v1")
        );

        // Reuse the same builder pattern for disabled provider
        RestClient.Builder disabledBuilder = RestClient.builder()
                .baseUrl("https://api.groq.com/openai/v1")
                .defaultHeader(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE);
        GroqAgentProvider disabledProvider = new GroqAgentProvider(
                disabledBuilder, disabledProps, objectMapper);

        GroqAgentProvider.AgentRunResult result = disabledProvider.runWorkflow("test-order", List.of());

        assertThat(result.status()).isEqualTo("NOT_CONFIGURED");
        assertThat(result.summary()).contains("disabled");
    }

    @Test
    void returnsProviderUnavailableWhenApiKeyMissing() {
        AgentProperties noKeyProps = new AgentProperties(
                true, false, 30, 4, 10, 15,
                new AgentProperties.Groq("", "llama-3.1-70b-versatile", "https://api.groq.com/openai/v1")
        );

        // Reuse the same builder pattern for no-key provider
        RestClient.Builder noKeyBuilder = RestClient.builder()
                .baseUrl("https://api.groq.com/openai/v1")
                .defaultHeader(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE);
        GroqAgentProvider noKeyProvider = new GroqAgentProvider(
                noKeyBuilder, noKeyProps, objectMapper);

        GroqAgentProvider.AgentRunResult result = noKeyProvider.runWorkflow("test-order", List.of());

        assertThat(result.status()).isEqualTo("PROVIDER_UNAVAILABLE");
        assertThat(result.summary()).contains("API key not configured");
    }
}