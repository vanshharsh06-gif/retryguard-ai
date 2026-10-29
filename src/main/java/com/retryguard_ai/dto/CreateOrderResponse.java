package com.retryguard_ai.dto;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.UUID;

public record CreateOrderResponse(
        UUID id,
        String merchantOrderReference,
        BigDecimal amount,
        String currency,
        String orderStatus,
        OffsetDateTime createdAt) {
}
