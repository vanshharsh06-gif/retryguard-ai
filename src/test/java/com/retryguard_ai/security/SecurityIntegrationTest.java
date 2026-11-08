package com.retryguard_ai.security;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.context.WebApplicationContext;

import java.util.UUID;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Genuine Spring Security integration tests exercising the configured filter
 * chain through real HTTP requests (MockMvc).
 *
 * Uses {@link SecurityMockMvcConfigurers#springSecurity()} so that CSRF
 * tokens are properly generated and handled by MockMvc, allowing tests to
 * distinguish CSRF rejection (403) from authentication rejection (401).
 *
 * These tests verify authentication, authorization, and CSRF protection
 * against the actual Spring Security configuration. No real payments are
 * made.
 */
@SpringBootTest
class SecurityIntegrationTest {

    @Autowired
    private WebApplicationContext context;

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        mockMvc = org.springframework.test.web.servlet.setup.MockMvcBuilders
                .webAppContextSetup(context)
                .apply(SecurityMockMvcConfigurers.springSecurity())
                .build();
    }

    @Nested
    @DisplayName("Unauthenticated requests")
    class UnauthenticatedRequests {

        @Test
        void getOrdersPermittedWithoutAuthentication() throws Exception {
            mockMvc.perform(get("/api/orders"))
                    .andExpect(status().isOk());
        }

        @Test
        void postOrdersRejectedByCsrfWithoutToken() throws Exception {
            // POST without CSRF token is blocked by CSRF protection (403)
            // before reaching authentication.
            mockMvc.perform(post("/api/orders")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"merchantOrderReference\":\"ref\",\"amount\":10,\"currency\":\"USD\"}"))
                    .andExpect(status().isForbidden());
        }

        @Test
        void postOrdersWithCsrfRejectedWith401() throws Exception {
            // POST with valid CSRF token passes CSRF protection but is
            // rejected by authentication (no credentials configured).
            mockMvc.perform(postWithCsrf("/api/orders")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"merchantOrderReference\":\"ref\",\"amount\":10,\"currency\":\"USD\"}"))
                    .andExpect(status().isUnauthorized());
        }

        @Test
        void postInvestigationRejectedWith401() throws Exception {
            mockMvc.perform(postWithCsrf("/api/orders/{orderId}/investigations", UUID.randomUUID()))
                    .andExpect(status().isUnauthorized());
        }

        @Test
        void postAgentScanRejectedWith401() throws Exception {
            mockMvc.perform(postWithCsrf("/api/agent/scans/run"))
                    .andExpect(status().isUnauthorized());
        }

        @Test
        void postPaypalOrderCreationRejectedWith401() throws Exception {
            mockMvc.perform(postWithCsrf("/api/payment-attempts/{paymentAttemptId}/paypal/order",
                            UUID.randomUUID()))
                    .andExpect(status().isUnauthorized());
        }

        @Test
        void webhookNotRejectedByAuthenticationFilter() throws Exception {
            // Webhook endpoint must not require authentication. Even with an
            // incomplete request it is rejected by the webhook service (400),
            // not by Spring Security (401/403).
            mockMvc.perform(post("/api/webhooks/paypal")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"id\":\"WH-1\"}"))
                    .andExpect(status().isBadRequest());
        }
    }

    @Nested
    @DisplayName("HTTP Basic authentication")
    class HttpBasicAuthentication {

        @Test
        void invalidCredentialsRejectedWith401() throws Exception {
            // Use GET (no CSRF required) with invalid Basic auth to test
            // the authentication layer directly.
            mockMvc.perform(get("/api/investigations/{investigationId}", UUID.randomUUID())
                            .header("Authorization", "Basic " + java.util.Base64
                                    .getEncoder().encodeToString(("wrong:wrong").getBytes())))
                    .andExpect(status().isUnauthorized());
        }
    }

    /**
     * Builds a POST request with a CSRF token using MockMvc's built-in
     * CSRF support via SecurityMockMvcConfigurers.
     */
    private static org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder postWithCsrf(
            String uri, Object... uriVariables) {
        return post(uri, uriVariables)
                .with(org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf());
    }
}
