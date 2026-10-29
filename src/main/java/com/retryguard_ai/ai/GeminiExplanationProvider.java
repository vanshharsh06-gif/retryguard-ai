package com.retryguard_ai.ai;

import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

@Component
public class GeminiExplanationProvider implements ExplanationProvider {

    private static final String SYSTEM_INSTRUCTION = """
            Explain the supplied, already-final deterministic payment investigation in concise,
            plain language. The decision and reason are authoritative and must never be changed.
            Use only the supplied states, finding codes, and evidence labels; do not infer missing or unverified
            facts. Do not recommend or describe any payment action, including retry, charge,
            capture, or refund. Explain uncertainty when the evidence is incomplete. Return only
            the explanation text, without a heading or JSON.
            """;

    private final RestClient restClient;
    private final GeminiProperties properties;
    private final ObjectMapper objectMapper;

    public GeminiExplanationProvider(
            RestClient.Builder builder,
            GeminiProperties properties,
            ObjectMapper objectMapper) {
        this.restClient = builder.build();
        this.properties = properties;
        this.objectMapper = objectMapper;
    }

    @Override
    public String explain(ExplanationInput input) {
        if (!properties.isConfigured()) {
            throw new ExplanationProviderUnavailableException();
        }
        String requestBody;
        try {
            requestBody = objectMapper.writeValueAsString(new GeminiRequest(
                    new SystemInstruction(new Part(SYSTEM_INSTRUCTION)),
                    new Content("user", new Part(
                            "Explain this investigation using only this sanitized data: "
                                    + objectMapper.writeValueAsString(input))),
                    new GenerationConfig(0.2, 256)));
        } catch (JacksonException exception) {
            throw new ExplanationProviderFailureException();
        }

        JsonNode response;
        try {
            response = restClient.post()
                    .uri(properties.endpoint() + "/models/" + properties.model()
                            + ":generateContent")
                    .header("x-goog-api-key", properties.apiKey())
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(requestBody)
                    .retrieve()
                    .body(JsonNode.class);
        } catch (RestClientException exception) {
            throw new ExplanationProviderFailureException();
        }

        JsonNode candidates = response == null ? null : response.get("candidates");
        if (candidates == null || !candidates.isArray() || candidates.isEmpty()) {
            throw new InvalidExplanationResponseException();
        }
        JsonNode firstCandidate = candidates.get(0);
        JsonNode content = firstCandidate == null ? null : firstCandidate.get("content");
        JsonNode parts = content == null ? null : content.get("parts");
        if (parts == null || !parts.isArray() || parts.isEmpty()) {
            throw new InvalidExplanationResponseException();
        }
        StringBuilder text = new StringBuilder();
        for (JsonNode part : parts) {
            JsonNode textNode = part == null ? null : part.get("text");
            if (textNode != null && textNode.isString()) {
                if (!text.isEmpty()) {
                    text.append(' ');
                }
                text.append(textNode.asString());
            }
        }
        if (text.toString().isBlank()) {
            throw new InvalidExplanationResponseException();
        }
        return text.toString();
    }

    private record Part(String text) {
    }

    private record SystemInstruction(Part[] parts) {
        SystemInstruction(Part part) {
            this(new Part[]{part});
        }
    }

    private record Content(String role, Part[] parts) {
        Content(String role, Part part) {
            this(role, new Part[]{part});
        }
    }

    private record GenerationConfig(double temperature, int maxOutputTokens) {
    }

    private record GeminiRequest(
            SystemInstruction systemInstruction,
            Content[] contents,
            GenerationConfig generationConfig) {
        GeminiRequest(
                SystemInstruction systemInstruction,
                Content content,
                GenerationConfig generationConfig) {
            this(systemInstruction, new Content[]{content}, generationConfig);
        }
    }
}
