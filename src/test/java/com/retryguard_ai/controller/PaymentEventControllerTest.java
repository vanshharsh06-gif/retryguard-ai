package com.retryguard_ai.controller;

import com.retryguard_ai.dto.RecordPaymentEventRequest;
import com.retryguard_ai.entity.PaymentAttempt;
import com.retryguard_ai.entity.PaymentEvent;
import com.retryguard_ai.exception.GlobalExceptionHandler;
import com.retryguard_ai.exception.PaymentAttemptNotFoundException;
import com.retryguard_ai.service.PaymentEventCreationResult;
import com.retryguard_ai.service.PaymentEventService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import tools.jackson.databind.ObjectMapper;

import java.time.OffsetDateTime;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@ExtendWith(MockitoExtension.class)
class PaymentEventControllerTest {

    @Mock
    private PaymentEventService paymentEventService;

    @Mock
    private ObjectMapper objectMapper;

    @InjectMocks
    private PaymentEventController paymentEventController;

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.standaloneSetup(paymentEventController)
                .setControllerAdvice(new GlobalExceptionHandler())
                .build();
    }

    @Test
    void recordSimulatedEventReturnsCreatedResponse() throws Exception {
        UUID attemptId = UUID.fromString("8074f3ee-5caf-4a15-9e59-1ed2d4c5dbd7");
        PaymentEvent event = createEvent(attemptId);
        when(paymentEventService.recordSimulatedEvent(
                eq(attemptId), any(RecordPaymentEventRequest.class)))
                .thenReturn(new PaymentEventCreationResult(event, true));
        when(objectMapper.readValue(eq("{}"), any(tools.jackson.core.type.TypeReference.class)))
                .thenReturn(Map.of("simulated", true));

        mockMvc.perform(post("/api/payment-attempts/{paymentAttemptId}/events", attemptId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "providerEventId": "evt-1",
                                  "eventType": "PAYMENT.PENDING",
                                  "payload": {"simulated": true}
                                }
                                """))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.paymentAttemptId").value(attemptId.toString()))
                .andExpect(jsonPath("$.provider").value("PAYPAL_SANDBOX"))
                .andExpect(jsonPath("$.providerEventId").value("evt-1"))
                .andExpect(jsonPath("$.eventType").value("PAYMENT.PENDING"))
                .andExpect(jsonPath("$.sourceType").value("SIMULATED"))
                .andExpect(jsonPath("$.verificationStatus").value("NOT_APPLICABLE"))
                .andExpect(jsonPath("$.processingStatus").value("RECEIVED"))
                .andExpect(jsonPath("$.payload.simulated").value(true));

        ArgumentCaptor<RecordPaymentEventRequest> requestCaptor =
                ArgumentCaptor.forClass(RecordPaymentEventRequest.class);
        verify(paymentEventService).recordSimulatedEvent(eq(attemptId), requestCaptor.capture());
        assertEquals("evt-1", requestCaptor.getValue().getProviderEventId());
        assertEquals("PAYMENT.PENDING", requestCaptor.getValue().getEventType());
    }

    @Test
    void duplicateEventReturnsOriginalResponseWithOk() throws Exception {
        UUID attemptId = UUID.randomUUID();
        when(paymentEventService.recordSimulatedEvent(
                eq(attemptId), any(RecordPaymentEventRequest.class)))
                .thenReturn(new PaymentEventCreationResult(createEvent(attemptId), false));
        when(objectMapper.readValue(eq("{}"), any(tools.jackson.core.type.TypeReference.class)))
                .thenReturn(Map.of());

        mockMvc.perform(post("/api/payment-attempts/{paymentAttemptId}/events", attemptId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "providerEventId": "evt-1",
                                  "eventType": "PAYMENT.PENDING",
                                  "payload": {"simulated": true}
                                }
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.providerEventId").value("evt-1"));
    }

    @Test
    void recordEventRejectsMissingEventIdAndPayloadBeforeCallingService() throws Exception {
        UUID attemptId = UUID.randomUUID();
        mockMvc.perform(post("/api/payment-attempts/{paymentAttemptId}/events", attemptId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "eventType": "PAYMENT.PENDING",
                                  "payload": {}
                                }
                                """))
                .andExpect(status().isBadRequest());

        verifyNoInteractions(paymentEventService);
    }

    @Test
    void recordEventReturnsNotFoundForMissingAttempt() throws Exception {
        UUID attemptId = UUID.randomUUID();
        when(paymentEventService.recordSimulatedEvent(
                eq(attemptId), any(RecordPaymentEventRequest.class)))
                .thenThrow(new PaymentAttemptNotFoundException(attemptId));

        mockMvc.perform(post("/api/payment-attempts/{paymentAttemptId}/events", attemptId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "providerEventId": "evt-1",
                                  "eventType": "PAYMENT.PENDING",
                                  "payload": {"simulated": true}
                                }
                                """))
                .andExpect(status().isNotFound())
                .andExpect(content().contentTypeCompatibleWith("application/problem+json"))
                .andExpect(jsonPath("$.title").value("Payment attempt not found"))
                .andExpect(jsonPath("$.status").value(404));
    }

    private PaymentEvent createEvent(UUID attemptId) {
        PaymentAttempt attempt = new PaymentAttempt();
        attempt.setId(attemptId);
        PaymentEvent event = new PaymentEvent();
        event.setId(UUID.fromString("cb376772-1cbe-456a-a1c1-948f87390cdd"));
        event.setPaymentAttempt(attempt);
        event.setProvider("PAYPAL_SANDBOX");
        event.setProviderEventId("evt-1");
        event.setEventType("PAYMENT.PENDING");
        event.setSourceType(PaymentEvent.SourceType.SIMULATED);
        event.setVerificationStatus(PaymentEvent.VerificationStatus.NOT_APPLICABLE);
        event.setProcessingStatus(PaymentEvent.ProcessingStatus.RECEIVED);
        event.setPayload("{}");
        event.setReceivedAt(OffsetDateTime.parse("2026-10-17T10:00:00Z"));
        return event;
    }
}
