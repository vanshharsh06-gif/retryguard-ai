package com.retryguard_ai.dto;

import java.util.List;
import java.util.UUID;

public record AutopsyFinding(
        Code code,
        String title,
        String description,
        RootCauseCategory rootCauseCategory,
        Severity severity,
        List<EvidenceReference> evidence,
        String recommendedAction,
        boolean rootCauseUncertain) {

    public enum Code {
        PROVIDER_DENIAL,
        PENDING_PAYMENT,
        CONFLICTING_OUTCOMES,
        STALE_EVIDENCE,
        UNVERIFIED_EVIDENCE,
        MISSING_EVIDENCE,
        PAYMENT_CONFIRMED
    }

    public enum RootCauseCategory {
        PROVIDER_DENIAL,
        PENDING_PAYMENT,
        CONFLICTING_OUTCOMES,
        STALE_EVIDENCE,
        UNVERIFIED_EVIDENCE,
        MISSING_EVIDENCE,
        CONFIRMED_PAYMENT
    }

    public enum Severity {
        INFO,
        LOW,
        MEDIUM,
        HIGH
    }

    public enum EvidenceType {
        ORDER,
        PAYMENT_ATTEMPT,
        PAYMENT_EVENT
    }

    public record EvidenceReference(EvidenceType type, UUID id) {
    }
}
