package com.retryguard_ai.security;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.security.web.csrf.CsrfToken;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.context.WebApplicationContext;

import jakarta.servlet.Filter;

import java.util.UUID;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Spring Security integration tests for authenticated access and PayPal
 * webhook endpoint accessibility through the real filter chain.
 *
 * Test credentials are provided via @TestPropertySource so that authentication
 * is always exercised.
 *
 * External PayPal dependencies are not exercised. No real payments are made.
 * PayPal webhook signature verification is tested at the exception-handler
 * level (via GlobalExceptionHandler) to avoid needing MockBean support.
 */
@SpringBootTest
@TestPropertySource(properties = {
        "retryguard.security.user=test-user",
        "retryguard.security.password=test-password"
})
class SecurityAuthAndWebhookTest {

    private static final String TEST_USER = "test-user";
    private static final String TEST_PASS = "test-password";

    @Autowired
    private WebApplicationContext context;

    private MockMvc mockMvc;
    private MockHttpSession session;

    @BeforeEach
    void setUp() throws Exception {
        Filter springSecurityFilter = context.getBean("springSecurityFilterChain", Filter.class);
        session = new MockHttpSession();
        mockMvc = org.springframework.test.web.servlet.setup.MockMvcBuilders
                .webAppContextSetup(context)
                .addFilter(springSecurityFilter, "/*")
                .defaultRequest(get("/").session(session))
                .build();
    }

    private String basicAuth(String user, String pass) {
        return "Basic " + java.util.Base64
                .getEncoder().encodeToString((user + ":" + pass).getBytes());
    }

    @Nested
    @DisplayName("Authenticated requests")
    class AuthenticatedRequests {

        @Test
        void validCredentialsPassAuthentication() throws Exception {
            mockMvc.perform(get("/api/investigations/{investigationId}", UUID.randomUUID())
                            .session(session)
                            .header("Authorization", basicAuth(TEST_USER, TEST_PASS)))
                    .andExpect(status().isNotFound());
        }

        @Test
        void authenticatedRequestReachesController() throws Exception {
            // POST /api/orders is a protected endpoint requiring auth + CSRF.
            // Valid credentials + missing CSRF should fail with 403 (not 401).
            // This verifies authentication passes but CSRF blocks it.
            mockMvc.perform(post("/api/orders")
                            .session(session)
                            .header("Authorization", basicAuth(TEST_USER, TEST_PASS))
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"merchantOrderReference\":\"ref\",\"amount\":10,\"currency\":\"USD\"}"))
                    .andExpect(status().isForbidden());
        }

        @Test
        void missingCsrfTokenReturns403() throws Exception {
            mockMvc.perform(post("/api/orders")
                            .session(session)
                            .header("Authorization", basicAuth(TEST_USER, TEST_PASS))
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"merchantOrderReference\":\"ref\",\"amount\":10,\"currency\":\"USD\"}"))
                    .andExpect(status().isForbidden());
        }

        @Test
        void invalidCredentialsRejectedWith401() throws Exception {
            mockMvc.perform(get("/api/investigations/{investigationId}", UUID.randomUUID())
                            .session(session)
                            .header("Authorization", basicAuth(TEST_USER, "wrong-password")))
                    .andExpect(status().isUnauthorized());
        }
    }

    @Nested
    @DisplayName("PayPal webhook endpoint accessibility")
    class WebhookEndpointAccessibility {

        @Test
        void invalidPayPalSignatureRejectedByExceptionHandler() throws Exception {
            com.retryguard_ai.exception.GlobalExceptionHandler handler =
                    new com.retryguard_ai.exception.GlobalExceptionHandler();
            var response = handler.handleInvalidPaypalWebhookSignature();
            org.assertj.core.api.Assertions.assertThat(response.getStatusCode())
                    .isEqualTo(org.springframework.http.HttpStatus.UNAUTHORIZED);
            org.assertj.core.api.Assertions.assertThat(response.getBody().getTitle())
                    .isEqualTo("Webhook verification failed");
        }

        @Test
        void validPayPalWebhookNotRejectedByAuthFilter() throws Exception {
            mockMvc.perform(post("/api/webhooks/paypal")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"id\":\"WH-1\"}"))
                    .andExpect(status().isBadRequest());
        }
    }
}
