package com.retryguard_ai.paypal;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.ConstructorBinding;

import java.net.URI;
import java.util.UUID;

@ConfigurationProperties(prefix = "retryguard.paypal")
public record PaypalProperties(
        String clientId, String clientSecret, String webhookId, String apiBaseUrl,
        String publicBaseUrl) {

    private static final String SANDBOX_API_BASE_URL = "https://api-m.sandbox.paypal.com";

    @ConstructorBinding
    public PaypalProperties {
    }

    public PaypalProperties(
            String clientId, String clientSecret, String webhookId, String apiBaseUrl) {
        this(clientId, clientSecret, webhookId, apiBaseUrl, null);
    }

    public PaypalProperties(String clientId, String clientSecret, String webhookId) {
        this(clientId, clientSecret, webhookId, SANDBOX_API_BASE_URL, null);
    }

    public PaypalProperties(String clientId, String clientSecret) {
        this(clientId, clientSecret, null, SANDBOX_API_BASE_URL, null);
    }

    public boolean isCheckoutExperienceConfigured() {
        return isPublicHttpsUrl(publicBaseUrl);
    }

    public String returnUrl(UUID paymentAttemptId) {
        return callbackUrl(paymentAttemptId, "return");
    }

    public String cancelUrl(UUID paymentAttemptId) {
        return callbackUrl(paymentAttemptId, "cancel");
    }

    public boolean isConfigured() {
        return clientId != null && !clientId.isBlank()
                && clientSecret != null && !clientSecret.isBlank();
    }

    public boolean isWebhookConfigured() {
        return isConfigured() && webhookId != null && !webhookId.isBlank();
    }

    private static boolean isPublicHttpsUrl(String value) {
        if (value == null || value.isBlank()) {
            return false;
        }
        try {
            URI uri = URI.create(value);
            String host = uri.getHost();
            return uri.isAbsolute()
                    && "https".equalsIgnoreCase(uri.getScheme())
                    && host != null
                    && uri.getRawUserInfo() == null
                    && uri.getRawQuery() == null
                    && uri.getRawFragment() == null
                    && !host.equalsIgnoreCase("localhost")
                    && !host.toLowerCase(java.util.Locale.ROOT).endsWith(".localhost")
                    && !host.startsWith("127.")
                    && !host.equals("::1");
        } catch (IllegalArgumentException exception) {
            return false;
        }
    }

    private String callbackUrl(UUID paymentAttemptId, String callback) {
        String baseUrl = publicBaseUrl.endsWith("/")
                ? publicBaseUrl.substring(0, publicBaseUrl.length() - 1)
                : publicBaseUrl;
        return baseUrl + "/api/payment-attempts/" + paymentAttemptId
                + "/paypal/" + callback;
    }
}
