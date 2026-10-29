package com.retryguard_ai.dto;

import java.time.OffsetDateTime;
import java.util.UUID;

public record InvestigationHistoryResponse(
        UUID id,
        UUID investigationId,
        String action,
        String previousDecision,
        String newDecision,
        String reason,
        OffsetDateTime createdAt) {
}
