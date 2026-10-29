package com.retryguard_ai.service;

import com.retryguard_ai.ai.ExplanationInput;
import com.retryguard_ai.ai.ExplanationProvider;
import com.retryguard_ai.ai.ExplanationProviderException;
import com.retryguard_ai.dto.AutopsyFinding;
import com.retryguard_ai.dto.InvestigationExplanationResponse;
import com.retryguard_ai.dto.InvestigationExplanationResponse.Source;
import com.retryguard_ai.dto.InvestigationResponse.EvidenceSnapshot;
import com.retryguard_ai.entity.Investigation;
import com.retryguard_ai.exception.InvestigationNotFoundException;
import com.retryguard_ai.repository.InvestigationRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.core.JacksonException;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

import java.util.List;
import java.util.Objects;
import java.util.regex.Pattern;

@Service
public class InvestigationExplanationService {

    private static final int MAX_EXPLANATION_LENGTH = 1200;
    private static final int MAX_ATTEMPTS = 25;
    private static final int MAX_EVENTS = 50;
    private static final Pattern ACTION_INSTRUCTION = Pattern.compile(
            "(?i)\\b(?:you should|you can|please|go ahead and|proceed to)\\s+"
                    + "(?:retry|charge|capture|refund)\\b"
                    + "|\\b(?:proceed with|start|initiate|execute|perform|recommend(?:s|ed)?)\\s+"
                    + "(?:a\\s+)?(?:retry|charge|capture|refund|payment action)\\b"
                    + "|\\b(?:retry|charge|capture|refund)\\s+(?:the|this|another)\\s+"
                    + "(?:payment|transaction|order)\\b");

    private final InvestigationRepository investigationRepository;
    private final ExplanationProvider explanationProvider;
    private final InvestigationExplanationPersistenceService persistenceService;
    private final ObjectMapper objectMapper;

    public InvestigationExplanationService(
            InvestigationRepository investigationRepository,
            ExplanationProvider explanationProvider,
            InvestigationExplanationPersistenceService persistenceService,
            ObjectMapper objectMapper) {
        this.investigationRepository = investigationRepository;
        this.explanationProvider = explanationProvider;
        this.persistenceService = persistenceService;
        this.objectMapper = objectMapper;
    }

    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    public InvestigationExplanationResponse explain(java.util.UUID investigationId) {
        Investigation investigation = investigationRepository.findById(investigationId)
                .orElseThrow(() -> new InvestigationNotFoundException(investigationId));
        InvestigationExplanationResponse.Source source = Source.GEMINI;
        String explanation;
        try {
            ExplanationInput input = createInput(investigation);
            explanation = validate(explanationProvider.explain(input));
        } catch (ExplanationProviderException | InvalidEvidenceSnapshotException
                 | InvalidGeneratedExplanationException exception) {
            explanation = fallback(investigation);
            source = Source.DETERMINISTIC_FALLBACK;
        }
        persistenceService.saveExplanation(investigationId, explanation);
        return new InvestigationExplanationResponse(
                investigationId,
                investigation.getDecision().name(),
                investigation.getDecisionReason(),
                investigation.getRulesVersion(),
                explanation,
                source);
    }

    private ExplanationInput createInput(Investigation investigation) {
        EvidenceSnapshot snapshot;
        try {
            snapshot = objectMapper.readValue(
                    investigation.getEvidenceSnapshot(), EvidenceSnapshot.class);
        } catch (JacksonException exception) {
            throw new InvalidEvidenceSnapshotException();
        }
        if (snapshot == null || snapshot.attempts() == null
                || snapshot.attempts().size() > MAX_ATTEMPTS
                || snapshot.attempts().stream().anyMatch(Objects::isNull)) {
            throw new InvalidEvidenceSnapshotException();
        }
        if (snapshot.attempts().stream().anyMatch(attempt -> attempt.events() != null
                && attempt.events().stream().anyMatch(Objects::isNull))) {
            throw new InvalidEvidenceSnapshotException();
        }
        long eventCount = snapshot.attempts().stream()
                .mapToLong(attempt -> attempt.events() == null ? 0 : attempt.events().size())
                .sum();
        if (eventCount > MAX_EVENTS) {
            throw new InvalidEvidenceSnapshotException();
        }
        List<AutopsyFinding> findings;
        try {
            findings = objectMapper.readValue(
                    investigation.getFindings(),
                    new TypeReference<>() { });
        } catch (JacksonException exception) {
            throw new InvalidEvidenceSnapshotException();
        }
        if (findings == null || findings.size() > 10
                || findings.stream().anyMatch(Objects::isNull)) {
            throw new InvalidEvidenceSnapshotException();
        }
        List<ExplanationInput.Attempt> attempts = snapshot.attempts().stream()
                .map(attempt -> new ExplanationInput.Attempt(
                        safeProvider(attempt.provider()),
                        safePaymentStatus(attempt.paymentStatus()),
                        attempt.events() == null ? List.of() : attempt.events().stream()
                                .map(event -> new ExplanationInput.Event(
                                        safeEventType(event.eventType()),
                                        safeProvider(event.provider()),
                                        safeSource(event.sourceType()),
                                        safeVerification(event.verificationStatus()),
                                        safeTimestampSource(event.timestampSource()),
                                        safeFreshness(event.freshness()),
                                        event.trustedPayPalEvidence()))
                                .toList()))
                .toList();
        return new ExplanationInput(
                investigation.getDecision().name(),
                safeReason(investigation.getDecisionReason()),
                safeRulesVersion(investigation.getRulesVersion()),
                safeOrderStatus(snapshot.orderStatus()),
                snapshot.freshnessThresholdMinutes(),
                attempts,
                findings.stream()
                        .map(finding -> new ExplanationInput.Finding(
                                safeFindingCode(finding.code() == null
                                        ? null : finding.code().name()),
                                finding.rootCauseCategory() == null
                                        ? "OTHER" : finding.rootCauseCategory().name(),
                                finding.severity() == null ? "OTHER" : finding.severity().name(),
                                finding.rootCauseUncertain(),
                                finding.evidence() == null ? List.of() : finding.evidence().stream()
                                        .filter(Objects::nonNull)
                                        .map(reference -> reference.type() == null
                                                ? "OTHER" : reference.type().name())
                                        .distinct()
                                        .toList()))
                        .toList());
    }

    private String validate(String candidate) {
        if (candidate == null) {
            throw new InvalidGeneratedExplanationException();
        }
        String text = candidate.strip();
        if (text.isBlank() || text.length() > MAX_EXPLANATION_LENGTH
                || ACTION_INSTRUCTION.matcher(text).find()) {
            throw new InvalidGeneratedExplanationException();
        }
        return text;
    }

    private String fallback(Investigation investigation) {
        return "The deterministic investigation returned "
                + investigation.getDecision().name() + " ("
                + safeReason(investigation.getDecisionReason()) + ") under rules version "
                + safeRulesVersion(investigation.getRulesVersion())
                + ". This explanation does not authorize a payment action.";
    }

    private String safeProvider(String value) {
        return "PAYPAL_SANDBOX".equals(value) ? value : "OTHER";
    }

    private String safePaymentStatus(String value) {
        return oneOf(value, "CREATED", "PENDING", "SUCCEEDED", "FAILED", "CANCELLED", "UNKNOWN");
    }

    private String safeEventType(String value) {
        return oneOf(value, "PAYMENT.CAPTURE.DENIED", "PAYMENT.CAPTURE.COMPLETED",
                "PAYMENT.CAPTURE.PENDING", "PAYMENT.CAPTURE.RESPONSE");
    }

    private String safeSource(String value) {
        return oneOf(value, "PROVIDER", "SIMULATED");
    }

    private String safeVerification(String value) {
        return oneOf(value, "VERIFIED", "NOT_APPLICABLE", "UNVERIFIED");
    }

    private String safeTimestampSource(String value) {
        return oneOf(value, "PROVIDER_CREATE_TIME", "RECEIVED_AT_FALLBACK", "UNAVAILABLE");
    }

    private String safeFreshness(String value) {
        return oneOf(value, "FRESH", "STALE_OR_UNKNOWN", "NOT_APPLICABLE");
    }

    private String safeOrderStatus(String value) {
        return oneOf(value, "CREATED", "PENDING", "PAID", "FAILED", "CANCELLED");
    }

    private String safeReason(String value) {
        if (value != null && value.matches("[A-Z0-9_]{1,60}")) {
            return value;
        }
        return "UNRECOGNIZED_REASON";
    }

    private String safeFindingCode(String value) {
        return oneOf(value, "PROVIDER_DENIAL", "PENDING_PAYMENT", "CONFLICTING_OUTCOMES",
                "STALE_EVIDENCE", "UNVERIFIED_EVIDENCE", "MISSING_EVIDENCE",
                "PAYMENT_CONFIRMED");
    }

    private String safeRulesVersion(String value) {
        if (value != null && value.matches("[A-Za-z0-9._-]{1,30}")) {
            return value;
        }
        return "UNRECOGNIZED";
    }

    private String oneOf(String value, String... allowed) {
        if (value != null) {
            for (String candidate : allowed) {
                if (candidate.equals(value)) {
                    return value;
                }
            }
        }
        return "OTHER";
    }

    private static class InvalidEvidenceSnapshotException extends RuntimeException {
    }

    private static class InvalidGeneratedExplanationException extends RuntimeException {
    }
}
