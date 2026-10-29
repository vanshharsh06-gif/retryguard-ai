package com.retryguard_ai.ai;

import java.util.List;

public record ExplanationInput(
        String decision,
        String reason,
        String rulesVersion,
        String orderStatus,
        long freshnessThresholdMinutes,
        List<Attempt> attempts,
        List<Finding> findings) {

    public ExplanationInput(
            String decision,
            String reason,
            String rulesVersion,
            String orderStatus,
            long freshnessThresholdMinutes,
            List<Attempt> attempts) {
        this(decision, reason, rulesVersion, orderStatus, freshnessThresholdMinutes,
                attempts, List.of());
    }

    public record Attempt(String provider, String paymentStatus, List<Event> events) {
    }

    public record Event(
            String eventType,
            String provider,
            String sourceType,
            String verificationStatus,
            String timestampSource,
            String freshness,
            boolean trusted) {
    }

    public record Finding(
            String code,
            String category,
            String severity,
            boolean rootCauseUncertain,
            List<String> evidenceTypes) {
    }
}
