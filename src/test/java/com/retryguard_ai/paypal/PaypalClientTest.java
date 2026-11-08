package com.retryguard_ai.paypal;

import com.retryguard_ai.entity.MerchantOrder;
import com.retryguard_ai.entity.PaymentAttempt;
import com.retryguard_ai.exception.PaypalProviderException;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.content;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

class PaypalClientTest {

    private static final String SANDBOX_API = "https://api-m.sandbox.paypal.com";

    @Test
    void createsSandboxOrderUsingPersistedAmountAndStableRequestId() {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        PaypalClient client = new PaypalClient(
                builder.build(), new PaypalProperties(
                        "sandbox-client", "sandbox-secret", null, SANDBOX_API,
                        "https://retryguard.ngrok.app"));
        PaymentAttempt attempt = attempt();
        String basicCredentials = Base64.getEncoder().encodeToString(
                "sandbox-client:sandbox-secret".getBytes(StandardCharsets.UTF_8));

        server.expect(requestTo(SANDBOX_API + "/v1/oauth2/token"))
                .andExpect(method(HttpMethod.POST))
                .andExpect(header("Authorization", "Basic " + basicCredentials))
                .andExpect(content().string("grant_type=client_credentials"))
                .andRespond(withSuccess(
                        "{\"access_token\":\"sandbox-token\",\"token_type\":\"Bearer\",\"expires_in\":300}",
                        MediaType.APPLICATION_JSON));
        server.expect(requestTo(SANDBOX_API + "/v2/checkout/orders"))
                .andExpect(method(HttpMethod.POST))
                .andExpect(header("Authorization", "Bearer sandbox-token"))
                .andExpect(header("PayPal-Request-Id",
                        requestId(attempt.getId(), "create")))
                .andExpect(content().json(
                        "{\"intent\":\"CAPTURE\",\"purchase_units\":[{"
                                + "\"reference_id\":\"merchant-ref\",\"amount\":{"
                                + "\"currency_code\":\"USD\",\"value\":\"42.75\"},"
                                + "\"payments\":null}],"
                                + "\"payment_source\":{\"paypal\":{\"experience_context\":{"
                                + "\"return_url\":\"https://retryguard.ngrok.app/api/payment-attempts/"
                                + attempt.getId() + "/paypal/return\","
                                + "\"cancel_url\":\"https://retryguard.ngrok.app/api/payment-attempts/"
                                + attempt.getId() + "/paypal/cancel\"}}}}",
                        true))
                .andRespond(withSuccess(
                        "{\"id\":\"ORDER-123\",\"status\":\"CREATED\",\"links\":["
                                + "{\"rel\":\"approve\",\"href\":\"https://www.sandbox.paypal.com/checkout\"}]}",
                        MediaType.APPLICATION_JSON));

        PaypalOrderResult result = client.createOrder(attempt);

        assertEquals("ORDER-123", result.orderId());
        assertEquals("CREATED", result.status());
        assertEquals("https://www.sandbox.paypal.com/checkout", result.approvalUrl());
        server.verify();
    }

    @Test
    void diagnosesExistingOrderWithReadOnlyGetAndReturnsOnlyRequestedFields() {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        PaypalClient client = new PaypalClient(
                builder.build(), new PaypalProperties("sandbox-client", "sandbox-secret"));

        server.expect(requestTo(SANDBOX_API + "/v1/oauth2/token"))
                .andRespond(withSuccess("{\"access_token\":\"sandbox-token\"}",
                        MediaType.APPLICATION_JSON));
        server.expect(requestTo(SANDBOX_API + "/v2/checkout/orders/17Y00533C42982630"))
                .andExpect(method(HttpMethod.GET))
                .andRespond(withSuccess(
                        "{\"id\":\"17Y00533C42982630\",\"status\":\"CREATED\","
                                + "\"intent\":\"CAPTURE\",\"purchase_units\":[{\"amount\":"
                                + "{\"currency_code\":\"USD\",\"value\":\"10.00\"}}],"
                                + "\"links\":[{\"rel\":\"payer-action\","
                                + "\"href\":\"https://www.sandbox.paypal.com/checkoutnow?token=private-token\"}]}",
                        MediaType.APPLICATION_JSON));

        PaypalClient.PaypalOrderDiagnostic result =
                client.diagnoseOrder("17Y00533C42982630");

        assertEquals(200, result.httpStatus());
        assertEquals("CREATED", result.status());
        assertEquals("CAPTURE", result.intent());
        assertEquals("USD", result.purchaseUnits().get(0).currencyCode());
        assertEquals("10.00", result.purchaseUnits().get(0).value());
        assertEquals("payer-action", result.approvalLink().rel());
        assertEquals("https", result.approvalLink().scheme());
        assertEquals("www.sandbox.paypal.com", result.approvalLink().host());
        assertEquals("/checkoutnow", result.approvalLink().path());
        org.junit.jupiter.api.Assertions.assertTrue(
                result.approvalLink().expectedSandboxCheckoutHost());
        String diagnosticOutput = PaypalOrderDiagnosticTest.formatDiagnostic(result);
        org.junit.jupiter.api.Assertions.assertTrue(diagnosticOutput.contains("rel=payer-action"));
        org.junit.jupiter.api.Assertions.assertTrue(
                diagnosticOutput.contains("path=/checkoutnow"));
        org.junit.jupiter.api.Assertions.assertTrue(
                diagnosticOutput.contains("Expected PayPal Sandbox checkout host=YES"));
        org.junit.jupiter.api.Assertions.assertFalse(diagnosticOutput.contains("private-token"));
        org.junit.jupiter.api.Assertions.assertFalse(diagnosticOutput.contains("?token="));
        org.junit.jupiter.api.Assertions.assertFalse(
                diagnosticOutput.contains("https://www.sandbox.paypal.com"));
        server.verify();
    }

    @Test
    void reportsUnexpectedApprovalHostWithoutPrintingQueryParameters() {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        PaypalClient client = new PaypalClient(
                builder.build(), new PaypalProperties("sandbox-client", "sandbox-secret"));

        server.expect(requestTo(SANDBOX_API + "/v1/oauth2/token"))
                .andRespond(withSuccess("{\"access_token\":\"sandbox-token\"}",
                        MediaType.APPLICATION_JSON));
        server.expect(requestTo(SANDBOX_API + "/v2/checkout/orders/17Y00533C42982630"))
                .andExpect(method(HttpMethod.GET))
                .andRespond(withSuccess(
                        "{\"id\":\"17Y00533C42982630\",\"status\":\"CREATED\","
                                + "\"intent\":\"CAPTURE\",\"links\":[{\"rel\":\"approve\","
                                + "\"href\":\"https://checkout.example/approve?token=private-token\"}]}",
                        MediaType.APPLICATION_JSON));

        PaypalClient.PaypalOrderDiagnostic result =
                client.diagnoseOrder("17Y00533C42982630");

        assertEquals("approve", result.approvalLink().rel());
        assertEquals("checkout.example", result.approvalLink().host());
        org.junit.jupiter.api.Assertions.assertFalse(
                result.approvalLink().expectedSandboxCheckoutHost());
        String diagnosticOutput = PaypalOrderDiagnosticTest.formatDiagnostic(result);
        org.junit.jupiter.api.Assertions.assertTrue(
                diagnosticOutput.contains("WARNING: approval URL uses an unexpected host."));
        org.junit.jupiter.api.Assertions.assertFalse(diagnosticOutput.contains("private-token"));
        server.verify();
    }

    @Test
    void reportsMissingApprovalLinkClearly() {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        PaypalClient client = new PaypalClient(
                builder.build(), new PaypalProperties("sandbox-client", "sandbox-secret"));

        server.expect(requestTo(SANDBOX_API + "/v1/oauth2/token"))
                .andRespond(withSuccess("{\"access_token\":\"sandbox-token\"}",
                        MediaType.APPLICATION_JSON));
        server.expect(requestTo(SANDBOX_API + "/v2/checkout/orders/17Y00533C42982630"))
                .andExpect(method(HttpMethod.GET))
                .andRespond(withSuccess(
                        "{\"id\":\"17Y00533C42982630\",\"status\":\"CREATED\","
                                + "\"intent\":\"CAPTURE\",\"links\":[]}",
                        MediaType.APPLICATION_JSON));

        PaypalClient.PaypalOrderDiagnostic result =
                client.diagnoseOrder("17Y00533C42982630");

        assertNull(result.approvalLink());
        org.junit.jupiter.api.Assertions.assertTrue(
                PaypalOrderDiagnosticTest.formatDiagnostic(result)
                        .contains("Approval link: MISSING"));
        server.verify();
    }

    @Test
    void diagnosesPayPalOrderErrorsWithoutRetainingOtherResponseData() {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        PaypalClient client = new PaypalClient(
                builder.build(), new PaypalProperties("sandbox-client", "sandbox-secret"));

        server.expect(requestTo(SANDBOX_API + "/v1/oauth2/token"))
                .andRespond(withSuccess("{\"access_token\":\"sandbox-token\"}",
                        MediaType.APPLICATION_JSON));
        server.expect(requestTo(SANDBOX_API + "/v2/checkout/orders/17Y00533C42982630"))
                .andExpect(method(HttpMethod.GET))
                .andRespond(withStatus(HttpStatus.NOT_FOUND)
                        .contentType(MediaType.APPLICATION_JSON)
                        .body("{\"name\":\"RESOURCE_NOT_FOUND\","
                                + "\"message\":\"The specified resource does not exist.\","
                                + "\"debug_id\":\"safe-debug-id\","
                                + "\"secret\":\"must-not-be-retained\"}"));

        PaypalClient.PaypalOrderDiagnostic result =
                client.diagnoseOrder("17Y00533C42982630");

        assertEquals(404, result.httpStatus());
        assertEquals("RESOURCE_NOT_FOUND", result.errorName());
        assertEquals("The specified resource does not exist.", result.errorMessage());
        assertEquals("safe-debug-id", result.debugId());
        assertNull(result.status());
        server.verify();
    }

    @Test
    void capturesExistingSandboxOrderAndMapsCaptureResponse() {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        PaypalClient client = new PaypalClient(
                builder.build(), new PaypalProperties("sandbox-client", "sandbox-secret"));
        UUID attemptId = UUID.randomUUID();
        String basicCredentials = Base64.getEncoder().encodeToString(
                "sandbox-client:sandbox-secret".getBytes(StandardCharsets.UTF_8));

        server.expect(requestTo(SANDBOX_API + "/v1/oauth2/token"))
                .andExpect(method(HttpMethod.POST))
                .andExpect(header("Authorization", "Basic " + basicCredentials))
                .andRespond(withSuccess("{\"access_token\":\"sandbox-token\"}",
                        MediaType.APPLICATION_JSON));
        server.expect(requestTo(SANDBOX_API + "/v2/checkout/orders/ORDER-123/capture"))
                .andExpect(method(HttpMethod.POST))
                .andExpect(header("Authorization", "Bearer sandbox-token"))
                .andExpect(header("PayPal-Request-Id", requestId(attemptId, "capture")))
                .andExpect(content().string("{}"))
                .andRespond(withSuccess(
                        "{\"id\":\"ORDER-123\",\"status\":\"COMPLETED\","
                                + "\"purchase_units\":[{\"payments\":{"
                                + "\"captures\":[{\"id\":\"CAPTURE-456\","
                                + "\"status\":\"COMPLETED\",\"amount\":{"
                                + "\"currency_code\":\"USD\",\"value\":\"42.75\"}}]}}]}",
                        MediaType.APPLICATION_JSON));

        PaypalCaptureResult result = client.captureOrder(attemptId, "ORDER-123");

        assertEquals("ORDER-123", result.orderId());
        assertEquals("CAPTURE-456", result.captureId());
        assertEquals("COMPLETED", result.status());
        assertEquals("42.75", result.amount());
        assertEquals("USD", result.currency());
        server.verify();
    }

    @Test
    void providerErrorsAreSanitizedAndMissingCredentialsAvoidHttpRequests() {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        PaypalClient client = new PaypalClient(
                builder.build(), new PaypalProperties(
                        "sandbox-client", "sandbox-secret", null, SANDBOX_API,
                        "https://retryguard.ngrok.app"));
        server.expect(requestTo(SANDBOX_API + "/v1/oauth2/token"))
                .andRespond(withStatus(HttpStatus.UNAUTHORIZED)
                        .contentType(MediaType.APPLICATION_JSON)
                        .body("{\"error\":\"invalid_client\",\"error_description\":\"sandbox-secret\"}"));

        PaypalProviderException exception = assertThrows(
                PaypalProviderException.class, () -> client.createOrder(attempt()));
        assertEquals(HttpStatus.BAD_GATEWAY, exception.getStatus());
        org.junit.jupiter.api.Assertions.assertFalse(
                exception.getMessage().contains("sandbox-secret"));
        server.verify();

        RestClient.Builder unconfiguredBuilder = RestClient.builder();
        MockRestServiceServer unconfiguredServer =
                MockRestServiceServer.bindTo(unconfiguredBuilder).build();
        PaypalClient unconfigured = new PaypalClient(
                unconfiguredBuilder.build(), new PaypalProperties("", ""));
        PaypalProviderException notConfigured = assertThrows(
                PaypalProviderException.class, () -> unconfigured.createOrder(attempt()));
        assertEquals(HttpStatus.SERVICE_UNAVAILABLE, notConfigured.getStatus());
        assertNull(notConfigured.getCause());
        unconfiguredServer.verify();
    }

    @Test
    void verifiesWebhookUsingPaypalsSignatureVerificationApiAndConfiguredWebhookId() {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        PaypalClient client = new PaypalClient(
                builder.build(), new PaypalProperties(
                        "sandbox-client", "sandbox-secret", "webhook-configured-id"));
        tools.jackson.databind.JsonNode event =
                tools.jackson.databind.node.JsonNodeFactory.instance.objectNode();
        PaypalWebhookTransmission transmission = new PaypalWebhookTransmission(
                "SHA256withRSA", "https://cert.example/cert", "transmission-123",
                "signature-abc", "2026-10-17T10:00:00Z");

        server.expect(requestTo(SANDBOX_API + "/v1/oauth2/token"))
                .andRespond(withSuccess("{\"access_token\":\"sandbox-token\"}",
                        MediaType.APPLICATION_JSON));
        server.expect(requestTo(
                        SANDBOX_API + "/v1/notifications/verify-webhook-signature"))
                .andExpect(method(HttpMethod.POST))
                .andExpect(content().string(org.hamcrest.Matchers.allOf(
                        org.hamcrest.Matchers.containsString("\"auth_algo\":\"SHA256withRSA\""),
                        org.hamcrest.Matchers.containsString("\"cert_url\":\"https://cert.example/cert\""),
                        org.hamcrest.Matchers.containsString("\"transmission_id\":\"transmission-123\""),
                        org.hamcrest.Matchers.containsString("\"transmission_sig\":\"signature-abc\""),
                        org.hamcrest.Matchers.containsString("\"webhook_id\":\"webhook-configured-id\""),
                        org.hamcrest.Matchers.containsString("\"webhook_event\":{}"))))
                .andRespond(withSuccess("{\"verification_status\":\"SUCCESS\"}",
                        MediaType.APPLICATION_JSON));

        org.junit.jupiter.api.Assertions.assertTrue(
                client.verifyWebhookSignature(transmission, event));
        server.verify();
    }

    @Test
    void providerSignatureFailureIsNotAccepted() {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        PaypalClient client = new PaypalClient(
                builder.build(), new PaypalProperties(
                        "sandbox-client", "sandbox-secret", "webhook-configured-id"));
        PaypalWebhookTransmission transmission = new PaypalWebhookTransmission(
                "SHA256withRSA", "https://cert.example/cert", "transmission-123",
                "signature-abc", "2026-10-17T10:00:00Z");

        server.expect(requestTo(SANDBOX_API + "/v1/oauth2/token"))
                .andRespond(withSuccess("{\"access_token\":\"sandbox-token\"}",
                        MediaType.APPLICATION_JSON));
        server.expect(requestTo(SANDBOX_API + "/v1/notifications/verify-webhook-signature"))
                .andRespond(withSuccess("{\"verification_status\":\"FAILURE\"}",
                        MediaType.APPLICATION_JSON));

        org.junit.jupiter.api.Assertions.assertFalse(client.verifyWebhookSignature(
                transmission, tools.jackson.databind.node.JsonNodeFactory.instance.objectNode()));
        server.verify();
    }

    @Test
    void webhookVerificationRequiresConfiguredWebhookIdWithoutCallingProvider() {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        PaypalClient client = new PaypalClient(
                builder.build(), new PaypalProperties("sandbox-client", "sandbox-secret"));
        PaypalWebhookTransmission transmission = new PaypalWebhookTransmission(
                "SHA256withRSA", "https://cert.example/cert", "transmission-123",
                "signature-abc", "2026-10-17T10:00:00Z");

        PaypalProviderException exception = assertThrows(PaypalProviderException.class,
                () -> client.verifyWebhookSignature(
                        transmission,
                        tools.jackson.databind.node.JsonNodeFactory.instance.objectNode()));

        assertEquals(HttpStatus.SERVICE_UNAVAILABLE, exception.getStatus());
        server.verify();
    }

    @Test
    void diagnosesOrderWithReferenceIdExtractedFromPurchaseUnit() {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        PaypalClient client = new PaypalClient(
                builder.build(), new PaypalProperties("sandbox-client", "sandbox-secret"));

        server.expect(requestTo(SANDBOX_API + "/v1/oauth2/token"))
                .andRespond(withSuccess("{\"access_token\":\"sandbox-token\"}",
                        MediaType.APPLICATION_JSON));
        server.expect(requestTo(SANDBOX_API + "/v2/checkout/orders/ORDER-123"))
                .andExpect(method(HttpMethod.GET))
                .andRespond(withSuccess(
                        "{\"id\":\"ORDER-123\",\"status\":\"COMPLETED\","
                                + "\"intent\":\"CAPTURE\","
                                + "\"purchase_units\":[{"
                                + "\"reference_id\":\"merchant-ref-123\","
                                + "\"amount\":{\"currency_code\":\"USD\",\"value\":\"42.75\"},"
                                + "\"payments\":null}],"
                                + "\"links\":[]}",
                        MediaType.APPLICATION_JSON));

        PaypalClient.PaypalOrderDiagnostic result =
                client.diagnoseOrder("ORDER-123");

        assertEquals(200, result.httpStatus());
        assertEquals("merchant-ref-123", result.purchaseUnits().get(0).referenceId());
        assertEquals("USD", result.purchaseUnits().get(0).currencyCode());
        assertEquals("42.75", result.purchaseUnits().get(0).value());
        server.verify();
    }

    @Test
    void diagnosesOrderWithMissingReferenceId() {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        PaypalClient client = new PaypalClient(
                builder.build(), new PaypalProperties("sandbox-client", "sandbox-secret"));

        server.expect(requestTo(SANDBOX_API + "/v1/oauth2/token"))
                .andRespond(withSuccess("{\"access_token\":\"sandbox-token\"}",
                        MediaType.APPLICATION_JSON));
        server.expect(requestTo(SANDBOX_API + "/v2/checkout/orders/ORDER-123"))
                .andExpect(method(HttpMethod.GET))
                .andRespond(withSuccess(
                        "{\"id\":\"ORDER-123\",\"status\":\"COMPLETED\","
                                + "\"intent\":\"CAPTURE\","
                                + "\"purchase_units\":[{"
                                + "\"amount\":{\"currency_code\":\"USD\",\"value\":\"42.75\"},"
                                + "\"payments\":null}],"
                                + "\"links\":[]}",
                        MediaType.APPLICATION_JSON));

        PaypalClient.PaypalOrderDiagnostic result =
                client.diagnoseOrder("ORDER-123");

        assertEquals(200, result.httpStatus());
        assertNull(result.purchaseUnits().get(0).referenceId());
        server.verify();
    }

    @Test
    void diagnosesOrderWithCompletedCaptureAndReferenceId() {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        PaypalClient client = new PaypalClient(
                builder.build(), new PaypalProperties("sandbox-client", "sandbox-secret"));

        server.expect(requestTo(SANDBOX_API + "/v1/oauth2/token"))
                .andRespond(withSuccess("{\"access_token\":\"sandbox-token\"}",
                        MediaType.APPLICATION_JSON));
        server.expect(requestTo(SANDBOX_API + "/v2/checkout/orders/ORDER-123"))
                .andExpect(method(HttpMethod.GET))
                .andRespond(withSuccess(
                        "{\"id\":\"ORDER-123\",\"status\":\"COMPLETED\","
                                + "\"intent\":\"CAPTURE\","
                                + "\"purchase_units\":[{"
                                + "\"reference_id\":\"merchant-ref-123\","
                                + "\"amount\":{\"currency_code\":\"USD\",\"value\":\"42.75\"},"
                                + "\"payments\":{\"captures\":[{"
                                + "\"id\":\"CAPTURE-456\",\"status\":\"COMPLETED\","
                                + "\"amount\":{\"currency_code\":\"USD\",\"value\":\"42.75\"}}]}}],"
                                + "\"links\":[]}",
                        MediaType.APPLICATION_JSON));

        PaypalClient.PaypalOrderDiagnostic result =
                client.diagnoseOrder("ORDER-123");

        assertEquals(200, result.httpStatus());
        assertEquals("merchant-ref-123", result.purchaseUnits().get(0).referenceId());
        assertEquals("CAPTURE-456", result.purchaseUnits().get(0).captureId());
        assertEquals("COMPLETED", result.purchaseUnits().get(0).captureStatus());
        assertEquals("42.75", result.purchaseUnits().get(0).captureAmount());
        assertEquals("USD", result.purchaseUnits().get(0).captureCurrency());
        server.verify();
    }

    private PaymentAttempt attempt() {
        MerchantOrder order = new MerchantOrder();
        order.setMerchantOrderReference("merchant-ref");
        order.setAmount(new java.math.BigDecimal("42.75"));
        order.setCurrency("USD");

        PaymentAttempt attempt = new PaymentAttempt();
        attempt.setId(UUID.randomUUID());
        attempt.setOrder(order);
        attempt.setAmount(order.getAmount());
        attempt.setCurrency(order.getCurrency());
        return attempt;
    }

    private String requestId(UUID attemptId, String operation) {
        return UUID.nameUUIDFromBytes(
                (attemptId + ":" + operation).getBytes(StandardCharsets.UTF_8)).toString();
    }
}
