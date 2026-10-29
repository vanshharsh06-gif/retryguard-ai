package com.retryguard_ai.controller;

import com.retryguard_ai.exception.GlobalExceptionHandler;
import com.retryguard_ai.exception.PaypalOperationNotAllowedException;
import com.retryguard_ai.service.PaypalPaymentService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.UUID;

import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@ExtendWith(MockitoExtension.class)
class PaypalPaymentControllerTest {

    @Mock
    private PaypalPaymentService paypalPaymentService;

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.standaloneSetup(
                        new PaypalPaymentController(paypalPaymentService))
                .setControllerAdvice(new GlobalExceptionHandler())
                .build();
    }

    @Test
    void returnRedirectOffersExistingCapturePostWithoutClaimingPaymentSuccess() throws Exception {
        UUID attemptId = UUID.randomUUID();

        mockMvc.perform(get("/api/payment-attempts/{id}/paypal/return", attemptId)
                        .queryParam("token", "ORDER-123"))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith("text/html"))
                .andExpect(content().string(org.hamcrest.Matchers.allOf(
                        org.hamcrest.Matchers.containsString(
                                "action=\"/api/payment-attempts/" + attemptId + "/paypal/capture\""),
                        org.hamcrest.Matchers.containsString("method=\"post\""),
                        org.hamcrest.Matchers.containsString(
                                "Approval does not confirm payment."))));

        verify(paypalPaymentService).validateReturn(attemptId, "ORDER-123");
    }

    @Test
    void cancellationRedirectSaysStatusWasNotChanged() throws Exception {
        UUID attemptId = UUID.randomUUID();

        mockMvc.perform(get("/api/payment-attempts/{id}/paypal/cancel", attemptId)
                        .queryParam("token", "ORDER-123"))
                .andExpect(status().isOk())
                .andExpect(content().string(org.hamcrest.Matchers.containsString(
                        "Payment status was not changed.")));

        verify(paypalPaymentService).validateCancellation(attemptId, "ORDER-123");
    }

    @Test
    void returnRedirectRejectsUnmatchedAttemptOrOrderToken() throws Exception {
        UUID attemptId = UUID.randomUUID();
        doThrow(new PaypalOperationNotAllowedException(
                "The PayPal redirect could not be matched to this payment attempt."))
                .when(paypalPaymentService).validateReturn(attemptId, "OTHER-ORDER");

        mockMvc.perform(get("/api/payment-attempts/{id}/paypal/return", attemptId)
                        .queryParam("token", "OTHER-ORDER"))
                .andExpect(status().isConflict());

        verify(paypalPaymentService).validateReturn(attemptId, "OTHER-ORDER");
    }
}
