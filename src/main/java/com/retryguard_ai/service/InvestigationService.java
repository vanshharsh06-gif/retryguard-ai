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
import com.retryguard_ai.dto.InvestigationResponse.PaymentStateClassification;
import com.retryguard_ai.dto.InvestigationResponse.TimelineEntry;
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
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

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

        // Payment-state classification per attempt
        List<PaymentStateClassification> classifications = classifyPaymentStates(
                attempts, attemptEvidence);

        // Timeline reconstruction from all evidence
        List<TimelineEntry> timeline = reconstructTimeline(order, attempts, events, now);

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

        return toResponse(saved, snapshot, classifications, timeline);
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
            // Reconstruct classifications and timeline from stored snapshot
            List<PaymentStateClassification> classifications = classifyPaymentStates(
                    snapshot.attempts().stream()
                            .map(a -> toPaymentAttempt(a))
                            .toList(),
                    snapshot.attempts());
            List<TimelineEntry> timeline = reconstructTimelineFromSnapshot(
                    snapshot, investigation.getCreatedAt());
            return new InvestigationResponse(
                    investigation.getId(),
                    investigation.getOrder().getId(),
                    investigation.getDecision().name(),
                    investigation.getDecisionReason(),
                    investigation.getRulesVersion(),
                    investigation.getExplanation(),
                    snapshot,
                    findings,
                    classifications,
                    timeline,
                    investigation.getCreatedAt());
        } catch (JacksonException exception) {
            throw new IllegalStateException("Stored investigation data is invalid.", exception);
        }
    }

    private InvestigationResponse toResponse(
            Investigation investigation,
            EvidenceSnapshot snapshot,
            List<PaymentStateClassification> classifications,
            List<TimelineEntry> timeline) {
        try {
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
                    classifications,
                    timeline,
                    investigation.getCreatedAt());
        } catch (JacksonException exception) {
            throw new IllegalStateException("Stored investigation data is invalid.", exception);
        }
    }

    private List<AutopsyFinding> findingsFromJson(Investigation investigation) {
        try {
            return objectMapper.readValue(
                    investigation.getFindings(), new TypeReference<>() { });
        } catch (JacksonException exception) {
            throw new IllegalStateException("Stored investigation data is invalid.", exception);
        }
    }

    /**
     * Reconstructs a minimal PaymentAttempt from AttemptEvidence for classification purposes.
     * Only provider and paymentStatus are needed; these are stored in the snapshot.
     */
    private PaymentAttempt toPaymentAttempt(AttemptEvidence evidence) {
        PaymentAttempt attempt = new PaymentAttempt();
        attempt.setId(evidence.paymentAttemptId());
        attempt.setProvider(evidence.provider());
        attempt.setPaymentStatus(
                evidence.paymentStatus() == null
                        ? null
                        : PaymentAttempt.PaymentStatus.valueOf(evidence.paymentStatus()));
        return attempt;
    }

    /**
     * Reconstructs a timeline from a stored EvidenceSnapshot for investigation retrieval.
     * All events in the snapshot are already verified and stored; no new events are created.
     */
    private List<TimelineEntry> reconstructTimelineFromSnapshot(
            EvidenceSnapshot snapshot, OffsetDateTime investigationCreatedAt) {
        List<TimelineEntry> timeline = new ArrayList<>();
        OffsetDateTime now = investigationCreatedAt != null
                ? investigationCreatedAt
                : OffsetDateTime.now();

        // Attempt entries from snapshot
        for (AttemptEvidence attemptEvidence : snapshot.attempts()) {
            OffsetDateTime attemptTime = attemptEvidence.createdAt() != null
                    ? attemptEvidence.createdAt()
                    : investigationCreatedAt;
            timeline.add(new TimelineEntry(
                    attemptTime,
                    attemptEvidence.createdAt(),
                    "PAYMENT_ATTEMPT",
                    "ATTEMPT_STATUS_" + (attemptEvidence.paymentStatus() != null
                            ? attemptEvidence.paymentStatus() : "UNKNOWN"),
                    "Payment attempt " + attemptEvidence.paymentAttemptId()
                            + " status: " + attemptEvidence.paymentStatus(),
                    "PAYMENT_ATTEMPT"));

            // Event entries from snapshot
            for (EventEvidence event : attemptEvidence.events()) {
                OffsetDateTime eventTimestamp = event.effectiveEvidenceAt() != null
                        ? event.effectiveEvidenceAt()
                        : event.receivedAt();
                timeline.add(new TimelineEntry(
                        eventTimestamp,
                        event.receivedAt(),
                        (event.provider() != null ? event.provider() : "UNKNOWN")
                                + ":" + (event.sourceType() != null ? event.sourceType() : "UNKNOWN")
                                + ":" + (event.verificationStatus() != null
                                ? event.verificationStatus() : "UNKNOWN"),
                        event.eventType(),
                        event.eventType(),
                        "PAYMENT_EVENT"));
            }
        }

        // Sort by timestamp, then received_at as tie-breaker
        timeline.sort(Comparator
                .comparing((TimelineEntry t) -> t.timestamp(),
                        Comparator.nullsLast(Comparator.naturalOrder()))
                .thenComparing(TimelineEntry::receivedAt,
                        Comparator.nullsLast(Comparator.naturalOrder()))
                .thenComparing(TimelineEntry::eventType));

        return List.copyOf(timeline);
    }

    /**
     * Classifies each payment attempt into a deterministic state based on available evidence.
     * States: SUCCESSFUL, FAILED, PENDING, CONFLICTING, UNCERTAIN.
     */
    private List<PaymentStateClassification> classifyPaymentStates(
            List<PaymentAttempt> attempts,
            List<AttemptEvidence> attemptEvidence) {
        List<PaymentStateClassification> classifications = new ArrayList<>();

        for (int index = 0; index < attempts.size(); index++) {
            PaymentAttempt attempt = attempts.get(index);
            AttemptEvidence snapshot = attemptEvidence.get(index);
            List<EventEvidence> events = snapshot.events();
            List<EventEvidence> trusted = events.stream()
                    .filter(EventEvidence::trustedPayPalEvidence)
                    .toList();

            List<EventEvidence> denials = trusted.stream()
                    .filter(event -> DENIED_EVENT.equals(event.eventType()))
                    .toList();
            List<EventEvidence> completions = trusted.stream()
                    .filter(event -> COMPLETED_EVENT.equals(event.eventType()))
                    .toList();
            List<EventEvidence> pendings = trusted.stream()
                    .filter(event -> PENDING_EVENT.equals(event.eventType()))
                    .toList();

            boolean hasDenial = !denials.isEmpty();
            boolean hasCompletion = !completions.isEmpty();
            boolean hasPending = !pendings.isEmpty();
            List<UUID> evidenceIds = events.stream()
                    .map(EventEvidence::eventId)
                    .toList();

            String state;
            String reason;

            if (hasDenial && hasCompletion) {
                // Only flag conflict when denial and completion refer to different captures
                // or when they genuinely contradict on the same capture reference.
                Set<String> denialCaptureRefs = denials.stream()
                        .map(EventEvidence::captureReference)
                        .filter(Objects::nonNull)
                        .collect(Collectors.toSet());
                Set<String> completionCaptureRefs = completions.stream()
                        .map(EventEvidence::captureReference)
                        .filter(Objects::nonNull)
                        .collect(Collectors.toSet());
                boolean capturesOverlap = denialCaptureRefs.stream()
                        .anyMatch(completionCaptureRefs::contains);
                if (denialCaptureRefs.isEmpty() && completionCaptureRefs.isEmpty()) {
                    // No capture references available; preserve conservative reconciliation
                    state = "CONFLICTING";
                    reason = "Verified provider evidence contains both denial and completion for this attempt; capture references unavailable";
                } else if (!capturesOverlap) {
                    // Different capture references = genuinely contradictory evidence
                    state = "CONFLICTING";
                    reason = "Verified provider denial and completion reference different capture IDs; contradictory evidence";
                } else {
                    // Same capture reference: not necessarily contradictory (e.g., delayed
                    // state transition); classify as UNCERTAIN
                    state = "UNCERTAIN";
                    reason = "Verified provider denial and completion reference the same capture; conflicting terminal evidence requires reconciliation";
                }
            } else if (hasCompletion) {
                state = "SUCCESSFUL";
                reason = "Verified provider completion evidence exists for this attempt";
            } else if (hasPending && !hasDenial && !hasCompletion) {
                state = "PENDING";
                reason = "Verified provider pending evidence exists; no terminal outcome yet";
            } else if (hasDenial && !hasCompletion && !hasPending) {
                // Check freshness for denial
                EventEvidence latestDenial = denials.stream()
                        .max(Comparator.comparing(EventEvidence::effectiveEvidenceAt,
                                Comparator.nullsLast(Comparator.naturalOrder())))
                        .orElse(null);
                boolean freshDenial = latestDenial != null
                        && "FRESH".equals(latestDenial.freshness());
                state = freshDenial ? "FAILED" : "UNCERTAIN";
                reason = freshDenial
                        ? "Fresh, verified provider denial evidence exists for this attempt"
                        : "Denial evidence exists but is stale or unverified; cannot confirm failure";
            } else if (attempt.getPaymentStatus() == PaymentAttempt.PaymentStatus.SUCCEEDED
                    && !hasDenial && !hasCompletion) {
                state = "UNCERTAIN";
                reason = "Attempt is marked SUCCEEDED locally but no verified provider evidence confirms completion";
                evidenceIds = evidenceIds.stream()
                        .filter(id -> !id.equals(attempt.getId()))
                        .collect(Collectors.toList());
            } else if (attempt.getPaymentStatus() == PaymentAttempt.PaymentStatus.FAILED
                    && !hasDenial && !hasCompletion && !hasPending) {
                state = "UNCERTAIN";
                reason = "Attempt is marked FAILED locally but no verified provider terminal evidence exists";
            } else if (attempt.getPaymentStatus() == PaymentAttempt.PaymentStatus.PENDING
                    && !hasDenial && !hasCompletion && !hasPending) {
                state = "PENDING";
                reason = "Attempt is pending locally; no verified terminal provider evidence yet";
            } else if (attempt.getPaymentStatus() == PaymentAttempt.PaymentStatus.CREATED
                    && events.isEmpty()) {
                state = "UNCERTAIN";
                reason = "No payment events recorded for this attempt; evidence is missing";
            } else {
                state = "UNCERTAIN";
                reason = "Insufficient verified provider evidence to determine a terminal state";
            }

            classifications.add(new PaymentStateClassification(
                    attempt.getId(),
                    state,
                    reason,
                    List.copyOf(evidenceIds)));
        }

        return List.copyOf(classifications);
    }

    /**
     * Reconstructs the payment timeline from order records, attempts, and events.
     * Events are sorted by effective evidence time (provider timestamp when available,
     * received_at otherwise), preserving timestamps and sources.
     * No events are invented; only actual recorded evidence appears.
     * Invalid and future timestamps are handled conservatively.
     */
    private List<TimelineEntry> reconstructTimeline(
            MerchantOrder order,
            List<PaymentAttempt> attempts,
            List<PaymentEvent> events,
            OffsetDateTime now) {
        List<TimelineEntry> timeline = new ArrayList<>();

        // Order creation
        if (order.getCreatedAt() != null) {
            timeline.add(new TimelineEntry(
                    order.getCreatedAt(),
                    order.getCreatedAt(),
                    "MERCHANT_ORDER",
                    "ORDER_CREATED",
                    "Merchant order created: " + order.getMerchantOrderReference(),
                    "ORDER"));
        }

        // Order status updates
        if (order.getUpdatedAt() != null) {
            timeline.add(new TimelineEntry(
                    order.getUpdatedAt(),
                    order.getUpdatedAt(),
                    "MERCHANT_ORDER",
                    "ORDER_STATUS_" + order.getOrderStatus().name(),
                    "Merchant order status: " + order.getOrderStatus().name(),
                    "ORDER"));
        }

        // Attempt creations and statuses
        for (PaymentAttempt attempt : attempts) {
            OffsetDateTime attemptEffective = attempt.getCreatedAt() != null
                    ? attempt.getCreatedAt()
                    : OffsetDateTime.now();
            timeline.add(new TimelineEntry(
                    attemptEffective,
                    attempt.getCreatedAt(),
                    "PAYMENT_ATTEMPT",
                    "ATTEMPT_CREATED",
                    "Payment attempt created for order " + order.getMerchantOrderReference()
                            + " (provider: " + attempt.getProvider() + ")",
                    "PAYMENT_ATTEMPT"));

            if (attempt.getUpdatedAt() != null
                    && !attempt.getUpdatedAt().equals(attempt.getCreatedAt())) {
                timeline.add(new TimelineEntry(
                        attempt.getUpdatedAt(),
                        attempt.getUpdatedAt(),
                        "PAYMENT_ATTEMPT",
                        "ATTEMPT_STATUS_" + attempt.getPaymentStatus().name(),
                        "Payment attempt status: " + attempt.getPaymentStatus().name(),
                        "PAYMENT_ATTEMPT"));
            }
        }

        // Events sorted by effective evidence time, then received_at as tie-breaker
        List<PaymentEvent> sortedEvents = events.stream()
                .sorted(Comparator
                        .comparing((PaymentEvent e) -> effectiveEventTime(e, now),
                                Comparator.nullsLast(Comparator.naturalOrder()))
                        .thenComparing(PaymentEvent::getReceivedAt,
                                Comparator.nullsLast(Comparator.naturalOrder()))
                        .thenComparing(PaymentEvent::getId))
                .toList();

        for (PaymentEvent event : sortedEvents) {
            OffsetDateTime effectiveTime = effectiveEventTime(event, now);
            OffsetDateTime receivedAt = event.getReceivedAt();
            OffsetDateTime timestamp = effectiveTime != null ? effectiveTime : receivedAt;
            String source = event.getProvider() + ":" + event.getSourceType().name()
                    + ":" + event.getVerificationStatus().name();
            String description = event.getEventType();
            if (event.getProviderEventId() != null) {
                description += " (provider event: " + event.getProviderEventId() + ")";
            }
            timeline.add(new TimelineEntry(
                    timestamp,
                    receivedAt,
                    source,
                    event.getEventType(),
                    description,
                    "PAYMENT_EVENT"));
        }

        // Sort timeline by effective timestamp, then received_at as deterministic tie-breaker
        timeline.sort(Comparator
                .comparing((TimelineEntry t) -> t.timestamp(), Comparator.nullsLast(Comparator.naturalOrder()))
                .thenComparing(TimelineEntry::receivedAt, Comparator.nullsLast(Comparator.naturalOrder()))
                .thenComparing(TimelineEntry::eventType));

        return List.copyOf(timeline);
    }

    /**
     * Returns the effective evidence timestamp for an event:
     * provider create_time when valid and not in the future,
     * null otherwise (caller should fall back to received_at).
     */
    private OffsetDateTime effectiveEventTime(PaymentEvent event, OffsetDateTime now) {
        if (event == null) {
            return null;
        }
        if (event.getProvider() != null
                && event.getProvider().equals(PAYPAL_PROVIDER)
                && event.getSourceType() == PaymentEvent.SourceType.PROVIDER
                && event.getVerificationStatus() == PaymentEvent.VerificationStatus.VERIFIED) {
            OffsetDateTime providerTime = parseProviderTime(event.getPayload());
            if (providerTime != null && !providerTime.isAfter(now)) {
                return providerTime;
            }
        }
        return null;
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
            findings.add(finding(Code.STALE_EVIDENCE, "Provider evidence is stale",
                    "Verified provider evidence exists but is older than the freshness threshold (" + FRESHNESS_THRESHOLD_MINUTES + " minutes).",
                    RootCauseCategory.STALE_EVIDENCE, Severity.MEDIUM, staleEvidence,
                    "Wait for fresh verified terminal provider evidence before making a retry decision.", true));
        }

        List<EvidenceReference> unverifiedEvidence = evidence.stream()
                .flatMap(attempt -> attempt.events().stream()
                        .filter(event -> !event.trustedPayPalEvidence())
                        .map(event -> new EvidenceReference(EvidenceType.PAYMENT_EVENT, event.eventId())))
                .toList();
        if (!unverifiedEvidence.isEmpty()) {
            findings.add(finding(Code.UNVERIFIED_EVIDENCE, "Unverified payment event",
                    "A payment event exists but is not from a verified PayPal provider source.",
                    RootCauseCategory.UNVERIFIED_EVIDENCE, Severity.MEDIUM, unverifiedEvidence,
                    "Do not act on unverified provider evidence; wait for verified terminal outcome.", true));
        }

        List<EvidenceReference> missingEvidence = new ArrayList<>();
        if (attempts.isEmpty()) {
            missingEvidence.add(new EvidenceReference(EvidenceType.ORDER, order.getId()));
        } else {
            for (int index = 0; index < attempts.size(); index++) {
                PaymentAttempt attempt = attempts.get(index);
                List<EventEvidence> attemptEvents = evidence.get(index).events();
                boolean hasTrustedTerminal = attemptEvents.stream()
                        .filter(EventEvidence::trustedPayPalEvidence)
                        .anyMatch(event -> DENIED_EVENT.equals(event.eventType())
                                || COMPLETED_EVENT.equals(event.eventType()));
                if (!hasTrustedTerminal) {
                    missingEvidence.add(new EvidenceReference(EvidenceType.PAYMENT_ATTEMPT, attempt.getId()));
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
        // Only verified provider completion evidence counts as payment-confirmed.
        // Local PAID/SUCCEEDED status alone does not prove provider completion.
        boolean hasVerifiedCompletion = false;
        for (int index = 0; index < attempts.size(); index++) {
            PaymentAttempt attempt = attempts.get(index);
            int sizeBefore = confirmedEvidence.size();
            evidence.get(index).events().stream()
                    .filter(event -> event.trustedPayPalEvidence()
                            && COMPLETED_EVENT.equals(event.eventType()))
                    .map(event -> new EvidenceReference(EvidenceType.PAYMENT_EVENT, event.eventId()))
                    .forEach(confirmedEvidence::add);
            if (confirmedEvidence.size() > sizeBefore) {
                hasVerifiedCompletion = true;
            }
        }
        if (hasVerifiedCompletion && !confirmedEvidence.isEmpty()) {
            findings.add(finding(Code.PAYMENT_CONFIRMED, "Payment completion is recorded",
                    "Verified provider completion evidence records a completed payment outcome.",
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
                    attempt.getCreatedAt(),
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
                trusted,
                event.getProviderEventId(),
                nestedCaptureReference(event.getPayload()));
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
            List<EventEvidence> denials = trusted.stream()
                    .filter(event -> DENIED_EVENT.equals(event.eventType()))
                    .toList();
            List<EventEvidence> completions = trusted.stream()
                    .filter(event -> COMPLETED_EVENT.equals(event.eventType()))
                    .toList();
            hasDenied |= hasAttemptDenial;
            hasCompleted |= hasAttemptCompletion;
            // Only flag conflict when denial and completion refer to different captures
            // or when they genuinely contradict on the same capture reference.
            if (hasAttemptDenial && hasAttemptCompletion) {
                Set<String> denialCaptureRefs = denials.stream()
                        .map(EventEvidence::captureReference)
                        .filter(Objects::nonNull)
                        .collect(Collectors.toSet());
                Set<String> completionCaptureRefs = completions.stream()
                        .map(EventEvidence::captureReference)
                        .filter(Objects::nonNull)
                        .collect(Collectors.toSet());
                boolean capturesOverlap = denialCaptureRefs.stream()
                        .anyMatch(completionCaptureRefs::contains);
                if (denialCaptureRefs.isEmpty() && completionCaptureRefs.isEmpty()) {
                    // No capture references available; preserve conservative reconciliation
                    hasConflict = true;
                } else if (!capturesOverlap) {
                    // Different capture references = genuinely contradictory evidence
                    hasConflict = true;
                }
                // Same capture reference: not necessarily contradictory (e.g., delayed
                // state transition); do not flag as conflict.
            }
            if ((attempt.getPaymentStatus() == PaymentAttempt.PaymentStatus.FAILED
                    && hasAttemptCompletion)
                    || (attempt.getPaymentStatus() == PaymentAttempt.PaymentStatus.SUCCEEDED
                    && hasAttemptDenial)) {
                hasConflict = true;
            }
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

    /**
     * Extracts the PayPal capture reference (resource.id) from a webhook event payload.
     * Returns null when the payload does not contain a resource.id, e.g., for events
     * that do not carry a capture identifier.
     */
    private String nestedCaptureReference(String payload) {
        try {
            JsonNode root = objectMapper.readTree(payload);
            JsonNode resource = root == null ? null : root.get("resource");
            if (resource == null || !resource.isObject()) {
                return null;
            }
            JsonNode idNode = resource.get("id");
            if (idNode == null || !idNode.isTextual()) {
                return null;
            }
            String id = idNode.asText();
            return id.isBlank() ? null : id;
        } catch (JacksonException ignored) {
            return null;
        }
    }

    private record DecisionResult(
            Investigation.Decision decision, String reason, String explanation) {
    }

    private record ProviderTimestamp(boolean present, OffsetDateTime value) {
    }
}