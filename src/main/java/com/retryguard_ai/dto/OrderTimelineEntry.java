package com.retryguard_ai.dto;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.UUID;

public record OrderTimelineEntry(
        UUID id,
        String entryType,
        OffsetDateTime occurredAt,
        String provider,
        String currentStatus,
        String eventType,
        String sourceType,
        String verificationStatus,
        String processingStatus,
        String providerEventId,
        BigDecimal amount,
        String currency) {
}
