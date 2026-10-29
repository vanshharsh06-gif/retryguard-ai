package com.retryguard_ai.dto;

import java.util.List;
import java.util.UUID;

public record OrderTimelineResponse(
        UUID orderId,
        String merchantOrderReference,
        String currentOrderStatus,
        List<OrderTimelineEntry> entries) {
}
