package com.retryguard_ai.security;

import com.retryguard_ai.exception.GlobalExceptionHandler;
import com.retryguard_ai.exception.InvalidPaypalWebhookSignatureException;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Tests verifying that:
 * 1. PayPal webhook signature verification remains mandatory (invalid
 *    signatures are rejected with 401 via GlobalExceptionHandler).
 * 2. Unexpected exceptions return a generic error response without leaking
 *    stack traces, database details, internal implementation details, or
 *    credentials.
 */
class SecurityWebhookAndExceptionTest {

    @Test
    void invalidPayPalWebhookSignatureReturnsUnauthorized() {
        GlobalExceptionHandler handler = new GlobalExceptionHandler();
        ResponseEntity<ProblemDetail> response =
                handler.handleInvalidPaypalWebhookSignature();

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        ProblemDetail problem = response.getBody();
        assertThat(problem).isNotNull();
        assertThat(problem.getTitle()).isEqualTo("Webhook verification failed");
        assertThat(problem.getDetail())
                .isEqualTo("PayPal webhook signature was not accepted.");
        // No stack trace or internal details are exposed in the response.
    }

    @Test
    void genericExceptionReturnsSafeMessageWithoutStackTrace() {
        GlobalExceptionHandler handler = new GlobalExceptionHandler();
        ResponseEntity<ProblemDetail> response =
                handler.handleUnexpectedException(new RuntimeException("boom"));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR);
        ProblemDetail problem = response.getBody();
        assertThat(problem).isNotNull();
        assertThat(problem.getTitle()).isEqualTo("Internal server error");
        assertThat(problem.getDetail())
                .isEqualTo("An unexpected server error occurred.");
        // The detail does not include the original exception message or stack trace.
    }

    @Test
    void invalidWebhookSignatureExceptionIsInstantiable() {
        InvalidPaypalWebhookSignatureException exception =
                new InvalidPaypalWebhookSignatureException();
        assertThat(exception).isNotNull();
        assertThat(exception).isInstanceOf(RuntimeException.class);
    }
}
