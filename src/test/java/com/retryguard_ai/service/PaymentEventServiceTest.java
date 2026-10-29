package com.retryguard_ai.service;

import com.retryguard_ai.dto.RecordPaymentEventRequest;
import com.retryguard_ai.entity.PaymentAttempt;
import com.retryguard_ai.entity.PaymentEvent;
import com.retryguard_ai.exception.InvalidPaymentEventPayloadException;
import com.retryguard_ai.exception.PaymentAttemptNotFoundException;
import com.retryguard_ai.repository.PaymentAttemptRepository;
import com.retryguard_ai.repository.PaymentEventRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import tools.jackson.databind.ObjectMapper;

import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class PaymentEventServiceTest {

    @Mock
    private PaymentAttemptRepository paymentAttemptRepository;

    @Mock
    private PaymentEventRepository paymentEventRepository;

    @Mock
    private ObjectMapper objectMapper;

    @InjectMocks
    private PaymentEventService paymentEventService;

    @Test
    void recordSimulatedEventStoresUnverifiedReceivedEvent() throws Exception {
        UUID attemptId = UUID.randomUUID();
        PaymentAttempt attempt = new PaymentAttempt();
        attempt.setId(attemptId);
        attempt.setProvider("PAYPAL_SANDBOX");
        RecordPaymentEventRequest request = new RecordPaymentEventRequest(
                "evt-1", "PAYMENT.PENDING", Map.of("state", "pending"));
        when(paymentAttemptRepository.findById(attemptId)).thenReturn(Optional.of(attempt));
        when(objectMapper.writeValueAsString(request.getPayload())).thenReturn("{\"state\":\"pending\"}");
        when(paymentEventRepository.findByProviderAndProviderEventId("PAYPAL_SANDBOX", "evt-1"))
                .thenReturn(Optional.empty());
        when(paymentEventRepository.save(any(PaymentEvent.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));

        PaymentEventCreationResult result =
                paymentEventService.recordSimulatedEvent(attemptId, request);

        assertEquals(true, result.created());
        assertSame(attempt, result.event().getPaymentAttempt());
        assertEquals(PaymentEvent.SourceType.SIMULATED, result.event().getSourceType());
        assertEquals(PaymentEvent.VerificationStatus.NOT_APPLICABLE,
                result.event().getVerificationStatus());
        assertEquals(PaymentEvent.ProcessingStatus.RECEIVED,
                result.event().getProcessingStatus());
        assertEquals("{\"state\":\"pending\"}", result.event().getPayload());
        verify(paymentEventRepository).lockIdempotencyKey("PAYPAL_SANDBOX", "evt-1");
        verify(paymentEventRepository).save(result.event());
    }

    @Test
    void duplicateProviderEventIdReturnsOriginalEventWithoutSaving() throws Exception {
        UUID attemptId = UUID.randomUUID();
        PaymentAttempt attempt = new PaymentAttempt();
        attempt.setId(attemptId);
        attempt.setProvider("PAYPAL_SANDBOX");
        PaymentEvent original = new PaymentEvent();
        original.setProviderEventId("evt-1");
        original.setEventType("PAYMENT.PENDING");
        RecordPaymentEventRequest request = new RecordPaymentEventRequest(
                "evt-1", "PAYMENT.SUCCEEDED", Map.of("state", "succeeded"));
        when(paymentAttemptRepository.findById(attemptId)).thenReturn(Optional.of(attempt));
        when(objectMapper.writeValueAsString(request.getPayload()))
                .thenReturn("{\"state\":\"succeeded\"}");
        when(paymentEventRepository.findByProviderAndProviderEventId("PAYPAL_SANDBOX", "evt-1"))
                .thenReturn(Optional.of(original));

        PaymentEventCreationResult result =
                paymentEventService.recordSimulatedEvent(attemptId, request);

        assertEquals(false, result.created());
        assertSame(original, result.event());
        verify(paymentEventRepository, never()).save(any(PaymentEvent.class));
    }

    @Test
    void recordSimulatedEventRejectsMissingAttempt() {
        UUID attemptId = UUID.randomUUID();
        when(paymentAttemptRepository.findById(attemptId)).thenReturn(Optional.empty());

        assertThrows(PaymentAttemptNotFoundException.class,
                () -> paymentEventService.recordSimulatedEvent(attemptId,
                        new RecordPaymentEventRequest("evt-1", "PAYMENT.PENDING",
                                Map.of("state", "pending"))));
        verify(paymentEventRepository, never()).save(any(PaymentEvent.class));
    }

    @Test
    void recordSimulatedEventRejectsPayloadLargerThan64KiB() throws Exception {
        UUID attemptId = UUID.randomUUID();
        PaymentAttempt attempt = new PaymentAttempt();
        attempt.setProvider("PAYPAL_SANDBOX");
        when(paymentAttemptRepository.findById(attemptId)).thenReturn(Optional.of(attempt));
        when(objectMapper.writeValueAsString(any())).thenReturn("x".repeat(65_537));

        assertThrows(InvalidPaymentEventPayloadException.class,
                () -> paymentEventService.recordSimulatedEvent(attemptId,
                        new RecordPaymentEventRequest("evt-1", "PAYMENT.PENDING",
                                Map.of("state", "pending"))));
        verify(paymentEventRepository, never()).lockIdempotencyKey(any(), any());
        verify(paymentEventRepository, never()).save(any(PaymentEvent.class));
    }
}
