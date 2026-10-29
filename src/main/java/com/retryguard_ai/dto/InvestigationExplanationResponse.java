package com.retryguard_ai.dto;

import java.util.UUID;

public record InvestigationExplanationResponse(
        UUID investigationId,
        String decision,
        String reason,
        String rulesVersion,
        String explanation,
        Source source) {

    public enum Source {
        GEMINI,
        DETERMINISTIC_FALLBACK
    }
}
