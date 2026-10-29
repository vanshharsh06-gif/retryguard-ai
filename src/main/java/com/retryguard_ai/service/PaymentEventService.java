package com.retryguard_ai.service;

import com.retryguard_ai.dto.RecordPaymentEventRequest;
import com.retryguard_ai.entity.PaymentAttempt;
import com.retryguard_ai.entity.PaymentEvent;
import com.retryguard_ai.exception.InvalidPaymentEventPayloadException;
import com.retryguard_ai.exception.PaymentAttemptNotFoundException;
import com.retryguard_ai.repository.PaymentAttemptRepository;
import com.retryguard_ai.repository.PaymentEventRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;

import java.nio.charset.StandardCharsets;
import java.util.UUID;

@Service
public class PaymentEventService {

    private static final int MAX_PAYLOAD_BYTES = 64 * 1024;

    private final PaymentAttemptRepository paymentAttemptRepository;
    private final PaymentEventRepository paymentEventRepository;
    private final ObjectMapper objectMapper;

    public PaymentEventService(
            PaymentAttemptRepository paymentAttemptRepository,
            PaymentEventRepository paymentEventRepository,
            ObjectMapper objectMapper) {
        this.paymentAttemptRepository = paymentAttemptRepository;
        this.paymentEventRepository = paymentEventRepository;
        this.objectMapper = objectMapper;
    }

    @Transactional
    public PaymentEventCreationResult recordSimulatedEvent(
            UUID paymentAttemptId, RecordPaymentEventRequest request) {
        PaymentAttempt attempt = paymentAttemptRepository.findById(paymentAttemptId)
                .orElseThrow(() -> new PaymentAttemptNotFoundException(paymentAttemptId));

        String provider = attempt.getProvider();
        String payload = serializePayload(request);
        paymentEventRepository.lockIdempotencyKey(provider, request.getProviderEventId());
        var existingEvent = paymentEventRepository
                .findByProviderAndProviderEventId(provider, request.getProviderEventId());
        if (existingEvent.isPresent()) {
            return new PaymentEventCreationResult(existingEvent.get(), false);
        }

        PaymentEvent event = new PaymentEvent();
        event.setPaymentAttempt(attempt);
        event.setProvider(provider);
        event.setProviderEventId(request.getProviderEventId());
        event.setEventType(request.getEventType());
        event.setSourceType(PaymentEvent.SourceType.SIMULATED);
        event.setVerificationStatus(PaymentEvent.VerificationStatus.NOT_APPLICABLE);
        event.setProcessingStatus(PaymentEvent.ProcessingStatus.RECEIVED);
        event.setPayload(payload);

        return new PaymentEventCreationResult(paymentEventRepository.save(event), true);
    }

    private String serializePayload(RecordPaymentEventRequest request) {
        try {
            String payload = objectMapper.writeValueAsString(request.getPayload());
            if (payload.getBytes(StandardCharsets.UTF_8).length > MAX_PAYLOAD_BYTES) {
                throw new InvalidPaymentEventPayloadException(
                        "Event payload must not exceed 65536 bytes.");
            }
            return payload;
        } catch (JacksonException exception) {
            throw new InvalidPaymentEventPayloadException(
                    "Event payload could not be serialized.", exception);
        }
    }
}
