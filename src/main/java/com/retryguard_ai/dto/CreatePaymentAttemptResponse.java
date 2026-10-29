package com.retryguard_ai.dto;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.UUID;

public record CreatePaymentAttemptResponse(
        UUID id,
        UUID orderId,
        String provider,
        BigDecimal amount,
        String currency,
        String paymentStatus,
        String providerPaymentReference,
        OffsetDateTime createdAt) {
}
