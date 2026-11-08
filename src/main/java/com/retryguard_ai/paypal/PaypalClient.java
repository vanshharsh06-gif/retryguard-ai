package com.retryguard_ai.paypal;

import com.retryguard_ai.entity.PaymentAttempt;
import com.retryguard_ai.exception.PaypalProviderException;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;
import tools.jackson.databind.JsonNode;

import java.net.URI;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.UUID;

@Component
public class PaypalClient {

    private final RestClient restClient;
    private final PaypalProperties properties;

    public PaypalClient(RestClient paypalRestClient, PaypalProperties properties) {
        this.restClient = paypalRestClient;
        this.properties = properties;
    }

    public PaypalOrderResult createOrder(PaymentAttempt attempt) {
        if (!properties.isCheckoutExperienceConfigured()) {
            throw new PaypalProviderException(
                    HttpStatus.SERVICE_UNAVAILABLE,
                    "PayPal checkout return and cancel URLs must be configured as absolute public HTTPS URLs.");
        }

        PaypalCreateOrderRequest request = new PaypalCreateOrderRequest(
                "CAPTURE",
                List.of(new PaypalPurchaseUnit(
                        attempt.getOrder().getMerchantOrderReference(),
                        new PaypalAmount(
                                attempt.getCurrency(),
                                attempt.getAmount().setScale(2, RoundingMode.UNNECESSARY)
                                        .toPlainString()),
                        null)),
                new PaypalPaymentSource(
                        new PaypalPaypal(
                                new PaypalExperienceContext(
                                        properties.returnUrl(attempt.getId()),
                                        properties.cancelUrl(attempt.getId())))));

        PaypalOrderResponse response = execute(() -> restClient.post()
                .uri(properties.apiBaseUrl() + "/v2/checkout/orders")
                .headers(headers -> {
                    headers.setBearerAuth(accessToken());
                    headers.set("PayPal-Request-Id", requestId(attempt.getId(), "create"));
                })
                .contentType(MediaType.APPLICATION_JSON)
                .body(request)
                .retrieve()
                .body(PaypalOrderResponse.class));

        if (response == null || response.id() == null || response.id().isBlank()
                || response.status() == null || response.status().isBlank()) {
            throw providerFailure();
        }
        String approvalUrl = response.links() == null ? null : response.links().stream()
                .filter(link -> "approve".equals(link.rel()) || "payer-action".equals(link.rel()))
                .map(PaypalLink::href)
                .filter(href -> href != null && !href.isBlank())
                .findFirst()
                .orElse(null);
        return new PaypalOrderResult(response.id(), response.status(), approvalUrl);
    }

    public PaypalOrderDiagnostic diagnoseOrder(String orderId) {
        try {
            var response = restClient.get()
                    .uri(properties.apiBaseUrl() + "/v2/checkout/orders/{orderId}", orderId)
                    .headers(headers -> headers.setBearerAuth(accessToken()))
                    .retrieve()
                    .toEntity(PaypalOrderResponse.class);

            PaypalOrderResponse body = response.getBody();
            List<PaypalOrderUnitDiagnostic> units = body == null || body.purchase_units() == null
                    ? List.of()
                    : body.purchase_units().stream()
                            .map(unit -> resolveCaptureFromUnit(unit))
                            .toList();
            PaypalOrderApprovalLink approvalLink = body == null || body.links() == null
                    ? null
                    : body.links().stream()
                            .filter(link -> "approve".equals(link.rel())
                                    || "payer-action".equals(link.rel()))
                            .filter(link -> link.href() != null && !link.href().isBlank())
                            .findFirst()
                            .map(PaypalClient::diagnosticApprovalLink)
                            .orElse(null);

            return new PaypalOrderDiagnostic(
                    response.getStatusCode().value(),
                    body == null ? null : body.status(),
                    body == null ? null : body.intent(),
                    units, approvalLink, null, null, null);
        } catch (RestClientResponseException exception) {
            PaypalErrorResponse error = exception.getResponseBodyAs(PaypalErrorResponse.class);
            return new PaypalOrderDiagnostic(
                    exception.getStatusCode().value(), null, null, List.of(), null,
                    error == null ? null : error.name(),
                    error == null ? null : error.message(),
                    error == null ? null : error.debug_id());
        }
    }

    private PaypalOrderUnitDiagnostic resolveCaptureFromUnit(PaypalPurchaseUnit unit) {
        String referenceId = unit.reference_id();
        String currencyCode = unit.amount() == null ? null : unit.amount().currency_code();
        String value = unit.amount() == null ? null : unit.amount().value();
        PaypalPayments payments = unit.payments();
        List<PaypalCapture> captures = payments == null ? List.of()
                : (payments.captures() == null ? List.of() : payments.captures());

        if (captures.isEmpty()) {
            return new PaypalOrderUnitDiagnostic(referenceId, currencyCode, value,
                    null, null, null, null);
        }
        if (captures.size() == 1) {
            PaypalCapture capture = captures.get(0);
            return new PaypalOrderUnitDiagnostic(
                    referenceId, currencyCode, value,
                    capture.id(), capture.status(),
                    capture.amount() == null ? null : capture.amount().value(),
                    capture.amount() == null ? null : capture.amount().currency_code());
        }
        // Multiple captures exist - resolve using reliable identifiers or reject ambiguity
        PaypalCapture resolved = selectCapture(captures);
        if (resolved == null) {
            return new PaypalOrderUnitDiagnostic(referenceId, currencyCode, value,
                    null, null, null, null);
        }
        return new PaypalOrderUnitDiagnostic(
                referenceId, currencyCode, value,
                resolved.id(), resolved.status(),
                resolved.amount() == null ? null : resolved.amount().value(),
                resolved.amount() == null ? null : resolved.amount().currency_code());
    }

    private PaypalCapture selectCapture(List<PaypalCapture> captures) {
        // When multiple captures exist, do not blindly select the first.
        // Return null to indicate ambiguity that must be resolved by the caller
        // using reliable identifiers (e.g., stored capture reference).
        return null;
    }

    private static PaypalOrderApprovalLink diagnosticApprovalLink(PaypalLink link) {
        try {
            URI uri = URI.create(link.href());
            String scheme = uri.getScheme();
            String host = uri.getHost();
            String path = uri.getRawPath();
            return new PaypalOrderApprovalLink(
                    link.rel(), scheme, host, path,
                    "www.sandbox.paypal.com".equalsIgnoreCase(host));
        } catch (IllegalArgumentException exception) {
            return new PaypalOrderApprovalLink(link.rel(), null, null, null, false);
        }
    }

    public PaypalCaptureResult captureOrder(UUID attemptId, String paypalOrderId) {
        PaypalCaptureResponse response = execute(() -> restClient.post()
                .uri(properties.apiBaseUrl() + "/v2/checkout/orders/{orderId}/capture", paypalOrderId)
                .headers(headers -> {
                    headers.setBearerAuth(accessToken());
                    headers.set("PayPal-Request-Id", requestId(attemptId, "capture"));
                })
                .contentType(MediaType.APPLICATION_JSON)
                .body(new PaypalCaptureRequest())
                .retrieve()
                .body(PaypalCaptureResponse.class));
        if (response == null || response.id() == null || response.id().isBlank()
                || response.purchaseUnits() == null
                || response.purchaseUnits().isEmpty()
                || response.purchaseUnits().get(0).payments() == null
                || response.purchaseUnits().get(0).payments().captures() == null
                || response.purchaseUnits().get(0).payments().captures().isEmpty()) {
            throw providerFailure();
        }
        PaypalCapture capture = response.purchaseUnits().get(0)
                .payments().captures().get(0);
        if (capture.id() == null || capture.id().isBlank()
                || capture.status() == null || capture.status().isBlank()) {
            throw providerFailure();
        }
        PaypalAmount amount = capture.amount();
        return new PaypalCaptureResult(
                response.id(),
                capture.id(),
                capture.status(),
                amount == null ? null : amount.value(),
                amount == null ? null : amount.currency_code());
    }

    public boolean verifyWebhookSignature(
            PaypalWebhookTransmission transmission, JsonNode webhookEvent) {
        if (!properties.isWebhookConfigured()) {
            throw new PaypalProviderException(
                    HttpStatus.SERVICE_UNAVAILABLE,
                    "PayPal webhook verification is not configured.");
        }
        PaypalWebhookVerificationResponse response = execute(() -> restClient.post()
                .uri(properties.apiBaseUrl() + "/v1/notifications/verify-webhook-signature")
                .headers(headers -> headers.setBearerAuth(accessToken()))
                .contentType(MediaType.APPLICATION_JSON)
                .body(new PaypalWebhookVerificationRequest(
                        transmission.authAlgo(),
                        transmission.certUrl(),
                        transmission.transmissionId(),
                        transmission.transmissionSignature(),
                        transmission.transmissionTime(),
                        properties.webhookId(),
                        webhookEvent))
                .retrieve()
                .body(PaypalWebhookVerificationResponse.class));
        return response != null && "SUCCESS".equals(response.verification_status());
    }

    private String accessToken() {
        if (!properties.isConfigured()) {
            throw new PaypalProviderException(
                    HttpStatus.SERVICE_UNAVAILABLE,
                    "PayPal Sandbox credentials are not configured.");
        }

        try {
            MultiValueMap<String, String> form = new LinkedMultiValueMap<>();
            form.add("grant_type", "client_credentials");
            PaypalAccessTokenResponse response = restClient.post()
                    .uri(properties.apiBaseUrl() + "/v1/oauth2/token")
                    .headers(headers -> {
                        headers.setBasicAuth(properties.clientId(), properties.clientSecret());
                        headers.setContentType(MediaType.APPLICATION_FORM_URLENCODED);
                    })
                    .body(form)
                    .retrieve()
                    .body(PaypalAccessTokenResponse.class);
            if (response == null || response.accessToken() == null
                    || response.accessToken().isBlank()) {
                throw providerFailure();
            }
            return response.accessToken();
        } catch (PaypalProviderException exception) {
            throw exception;
        } catch (RestClientResponseException exception) {
            throw providerFailure();
        } catch (RestClientException exception) {
            throw providerFailure();
        }
    }

    private <T> T execute(ProviderCall<T> call) {
        try {
            return call.run();
        } catch (PaypalProviderException exception) {
            throw exception;
        } catch (RestClientResponseException exception) {
            throw providerFailure();
        } catch (RestClientException exception) {
            throw providerFailure();
        }
    }

    private PaypalProviderException providerFailure() {
        return new PaypalProviderException(
                HttpStatus.BAD_GATEWAY,
                "PayPal Sandbox could not complete the requested operation.");
    }

    private String requestId(UUID attemptId, String operation) {
        byte[] source = (attemptId + ":" + operation).getBytes(StandardCharsets.UTF_8);
        return UUID.nameUUIDFromBytes(source).toString();
    }

    @FunctionalInterface
    private interface ProviderCall<T> {
        T run();
    }

    private record PaypalCreateOrderRequest(
            String intent, List<PaypalPurchaseUnit> purchase_units,
            PaypalPaymentSource payment_source) {
    }

    private record PaypalPurchaseUnit(String reference_id, PaypalAmount amount, PaypalPayments payments) {
    }

    private record PaypalPaymentSource(PaypalPaypal paypal) {
    }

    private record PaypalPaypal(PaypalExperienceContext experience_context) {
    }

    private record PaypalExperienceContext(String return_url, String cancel_url) {
    }

    private record PaypalAmount(String currency_code, String value) {
    }

    private record PaypalAccessTokenResponse(String access_token) {
        String accessToken() {
            return access_token;
        }
    }

    private record PaypalOrderResponse(
            String id, String status, String intent,
            List<PaypalPurchaseUnit> purchase_units, List<PaypalLink> links) {
    }

    private record PaypalLink(String rel, String href) {
    }

    private record PaypalErrorResponse(String name, String message, String debug_id) {
    }

    public record PaypalOrderDiagnostic(
            int httpStatus, String status, String intent,
            List<PaypalOrderUnitDiagnostic> purchaseUnits, PaypalOrderApprovalLink approvalLink,
            String errorName, String errorMessage, String debugId) {
    }

    public record PaypalOrderUnitDiagnostic(String referenceId, String currencyCode, String value,
                                            String captureId, String captureStatus,
                                            String captureAmount, String captureCurrency) {
    }

    public record PaypalOrderApprovalLink(
            String rel, String scheme, String host, String path,
            boolean expectedSandboxCheckoutHost) {
    }

    private record PaypalCaptureRequest() {
    }

    private record PaypalCaptureResponse(
            String id, List<PaypalCapturePurchaseUnit> purchase_units) {
        List<PaypalCapturePurchaseUnit> purchaseUnits() {
            return purchase_units;
        }
    }

    private record PaypalCapturePurchaseUnit(PaypalPayments payments) {
    }

    private record PaypalPayments(List<PaypalCapture> captures) {
    }

    private record PaypalCapture(String id, String status, PaypalAmount amount) {
    }

    private record PaypalWebhookVerificationRequest(
            String auth_algo,
            String cert_url,
            String transmission_id,
            String transmission_sig,
            String transmission_time,
            String webhook_id,
            JsonNode webhook_event) {
    }

    private record PaypalWebhookVerificationResponse(String verification_status) {
    }
}
