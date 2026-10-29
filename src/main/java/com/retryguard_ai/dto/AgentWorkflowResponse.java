package com.retryguard_ai.dto;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

public record AgentWorkflowResponse(
        UUID id,
        UUID orderId,
        String status,
        String summary,
        int toolCallsUsed,
        int modelTurnsUsed,
        OffsetDateTime startedAt,
        OffsetDateTime completedAt,
        List<AgentWorkflowStepResponse> steps
) {}