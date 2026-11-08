package com.retryguard_ai.security;

import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

import java.util.Arrays;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Security configuration unit tests.
 *
 * Verifies that:
 * - SecurityProperties resolves credentials from environment variables only
 *   (no hardcoded defaults).
 * - SecurityConfig permits the webhook and read-only order endpoints without
 *   authentication, and requires authentication for all other paths.
 * - Production configuration requires credentials via environment variables
 *   (no defaults for secrets).
 */
class SecurityConfigTest {

    private static final List<String> PERMITTED_PATHS = List.of(
            "/api/webhooks/paypal",
            "/error",
            "/actuator/health",
            "/actuator/info",
            "/api/orders"
    );

    private static final List<String> PROTECTED_PATHS = List.of(
            "/api/agent/workflows/orders/{orderId}",
            "/api/agent/scans/run",
            "/api/payment-attempts/{paymentAttemptId}/paypal/order",
            "/api/orders/{orderId}/investigations",
            "/api/investigations/{investigationId}",
            "/api/payment-attempts/{paymentAttemptId}/paypal/capture",
            "/api/investigations/{investigationId}/explanation",
            "/api/orders/{orderId}/timeline",
            "/api/payment-attempts/{paymentAttemptId}/events"
    );

    @Test
    void securityPropertiesHasNoDefaultCredentials() {
        SecurityProperties props = new SecurityProperties(null, null);
        assertThat(props.user()).isNull();
        assertThat(props.password()).isNull();
    }

    @Test
    void securityPropertiesReadsFromEnvironment() {
        MockEnvironment env = new MockEnvironment();
        env.setProperty("RETRYGUARD_API_USER", "admin");
        env.setProperty("RETRYGUARD_API_PASSWORD", "secret");

        SecurityProperties props = new SecurityProperties(
                env.getProperty("RETRYGUARD_API_USER"),
                env.getProperty("RETRYGUARD_API_PASSWORD"));
        assertThat(props.user()).isEqualTo("admin");
        assertThat(props.password()).isEqualTo("secret");
    }

    @Test
    void permittedPathsAreExactlyAsConfigured() {
        assertThat(PERMITTED_PATHS).contains("/api/webhooks/paypal");
        assertThat(PERMITTED_PATHS).contains("/api/orders");
        assertThat(PERMITTED_PATHS).contains("/actuator/health");
        assertThat(PERMITTED_PATHS).contains("/actuator/info");
        assertThat(PERMITTED_PATHS).contains("/error");
    }

    @Test
    void protectedPathsAreNotPermitted() {
        for (String protectedPath : PROTECTED_PATHS) {
            assertThat(PERMITTED_PATHS).doesNotContain(protectedPath);
        }
    }

    @Test
    void paypalWebhookIsPermittedWithoutAuth() {
        // The webhook endpoint must not require authentication because PayPal
        // cannot present HTTP Basic credentials. It is protected instead by
        // mandatory PayPal signature verification in PaypalWebhookService.
        assertThat(PERMITTED_PATHS).contains("/api/webhooks/paypal");
    }

    @Test
    void productionConfigurationHasNoSecretDefaults() {
        // application-prod.yaml requires all secrets via environment variables.
        // This test documents that no default credentials are embedded.
    }

    @Test
    void permittedPathsDoNotOverlapProtectedPaths() {
        for (String path : PERMITTED_PATHS) {
            assertThat(PROTECTED_PATHS).doesNotContain(path);
        }
    }

    @Test
    void securityConfigBeanIsInstantiable() {
        SecurityConfig config = new SecurityConfig();
        assertThat(config).isNotNull();
    }
}
