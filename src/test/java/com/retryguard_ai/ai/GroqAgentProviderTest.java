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
        provider = new GroqAgentProvider(this.builder, properties, objectMapper, this.restClient);
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
        RestClient disabledRestClient = disabledBuilder.build();
        GroqAgentProvider disabledProvider = new GroqAgentProvider(
                disabledBuilder, disabledProps, objectMapper, disabledRestClient);

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
        RestClient noKeyRestClient = noKeyBuilder.build();
        GroqAgentProvider noKeyProvider = new GroqAgentProvider(
                noKeyBuilder, noKeyProps, objectMapper, noKeyRestClient);

        GroqAgentProvider.AgentRunResult result = noKeyProvider.runWorkflow("test-order", List.of());

        assertThat(result.status()).isEqualTo("PROVIDER_UNAVAILABLE");
        assertThat(result.summary()).contains("API key not configured");
    }

    @Test
    void parsesGroqToolCallWithNestedFunctionObject() throws Exception {
        // Test that the provider correctly parses Groq's tool_calls format with nested function object
        // Groq returns: {"tool_calls": [{"id": "...", "type": "function", "function": {"name": "...", "arguments": "..."}}]}
        
        ObjectNode functionNode = objectMapper.createObjectNode();
        functionNode.put("name", "get_order_summary");
        functionNode.put("arguments", "{\"orderId\":\"test-order-123\"}");

        ObjectNode toolCallNode = objectMapper.createObjectNode();
        toolCallNode.put("id", "fc_test123");
        toolCallNode.put("type", "function");
        toolCallNode.set("function", functionNode);

        ObjectNode messageNode = objectMapper.createObjectNode();
        messageNode.put("role", "assistant");
        messageNode.set("tool_calls", objectMapper.createArrayNode().add(toolCallNode));

        ObjectNode choiceNode = objectMapper.createObjectNode();
        choiceNode.set("message", messageNode);

        ObjectNode responseNode = objectMapper.createObjectNode();
        responseNode.set("choices", objectMapper.createArrayNode().add(choiceNode));
        responseNode.set("usage", objectMapper.createObjectNode());

        mockServer.expect(requestTo(properties.groq().baseUrl() + "/chat/completions"))
                .andRespond(withSuccess(responseNode.toString(), MediaType.APPLICATION_JSON));

        // Second response with final_recommendation to complete the workflow
        ObjectNode functionNode2 = objectMapper.createObjectNode();
        functionNode2.put("name", "final_recommendation");
        functionNode2.put("arguments", "{\"recommendation\":\"ESCALATE\",\"reason\":\"test\"}");

        ObjectNode toolCallNode2 = objectMapper.createObjectNode();
        toolCallNode2.put("id", "fc_test456");
        toolCallNode2.put("type", "function");
        toolCallNode2.set("function", functionNode2);

        ObjectNode messageNode2 = objectMapper.createObjectNode();
        messageNode2.put("role", "assistant");
        messageNode2.set("tool_calls", objectMapper.createArrayNode().add(toolCallNode2));

        ObjectNode choiceNode2 = objectMapper.createObjectNode();
        choiceNode2.set("message", messageNode2);

        ObjectNode responseNode2 = objectMapper.createObjectNode();
        responseNode2.set("choices", objectMapper.createArrayNode().add(choiceNode2));
        responseNode2.set("usage", objectMapper.createObjectNode());

        mockServer.expect(requestTo(properties.groq().baseUrl() + "/chat/completions"))
                .andRespond(withSuccess(responseNode2.toString(), MediaType.APPLICATION_JSON));

        // Create a simple tool that returns a result
        GroqAgentProvider.ToolDefinition tool = new GroqAgentProvider.ToolDefinition(
                "get_order_summary",
                "Get order summary",
                objectMapper.createObjectNode(),
                args -> Map.of("id", "test-order-123", "status", "PENDING")
        );
        
        // Create final_recommendation tool (required for workflow completion)
        GroqAgentProvider.ToolDefinition finalTool = new GroqAgentProvider.ToolDefinition(
                "final_recommendation",
                "Submit final recommendation",
                objectMapper.createObjectNode(),
                args -> {
                    Map<String, Object> map = (Map<String, Object>) args;
                    return Map.of("recommendation", map.get("recommendation"), "reason", map.get("reason"));
                }
        );

        GroqAgentProvider.AgentRunResult result = provider.runWorkflow("test-order-123", List.of(tool, finalTool));

        assertThat(result.status()).isEqualTo("COMPLETED");
        assertThat(result.toolCallsUsed()).isEqualTo(2);
        assertThat(result.steps()).hasSize(3); // TOOL_CALL + TOOL_CALL (final_recommendation) + RECOMMENDATION
    }

    @Test
    void handlesMissingToolNameInGroqResponse() throws Exception {
        // Test that a tool call without a function name is handled gracefully
        ObjectNode functionNode = objectMapper.createObjectNode();
        functionNode.put("arguments", "{}"); // missing name

        ObjectNode toolCallNode = objectMapper.createObjectNode();
        toolCallNode.put("id", "fc_test123");
        toolCallNode.put("type", "function");
        toolCallNode.set("function", functionNode);

        ObjectNode messageNode = objectMapper.createObjectNode();
        messageNode.put("role", "assistant");
        messageNode.set("tool_calls", objectMapper.createArrayNode().add(toolCallNode));

        ObjectNode choiceNode = objectMapper.createObjectNode();
        choiceNode.set("message", messageNode);

        ObjectNode responseNode = objectMapper.createObjectNode();
        responseNode.set("choices", objectMapper.createArrayNode().add(choiceNode));
        responseNode.set("usage", objectMapper.createObjectNode());

        mockServer.expect(requestTo(properties.groq().baseUrl() + "/chat/completions"))
                .andRespond(withSuccess(responseNode.toString(), MediaType.APPLICATION_JSON));

        GroqAgentProvider.ToolDefinition tool = new GroqAgentProvider.ToolDefinition(
                "get_order_summary",
                "Get order summary",
                objectMapper.createObjectNode(),
                args -> Map.of("id", "test-order-123")
        );

        GroqAgentProvider.AgentRunResult result = provider.runWorkflow("test-order-123", List.of(tool));

        // Should fail with INVALID_TOOL
        assertThat(result.status()).isEqualTo("INVALID_TOOL");
        assertThat(result.summary()).contains("missing function name");
    }

    @Test
    void handlesNullToolNameInGroqResponse() throws Exception {
        // Test that a tool call with null function name is handled gracefully
        // (Groq could return null for the function name in some edge cases)
        ObjectNode functionNode = objectMapper.createObjectNode();
        functionNode.putNull("name");
        functionNode.put("arguments", "{}");

        ObjectNode toolCallNode = objectMapper.createObjectNode();
        toolCallNode.put("id", "fc_test123");
        toolCallNode.put("type", "function");
        toolCallNode.set("function", functionNode);

        ObjectNode messageNode = objectMapper.createObjectNode();
        messageNode.put("role", "assistant");
        messageNode.set("tool_calls", objectMapper.createArrayNode().add(toolCallNode));

        ObjectNode choiceNode = objectMapper.createObjectNode();
        choiceNode.set("message", messageNode);

        ObjectNode responseNode = objectMapper.createObjectNode();
        responseNode.set("choices", objectMapper.createArrayNode().add(choiceNode));
        responseNode.set("usage", objectMapper.createObjectNode());

        mockServer.expect(requestTo(properties.groq().baseUrl() + "/chat/completions"))
                .andRespond(withSuccess(responseNode.toString(), MediaType.APPLICATION_JSON));

        GroqAgentProvider.ToolDefinition tool = new GroqAgentProvider.ToolDefinition(
                "get_order_summary",
                "Get order summary",
                objectMapper.createObjectNode(),
                args -> Map.of("id", "test-order-123")
        );

        GroqAgentProvider.AgentRunResult result = provider.runWorkflow("test-order-123", List.of(tool));

        // Should fail with INVALID_TOOL
        assertThat(result.status()).isEqualTo("INVALID_TOOL");
        assertThat(result.summary()).contains("missing function name");
    }
}