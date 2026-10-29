package com.retryguard_ai.dto;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

public record InvestigationResponse(
        UUID id,
        UUID orderId,
        String decision,
        String reason,
        String rulesVersion,
        String explanation,
        EvidenceSnapshot evidence,
        List<AutopsyFinding> findings,
        OffsetDateTime createdAt) {

    public InvestigationResponse(
            UUID id,
            UUID orderId,
            String decision,
            String reason,
            String rulesVersion,
            String explanation,
            EvidenceSnapshot evidence,
            OffsetDateTime createdAt) {
        this(id, orderId, decision, reason, rulesVersion, explanation, evidence, List.of(), createdAt);
    }

    public record EvidenceSnapshot(
            String orderStatus,
            OffsetDateTime investigatedAt,
            long freshnessThresholdMinutes,
            List<AttemptEvidence> attempts) {
    }

    public record AttemptEvidence(
            UUID paymentAttemptId,
            String provider,
            String paymentStatus,
            List<EventEvidence> events) {
    }

    public record EventEvidence(
            UUID eventId,
            String eventType,
            String provider,
            String sourceType,
            String verificationStatus,
            OffsetDateTime receivedAt,
            OffsetDateTime providerEventAt,
            OffsetDateTime effectiveEvidenceAt,
            String timestampSource,
            String freshness,
            boolean trustedPayPalEvidence) {
    }
}
