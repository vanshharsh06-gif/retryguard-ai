package com.retryguard_ai.service;

import com.retryguard_ai.dto.AutopsyFinding;
import com.retryguard_ai.dto.AutopsyFinding.EvidenceReference;
import com.retryguard_ai.dto.AutopsyFinding.EvidenceType;
import com.retryguard_ai.dto.AutopsyFinding.Code;
import com.retryguard_ai.dto.AutopsyFinding.RootCauseCategory;
import com.retryguard_ai.dto.AutopsyFinding.Severity;
import com.retryguard_ai.dto.InvestigationResponse.AttemptEvidence;
import com.retryguard_ai.dto.InvestigationResponse.EventEvidence;
import com.retryguard_ai.dto.InvestigationResponse.EvidenceSnapshot;
import com.retryguard_ai.dto.InvestigationHistoryResponse;
import com.retryguard_ai.dto.InvestigationResponse;
import com.retryguard_ai.entity.Investigation;
import com.retryguard_ai.entity.InvestigationHistory;
import com.retryguard_ai.entity.MerchantOrder;
import com.retryguard_ai.entity.PaymentAttempt;
import com.retryguard_ai.entity.PaymentEvent;
import com.retryguard_ai.exception.InvestigationNotFoundException;
import com.retryguard_ai.exception.MerchantOrderNotFoundException;
import com.retryguard_ai.repository.InvestigationHistoryRepository;
import com.retryguard_ai.repository.InvestigationRepository;
import com.retryguard_ai.repository.MerchantOrderRepository;
import com.retryguard_ai.repository.PaymentAttemptRepository;
import com.retryguard_ai.repository.PaymentEventRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.core.JacksonException;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.time.Duration;
import java.time.OffsetDateTime;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;

@Service
public class InvestigationService {

    public static final String RULES_VERSION = "1.0";
    public static final long FRESHNESS_THRESHOLD_MINUTES = 15;
    private static final String PAYPAL_PROVIDER = "PAYPAL_SANDBOX";
    private static final String DENIED_EVENT = "PAYMENT.CAPTURE.DENIED";
    private static final String COMPLETED_EVENT = "PAYMENT.CAPTURE.COMPLETED";
    private static final String PENDING_EVENT = "PAYMENT.CAPTURE.PENDING";

    private final MerchantOrderRepository merchantOrderRepository;
    private final PaymentAttemptRepository paymentAttemptRepository;
    private final PaymentEventRepository paymentEventRepository;
    private final InvestigationRepository investigationRepository;
    private final InvestigationHistoryRepository investigationHistoryRepository;
    private final ObjectMapper objectMapper;

    public InvestigationService(
            MerchantOrderRepository merchantOrderRepository,
            PaymentAttemptRepository paymentAttemptRepository,
            PaymentEventRepository paymentEventRepository,
            InvestigationRepository investigationRepository,
            InvestigationHistoryRepository investigationHistoryRepository,
            ObjectMapper objectMapper) {
        this.merchantOrderRepository = merchantOrderRepository;
        this.paymentAttemptRepository = paymentAttemptRepository;
        this.paymentEventRepository = paymentEventRepository;
        this.investigationRepository = investigationRepository;
        this.investigationHistoryRepository = investigationHistoryRepository;
        this.objectMapper = objectMapper;
    }

    @Transactional(isolation = Isolation.REPEATABLE_READ)
    public InvestigationResponse investigate(UUID orderId) {
        MerchantOrder order = merchantOrderRepository.findById(orderId)
                .orElseThrow(() -> new MerchantOrderNotFoundException(orderId));
        OffsetDateTime now = OffsetDateTime.now();
        List<PaymentAttempt> attempts = paymentAttemptRepository.findByOrder_Id(orderId).stream()
                .sorted(Comparator.comparing(PaymentAttempt::getId))
                .toList();
        List<UUID> attemptIds = attempts.stream().map(PaymentAttempt::getId).toList();
        List<PaymentEvent> events = attemptIds.isEmpty()
                ? List.of()
                : paymentEventRepository.findByPaymentAttempt_IdInOrderByReceivedAtAsc(attemptIds);
        List<AttemptEvidence> attemptEvidence = buildEvidence(attempts, events, now);

        DecisionResult result = decide(order, attempts, attemptEvidence);
        List<AutopsyFinding> findings = buildFindings(order, attempts, attemptEvidence, result);
        Investigation previous = investigationRepository
                .findFirstByOrder_IdOrderByCreatedAtDescIdDesc(orderId).orElse(null);

        Investigation investigation = new Investigation();
        investigation.setOrder(order);
        investigation.setDecision(result.decision());
        investigation.setDecisionReason(result.reason());
        investigation.setRulesVersion(RULES_VERSION);
        investigation.setExplanation(result.explanation());
        investigation.setCreatedAt(now);
        EvidenceSnapshot snapshot = new EvidenceSnapshot(
                order.getOrderStatus().name(),
                now,
                FRESHNESS_THRESHOLD_MINUTES,
                List.copyOf(attemptEvidence));
        investigation.setEvidenceSnapshot(objectMapper.writeValueAsString(snapshot));
        investigation.setFindings(objectMapper.writeValueAsString(findings));
        Investigation saved = investigationRepository.save(investigation);

        InvestigationHistory history = new InvestigationHistory();
        history.setInvestigation(saved);
        history.setAction("CREATED");
        history.setPreviousDecision(previous == null ? null : previous.getDecision());
        history.setNewDecision(result.decision());
        history.setReason(result.reason());
        history.setCreatedAt(now);
        investigationHistoryRepository.save(history);

        return toResponse(saved);
    }

    @Transactional(readOnly = true)
    public InvestigationResponse getInvestigation(UUID investigationId) {
        return investigationRepository.findById(investigationId)
                .map(this::toResponse)
                .orElseThrow(() -> new InvestigationNotFoundException(investigationId));
    }

    @Transactional(readOnly = true)
    public List<InvestigationResponse> getInvestigationsForOrder(UUID orderId) {
        if (!merchantOrderRepository.existsById(orderId)) {
            throw new MerchantOrderNotFoundException(orderId);
        }
        return investigationRepository
                .findTop50ByOrder_IdOrderByCreatedAtDescIdDesc(orderId)
                .stream()
                .map(this::toResponse)
                .toList();
    }

    @Transactional(readOnly = true)
    public List<InvestigationHistoryResponse> getInvestigationHistory(UUID investigationId) {
        if (!investigationRepository.existsById(investigationId)) {
            throw new InvestigationNotFoundException(investigationId);
        }
        return investigationHistoryRepository
                .findByInvestigation_IdOrderByCreatedAtAscIdAsc(investigationId)
                .stream()
                .map(history -> new InvestigationHistoryResponse(
                        history.getId(),
                        investigationId,
                        history.getAction(),
                        history.getPreviousDecision() == null
                                ? null : history.getPreviousDecision().name(),
                        history.getNewDecision() == null ? null : history.getNewDecision().name(),
                        history.getReason(),
                        history.getCreatedAt()))
                .toList();
    }

    private InvestigationResponse toResponse(Investigation investigation) {
        try {
            EvidenceSnapshot snapshot = objectMapper.readValue(
                    investigation.getEvidenceSnapshot(), EvidenceSnapshot.class);
            List<AutopsyFinding> findings = objectMapper.readValue(
                    investigation.getFindings(), new TypeReference<>() { });
            return new InvestigationResponse(
                    investigation.getId(),
                    investigation.getOrder().getId(),
                    investigation.getDecision().name(),
                    investigation.getDecisionReason(),
                    investigation.getRulesVersion(),
                    investigation.getExplanation(),
                    snapshot,
                    findings,
                    investigation.getCreatedAt());
        } catch (JacksonException exception) {
            throw new IllegalStateException("Stored investigation data is invalid.", exception);
        }
    }

    private List<AutopsyFinding> buildFindings(
            MerchantOrder order,
            List<PaymentAttempt> attempts,
            List<AttemptEvidence> evidence,
            DecisionResult decision) {
        List<AutopsyFinding> findings = new ArrayList<>();
        List<EvidenceReference> denialEvidence = evidence.stream()
                .flatMap(attempt -> attempt.events().stream()
                        .filter(event -> event.trustedPayPalEvidence()
                                && DENIED_EVENT.equals(event.eventType()))
                        .map(event -> new EvidenceReference(EvidenceType.PAYMENT_EVENT, event.eventId())))
                .toList();
        if (!denialEvidence.isEmpty()) {
            findings.add(finding(Code.PROVIDER_DENIAL, "Verified provider denial",
                    "PayPal reported a capture denial for this attempt. The event confirms the outcome but does not establish the underlying cause.",
                    RootCauseCategory.PROVIDER_DENIAL, Severity.HIGH, denialEvidence,
                    "Review the verified provider event and merchant records before deciding on any next step.", true));
        }

        List<EvidenceReference> pendingEvidence = new ArrayList<>();
        for (int index = 0; index < attempts.size(); index++) {
            PaymentAttempt attempt = attempts.get(index);
            if (attempt.getPaymentStatus() == PaymentAttempt.PaymentStatus.PENDING) {
                pendingEvidence.add(new EvidenceReference(EvidenceType.PAYMENT_ATTEMPT, attempt.getId()));
            }
            evidence.get(index).events().stream()
                    .filter(event -> event.trustedPayPalEvidence()
                            && PENDING_EVENT.equals(event.eventType()))
                    .map(event -> new EvidenceReference(EvidenceType.PAYMENT_EVENT, event.eventId()))
                    .forEach(pendingEvidence::add);
        }
        if (!pendingEvidence.isEmpty()) {
            findings.add(finding(Code.PENDING_PAYMENT, "Payment outcome pending",
                    "The persisted attempt or verified provider event is pending; this evidence does not establish why completion is delayed.",
                    RootCauseCategory.PENDING_PAYMENT, Severity.MEDIUM, pendingEvidence,
                    "Wait for a verified terminal provider outcome and review the order again; do not infer failure from a pending state.", true));
        }

        if (decision.decision() == Investigation.Decision.RECONCILE) {
            List<EvidenceReference> conflictEvidence = new ArrayList<>();
            conflictEvidence.add(new EvidenceReference(EvidenceType.ORDER, order.getId()));
            for (int index = 0; index < attempts.size(); index++) {
                PaymentAttempt attempt = attempts.get(index);
                conflictEvidence.add(new EvidenceReference(EvidenceType.PAYMENT_ATTEMPT, attempt.getId()));
                evidence.get(index).events().stream()
                        .filter(EventEvidence::trustedPayPalEvidence)
                        .filter(event -> DENIED_EVENT.equals(event.eventType())
                                || COMPLETED_EVENT.equals(event.eventType()))
                        .map(event -> new EvidenceReference(EvidenceType.PAYMENT_EVENT, event.eventId()))
                        .forEach(conflictEvidence::add);
            }
            findings.add(finding(Code.CONFLICTING_OUTCOMES, "Payment records need reconciliation",
                    "Verified payment evidence conflicts with another verified outcome or the persisted merchant/payment status.",
                    RootCauseCategory.CONFLICTING_OUTCOMES, Severity.HIGH, conflictEvidence,
                    "Reconcile the provider and merchant records before taking any payment action.", true));
        }

        List<EvidenceReference> staleEvidence = evidence.stream()
                .flatMap(attempt -> attempt.events().stream()
                        .filter(event -> event.trustedPayPalEvidence()
                                && "STALE_OR_UNKNOWN".equals(event.freshness()))
                        .map(event -> new EvidenceReference(EvidenceType.PAYMENT_EVENT, event.eventId())))
                .toList();
        if (!staleEvidence.isEmpty()) {
            findings.add(finding(Code.STALE_EVIDENCE, "Provider evidence is stale or undated",
                    "Verified provider evidence is older than the freshness window, malformed, or has a future timestamp.",
                    RootCauseCategory.STALE_EVIDENCE, Severity.MEDIUM, staleEvidence,
                    "Obtain or wait for current verified provider evidence before evaluating recovery.", true));
        }

        List<EvidenceReference> unverifiedEvidence = evidence.stream()
                .flatMap(attempt -> attempt.events().stream()
                        .filter(event -> !event.trustedPayPalEvidence())
                        .map(event -> new EvidenceReference(EvidenceType.PAYMENT_EVENT, event.eventId())))
                .toList();
        if (!unverifiedEvidence.isEmpty()) {
            findings.add(finding(Code.UNVERIFIED_EVIDENCE, "Event is not trusted provider evidence",
                    "One or more stored events are simulated, unverified, or from a provider/source not accepted by the deterministic rules.",
                    RootCauseCategory.UNVERIFIED_EVIDENCE, Severity.MEDIUM, unverifiedEvidence,
                    "Use the verified provider webhook flow and do not treat these events as proof of payment outcome.", true));
        }

        List<EvidenceReference> missingEvidence = new ArrayList<>();
        if (attempts.isEmpty()) {
            missingEvidence.add(new EvidenceReference(EvidenceType.ORDER, order.getId()));
        } else {
            for (int index = 0; index < attempts.size(); index++) {
                boolean hasTerminalOutcome = evidence.get(index).events().stream()
                        .anyMatch(event -> event.trustedPayPalEvidence()
                                && (DENIED_EVENT.equals(event.eventType())
                                || COMPLETED_EVENT.equals(event.eventType())));
                if (!hasTerminalOutcome) {
                    missingEvidence.add(new EvidenceReference(
                            EvidenceType.PAYMENT_ATTEMPT, attempts.get(index).getId()));
                }
            }
        }
        if (!missingEvidence.isEmpty()) {
            findings.add(finding(Code.MISSING_EVIDENCE, "Payment evidence is missing",
                    attempts.isEmpty()
                            ? "No payment attempts are recorded for this order."
                            : "One or more payment attempts have no verified terminal provider outcome.",
                    RootCauseCategory.MISSING_EVIDENCE, Severity.MEDIUM, missingEvidence,
                    "Review the attempt with the provider or record the verified event when available; missing evidence is not proof of failure.", true));
        }

        List<EvidenceReference> confirmedEvidence = new java.util.ArrayList<>();
        if (order.getOrderStatus() == MerchantOrder.OrderStatus.PAID) {
            confirmedEvidence.add(new EvidenceReference(EvidenceType.ORDER, order.getId()));
        }
        for (int index = 0; index < attempts.size(); index++) {
            PaymentAttempt attempt = attempts.get(index);
            if (attempt.getPaymentStatus() == PaymentAttempt.PaymentStatus.SUCCEEDED) {
                confirmedEvidence.add(new EvidenceReference(EvidenceType.PAYMENT_ATTEMPT, attempt.getId()));
            }
            evidence.get(index).events().stream()
                    .filter(event -> event.trustedPayPalEvidence()
                            && COMPLETED_EVENT.equals(event.eventType()))
                    .map(event -> new EvidenceReference(EvidenceType.PAYMENT_EVENT, event.eventId()))
                    .forEach(confirmedEvidence::add);
        }
        if (!confirmedEvidence.isEmpty()) {
            findings.add(finding(Code.PAYMENT_CONFIRMED, "Payment completion is recorded",
                    "The merchant order, attempt, or verified provider event records a completed payment outcome.",
                    RootCauseCategory.CONFIRMED_PAYMENT, Severity.INFO, confirmedEvidence,
                    "Verify the merchant's fulfillment and accounting records; do not create another charge.", true));
        }
        return List.copyOf(findings);
    }

    private AutopsyFinding finding(
            Code code,
            String title,
            String description,
            RootCauseCategory category,
            Severity severity,
            List<EvidenceReference> evidence,
            String action,
            boolean uncertain) {
        return new AutopsyFinding(
                code, title, description, category, severity, List.copyOf(evidence), action, uncertain);
    }

    private List<AttemptEvidence> buildEvidence(
            List<PaymentAttempt> attempts, List<PaymentEvent> events, OffsetDateTime now) {
        return attempts.stream().map(attempt -> {
            List<EventEvidence> eventEvidence = events.stream()
                    .filter(event -> event.getPaymentAttempt() != null
                            && attempt.getId().equals(event.getPaymentAttempt().getId()))
                    .sorted(Comparator
                            .comparing(PaymentEvent::getReceivedAt,
                                    Comparator.nullsLast(Comparator.naturalOrder()))
                            .thenComparing(PaymentEvent::getId))
                    .map(event -> eventEvidence(event, now))
                    .toList();
            return new AttemptEvidence(
                    attempt.getId(),
                    attempt.getProvider(),
                    attempt.getPaymentStatus() == null ? null : attempt.getPaymentStatus().name(),
                    eventEvidence);
        }).toList();
    }

    private EventEvidence eventEvidence(PaymentEvent event, OffsetDateTime now) {
        boolean trusted = PAYPAL_PROVIDER.equals(event.getProvider())
                && event.getSourceType() == PaymentEvent.SourceType.PROVIDER
                && event.getVerificationStatus() == PaymentEvent.VerificationStatus.VERIFIED;
        ProviderTimestamp providerTimestamp = trusted
                ? providerTimestamp(event.getPayload())
                : new ProviderTimestamp(false, null);
        OffsetDateTime providerAt = null;
        String timestampSource = "UNAVAILABLE";
        if (trusted) {
            if (!providerTimestamp.present()) {
                timestampSource = "RECEIVED_AT_FALLBACK";
                providerAt = event.getReceivedAt();
            } else {
                timestampSource = "PROVIDER_CREATE_TIME";
                providerAt = providerTimestamp.value();
            }
        }
        String freshness = "NOT_APPLICABLE";
        OffsetDateTime effectiveAt = trusted ? providerAt : null;
        if (trusted) {
            freshness = isFresh(providerAt, now) ? "FRESH" : "STALE_OR_UNKNOWN";
        }
        return new EventEvidence(
                event.getId(),
                event.getEventType(),
                event.getProvider(),
                event.getSourceType() == null ? null : event.getSourceType().name(),
                event.getVerificationStatus() == null
                        ? null : event.getVerificationStatus().name(),
                event.getReceivedAt(),
                providerTimestamp.value(),
                effectiveAt,
                timestampSource,
                freshness,
                trusted);
    }

    private DecisionResult decide(
            MerchantOrder order,
            List<PaymentAttempt> attempts,
            List<AttemptEvidence> evidence) {
        if (attempts.isEmpty()) {
            return waitResult("NO_PAYMENT_ATTEMPTS",
                    "No payment attempts exist; there is no verified terminal denial evidence.");
        }
        boolean hasDenied = false;
        boolean hasCompleted = false;
        boolean incompleteEvidence = false;
        boolean hasConflict = false;
        boolean retryProof = true;

        for (int index = 0; index < attempts.size(); index++) {
            PaymentAttempt attempt = attempts.get(index);
            AttemptEvidence attemptSnapshot = evidence.get(index);
            List<EventEvidence> attemptEvents = attemptSnapshot.events();
            List<EventEvidence> trusted = attemptEvents.stream()
                    .filter(EventEvidence::trustedPayPalEvidence)
                    .toList();

            boolean hasAttemptDenial = trusted.stream()
                    .anyMatch(event -> DENIED_EVENT.equals(event.eventType()));
            boolean hasAttemptCompletion = trusted.stream()
                    .anyMatch(event -> COMPLETED_EVENT.equals(event.eventType()));
            hasDenied |= hasAttemptDenial;
            hasCompleted |= hasAttemptCompletion;
            if (hasAttemptDenial && hasAttemptCompletion) {
                hasConflict = true;
            }
            if ((attempt.getPaymentStatus() == PaymentAttempt.PaymentStatus.FAILED
                    && hasAttemptCompletion)
                    || (attempt.getPaymentStatus() == PaymentAttempt.PaymentStatus.SUCCEEDED
                    && hasAttemptDenial)) {
                hasConflict = true;
            }
            List<EventEvidence> denials = trusted.stream()
                    .filter(event -> DENIED_EVENT.equals(event.eventType()))
                    .sorted(Comparator.comparing(
                            EventEvidence::effectiveEvidenceAt,
                            Comparator.nullsLast(Comparator.naturalOrder())))
                    .toList();
            EventEvidence latestDenial = denials.isEmpty() ? null : denials.get(denials.size() - 1);

            if (attempt.getPaymentStatus() != PaymentAttempt.PaymentStatus.FAILED
                    || !PAYPAL_PROVIDER.equals(attempt.getProvider())
                    || latestDenial == null
                    || !"FRESH".equals(latestDenial.freshness())) {
                retryProof = false;
                incompleteEvidence = true;
            }
            if (attemptEvents.stream().anyMatch(event -> !event.trustedPayPalEvidence())) {
                retryProof = false;
                incompleteEvidence = true;
            }
            if (latestDenial != null) {
                for (EventEvidence event : trusted) {
                    if (event != latestDenial && event.effectiveEvidenceAt() != null
                            && latestDenial.effectiveEvidenceAt() != null
                            && event.effectiveEvidenceAt().isAfter(
                            latestDenial.effectiveEvidenceAt())) {
                        hasConflict = true;
                    }
                }
            }
            if (trusted.stream().anyMatch(event -> PENDING_EVENT.equals(event.eventType()))) {
                retryProof = false;
                incompleteEvidence = true;
            }
            if (trusted.stream().anyMatch(event -> !DENIED_EVENT.equals(event.eventType())
                    && !COMPLETED_EVENT.equals(event.eventType())
                    && !PENDING_EVENT.equals(event.eventType()))) {
                retryProof = false;
                incompleteEvidence = true;
            }
        }

        boolean allAttemptsFailed = attempts.stream().allMatch(
                attempt -> attempt.getPaymentStatus() == PaymentAttempt.PaymentStatus.FAILED);
        if ((order.getOrderStatus() == MerchantOrder.OrderStatus.PAID
                && hasDenied && !hasCompleted)
                || (hasCompleted && order.getOrderStatus() != MerchantOrder.OrderStatus.PAID)
                || (order.getOrderStatus() == MerchantOrder.OrderStatus.PENDING
                && allAttemptsFailed && hasDenied && !hasCompleted)) {
            hasConflict = true;
        }
        if (hasConflict) {
            return new DecisionResult(Investigation.Decision.RECONCILE,
                    "CONFLICTING_PAYMENT_EVIDENCE",
                    "Verified provider outcomes conflict with each other or the current merchant/payment status; reconcile before any recovery action.");
        }
        if (order.getOrderStatus() == MerchantOrder.OrderStatus.PENDING) {
            return waitResult("MERCHANT_ORDER_PENDING",
                    "The merchant order is pending; wait for its outcome before considering recovery.");
        }
        if (order.getOrderStatus() == MerchantOrder.OrderStatus.CANCELLED) {
            return waitResult("MERCHANT_ORDER_CANCELLED",
                    "The merchant order is cancelled; no recovery action is indicated.");
        }
        if (hasCompleted) {
            return waitResult("PAYMENT_CONFIRMED",
                    "Verified PayPal completion evidence exists; no retry is indicated.");
        }
        if (retryProof) {
            return new DecisionResult(Investigation.Decision.RETRY_ELIGIBLE,
                    "ALL_ATTEMPTS_FRESHLY_DENIED",
                    "Every known attempt is failed and has fresh, verified PayPal terminal-denial evidence with no later, untrusted, pending, successful, or unknown outcome.");
        }
        if (!incompleteEvidence && hasDenied) {
            incompleteEvidence = true;
        }
        return waitResult(incompleteEvidence
                        ? "INSUFFICIENT_OR_STALE_EVIDENCE"
                        : "PAYMENT_OUTCOME_UNCERTAIN",
                "Wait for fresh, verified terminal PayPal denial evidence for every attempt; uncertainty must not trigger a retry.");
    }

    private DecisionResult waitResult(String reason, String explanation) {
        return new DecisionResult(Investigation.Decision.WAIT, reason, explanation);
    }

    private boolean isFresh(OffsetDateTime evidenceAt, OffsetDateTime now) {
        if (evidenceAt == null || evidenceAt.isAfter(now)) {
            return false;
        }
        Duration age = Duration.between(evidenceAt, now);
        return age.compareTo(Duration.ofMinutes(FRESHNESS_THRESHOLD_MINUTES)) <= 0;
    }

    private ProviderTimestamp providerTimestamp(String payload) {
        try {
            JsonNode root = objectMapper.readTree(payload);
            JsonNode node = root == null ? null : root.get("create_time");
            if (node == null) {
                return new ProviderTimestamp(false, null);
            }
            String value = node.isString() ? node.asString() : null;
            return new ProviderTimestamp(true, parseProviderTime(value));
        } catch (JacksonException ignored) {
            return new ProviderTimestamp(true, null);
        }
    }

    private OffsetDateTime parseProviderTime(String value) {
        if (value == null) {
            return null;
        }
        try {
            return OffsetDateTime.parse(value);
        } catch (DateTimeParseException ignored) {
            return null;
        }
    }

    private record DecisionResult(
            Investigation.Decision decision, String reason, String explanation) {
    }

    private record ProviderTimestamp(boolean present, OffsetDateTime value) {
    }
}
