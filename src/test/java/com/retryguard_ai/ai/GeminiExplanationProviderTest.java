package com.retryguard_ai.ai;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.ObjectMapper;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.content;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

class GeminiExplanationProviderTest {

    private static final String ENDPOINT = "https://gemini.example/v1beta";

    @Test
    void sendsOnlySanitizedInvestigationSummaryAndReturnsGeminiText() {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        GeminiExplanationProvider provider = new GeminiExplanationProvider(
                builder, new GeminiProperties("test-key", "gemini-test", ENDPOINT),
                new ObjectMapper());
        server.expect(requestTo(ENDPOINT + "/models/gemini-test:generateContent"))
                .andExpect(method(HttpMethod.POST))
                .andExpect(header("x-goog-api-key", "test-key"))
                .andExpect(content().string(org.hamcrest.Matchers.allOf(
                        org.hamcrest.Matchers.containsString("\"contents\":["),
                        org.hamcrest.Matchers.containsString("\\\"decision\\\":\\\"WAIT\\\""),
                        org.hamcrest.Matchers.containsString("\\\"reason\\\":\\\"INSUFFICIENT_EVIDENCE\\\""),
                        org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString("internal-reference")),
                        org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString("event-uuid")))))
                .andRespond(withSuccess(
                        "{\"candidates\":[{\"content\":{\"parts\":[{\"text\":"
                                + "\"The result is WAIT because verified evidence is incomplete.\"}]}}]}",
                        MediaType.APPLICATION_JSON));

        String explanation = provider.explain(input());

        assertEquals("The result is WAIT because verified evidence is incomplete.", explanation);
        server.verify();
    }

    @Test
    void missingApiKeyFailsWithoutAnHttpRequest() {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        GeminiExplanationProvider provider = new GeminiExplanationProvider(
                builder, new GeminiProperties("", "gemini-test", ENDPOINT), new ObjectMapper());

        assertThrows(ExplanationProviderUnavailableException.class,
                () -> provider.explain(input()));
        server.verify();
    }

    @Test
    void malformedAndEmptyResponsesAreRejected() {
        assertInvalidResponse("{\"unexpected\":true}");
        assertInvalidResponse("{\"candidates\":[{\"content\":{\"parts\":[{\"text\":\"  \"}]}}]}");
        assertInvalidResponse("{\"candidates\":[]}");
    }

    @Test
    void providerHttpFailureIsTypedAndSanitized() {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        GeminiExplanationProvider provider = new GeminiExplanationProvider(
                builder, new GeminiProperties("test-key", "gemini-test", ENDPOINT),
                new ObjectMapper());
        server.expect(requestTo(ENDPOINT + "/models/gemini-test:generateContent"))
                .andRespond(withStatus(HttpStatus.SERVICE_UNAVAILABLE)
                        .contentType(MediaType.APPLICATION_JSON)
                        .body("{\"error\":\"test-key must not be exposed\"}"));

        ExplanationProviderFailureException exception = assertThrows(
                ExplanationProviderFailureException.class, () -> provider.explain(input()));

        org.junit.jupiter.api.Assertions.assertFalse(exception.getMessage().contains("test-key"));
        server.verify();
    }

    private void assertInvalidResponse(String responseBody) {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        GeminiExplanationProvider provider = new GeminiExplanationProvider(
                builder, new GeminiProperties("test-key", "gemini-test", ENDPOINT),
                new ObjectMapper());
        server.expect(requestTo(ENDPOINT + "/models/gemini-test:generateContent"))
                .andRespond(withSuccess(responseBody, MediaType.APPLICATION_JSON));

        assertThrows(InvalidExplanationResponseException.class,
                () -> provider.explain(input()));
        server.verify();
    }

    private ExplanationInput input() {
        return new ExplanationInput(
                "WAIT",
                "INSUFFICIENT_EVIDENCE",
                "1.0",
                "FAILED",
                15,
                List.of(new ExplanationInput.Attempt(
                        "PAYPAL_SANDBOX",
                        "FAILED",
                        List.of(new ExplanationInput.Event(
                                "PAYMENT.CAPTURE.DENIED",
                                "PAYPAL_SANDBOX",
                                "PROVIDER",
                                "VERIFIED",
                                "PROVIDER_CREATE_TIME",
                                "FRESH",
                                true)))));
    }
}
