package com.retryguard_ai.controller;

import com.retryguard_ai.dto.RecordPaymentEventRequest;
import com.retryguard_ai.dto.RecordPaymentEventResponse;
import com.retryguard_ai.entity.PaymentEvent;
import com.retryguard_ai.service.PaymentEventCreationResult;
import com.retryguard_ai.service.PaymentEventService;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

import java.util.Map;
import java.util.UUID;

@RestController
@RequestMapping("/api/payment-attempts/{paymentAttemptId}/events")
public class PaymentEventController {

    private final PaymentEventService paymentEventService;
    private final ObjectMapper objectMapper;

    public PaymentEventController(
            PaymentEventService paymentEventService, ObjectMapper objectMapper) {
        this.paymentEventService = paymentEventService;
        this.objectMapper = objectMapper;
    }

    @PostMapping
    public ResponseEntity<RecordPaymentEventResponse> recordSimulatedEvent(
            @PathVariable UUID paymentAttemptId,
            @Valid @RequestBody RecordPaymentEventRequest request) {
        PaymentEventCreationResult result =
                paymentEventService.recordSimulatedEvent(paymentAttemptId, request);
        PaymentEvent event = result.event();
        Map<String, Object> payload = objectMapper.readValue(
                event.getPayload(), new TypeReference<>() { });
        RecordPaymentEventResponse response = new RecordPaymentEventResponse(
                event.getId(),
                event.getPaymentAttempt().getId(),
                event.getProvider(),
                event.getProviderEventId(),
                event.getEventType(),
                event.getSourceType().name(),
                event.getVerificationStatus().name(),
                event.getProcessingStatus().name(),
                payload,
                event.getReceivedAt(),
                event.getProcessedAt());

        HttpStatus status = result.created() ? HttpStatus.CREATED : HttpStatus.OK;
        return ResponseEntity.status(status).body(response);
    }
}
