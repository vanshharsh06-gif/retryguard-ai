package com.retryguard_ai.dto;

import java.time.OffsetDateTime;
import java.util.UUID;

public record AgentWorkflowListResponse(
        UUID id,
        UUID orderId,
        String status,
        String summary,
        OffsetDateTime startedAt,
        OffsetDateTime completedAt,
        int toolCallsUsed
) {}