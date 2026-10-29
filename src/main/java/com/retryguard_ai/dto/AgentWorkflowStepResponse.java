package com.retryguard_ai.dto;

import java.time.OffsetDateTime;
import java.util.UUID;

public record AgentWorkflowStepResponse(
        UUID id,
        UUID workflowId,
        String stepType,
        String stepName,
        String inputSummary,
        String outputSummary,
        String error,
        OffsetDateTime createdAt
) {}