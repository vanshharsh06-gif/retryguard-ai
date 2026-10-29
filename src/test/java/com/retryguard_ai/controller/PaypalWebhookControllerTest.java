package com.retryguard_ai.controller;

import com.retryguard_ai.exception.GlobalExceptionHandler;
import com.retryguard_ai.exception.InvalidPaypalWebhookRequestException;
import com.retryguard_ai.exception.InvalidPaypalWebhookSignatureException;
import com.retryguard_ai.paypal.PaypalWebhookTransmission;
import com.retryguard_ai.service.PaypalWebhookProcessingResult;
import com.retryguard_ai.service.PaypalWebhookService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@ExtendWith(MockitoExtension.class)
class PaypalWebhookControllerTest {

    @Mock
    private PaypalWebhookService paypalWebhookService;

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.standaloneSetup(
                        new PaypalWebhookController(paypalWebhookService))
                .setControllerAdvice(new GlobalExceptionHandler())
                .build();
    }

    @Test
    void acceptsVerifiedWebhookAndPreservesOriginalJsonText() throws Exception {
        String payload = "{ \"id\" : \"WH-1\", \"event_type\" : \"PAYMENT.CAPTURE.COMPLETED\" }";
        when(paypalWebhookService.receive(any(), any()))
                .thenReturn(new PaypalWebhookProcessingResult(true, false));

        mockMvc.perform(post("/api/webhooks/paypal")
                        .header("PAYPAL-AUTH-ALGO", "SHA256withRSA")
                        .header("PAYPAL-CERT-URL", "https://cert.example/cert")
                        .header("PAYPAL-TRANSMISSION-ID", "tx-1")
                        .header("PAYPAL-TRANSMISSION-SIG", "signature")
                        .header("PAYPAL-TRANSMISSION-TIME", "2026-10-17T10:00:00Z")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(payload))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("ACCEPTED"));

        ArgumentCaptor<PaypalWebhookTransmission> transmission =
                ArgumentCaptor.forClass(PaypalWebhookTransmission.class);
        ArgumentCaptor<String> rawPayload = ArgumentCaptor.forClass(String.class);
        verify(paypalWebhookService).receive(transmission.capture(), rawPayload.capture());
        assertEquals("tx-1", transmission.getValue().transmissionId());
        assertEquals(payload, rawPayload.getValue());
    }

    @Test
    void missingTransmissionHeadersAreRejected() throws Exception {
        when(paypalWebhookService.receive(any(), any()))
                .thenThrow(new InvalidPaypalWebhookRequestException("Invalid webhook request."));

        mockMvc.perform(post("/api/webhooks/paypal")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"id\":\"WH-1\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.title").value("Invalid PayPal webhook request"));
    }

    @Test
    void duplicateDeliveryGetsExplicitDuplicateResponse() throws Exception {
        when(paypalWebhookService.receive(any(), any()))
                .thenReturn(new PaypalWebhookProcessingResult(true, true));

        mockMvc.perform(post("/api/webhooks/paypal")
                        .header("PAYPAL-AUTH-ALGO", "SHA256withRSA")
                        .header("PAYPAL-CERT-URL", "https://cert.example/cert")
                        .header("PAYPAL-TRANSMISSION-ID", "tx-1")
                        .header("PAYPAL-TRANSMISSION-SIG", "signature")
                        .header("PAYPAL-TRANSMISSION-TIME", "2026-10-17T10:00:00Z")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"id\":\"WH-1\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("DUPLICATE"));
    }

    @Test
    void rejectedSignatureReturnsUnauthorized() throws Exception {
        when(paypalWebhookService.receive(any(), any()))
                .thenThrow(new InvalidPaypalWebhookSignatureException());

        mockMvc.perform(post("/api/webhooks/paypal")
                        .header("PAYPAL-AUTH-ALGO", "SHA256withRSA")
                        .header("PAYPAL-CERT-URL", "https://cert.example/cert")
                        .header("PAYPAL-TRANSMISSION-ID", "tx-1")
                        .header("PAYPAL-TRANSMISSION-SIG", "signature")
                        .header("PAYPAL-TRANSMISSION-TIME", "2026-10-17T10:00:00Z")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"id\":\"WH-1\"}"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.status").value(401));
    }
}
