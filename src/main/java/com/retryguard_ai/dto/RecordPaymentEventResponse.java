package com.retryguard_ai.dto;

import java.time.OffsetDateTime;
import java.util.Map;
import java.util.UUID;

public record RecordPaymentEventResponse(
        UUID id,
        UUID paymentAttemptId,
        String provider,
        String providerEventId,
        String eventType,
        String sourceType,
        String verificationStatus,
        String processingStatus,
        Map<String, Object> payload,
        OffsetDateTime receivedAt,
        OffsetDateTime processedAt) {
}
