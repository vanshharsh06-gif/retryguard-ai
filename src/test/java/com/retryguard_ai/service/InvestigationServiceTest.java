package com.retryguard_ai.service;

import com.retryguard_ai.dto.InvestigationResponse;
import com.retryguard_ai.dto.AutopsyFinding;
import com.retryguard_ai.entity.Investigation;
import com.retryguard_ai.entity.InvestigationHistory;
import com.retryguard_ai.entity.MerchantOrder;
import com.retryguard_ai.entity.PaymentAttempt;
import com.retryguard_ai.entity.PaymentEvent;
import com.retryguard_ai.exception.MerchantOrderNotFoundException;
import com.retryguard_ai.repository.InvestigationHistoryRepository;
import com.retryguard_ai.repository.InvestigationRepository;
import com.retryguard_ai.repository.MerchantOrderRepository;
import com.retryguard_ai.repository.PaymentAttemptRepository;
import com.retryguard_ai.repository.PaymentEventRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import tools.jackson.databind.ObjectMapper;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class InvestigationServiceTest {

    @Mock private MerchantOrderRepository merchantOrderRepository;
    @Mock private PaymentAttemptRepository paymentAttemptRepository;
    @Mock private PaymentEventRepository paymentEventRepository;
    @Mock private InvestigationRepository investigationRepository;
    @Mock private InvestigationHistoryRepository investigationHistoryRepository;

    private InvestigationService service;
    private MerchantOrder order;
    private PaymentAttempt attempt;

    @BeforeEach
    void setUp() {
        service = new InvestigationService(
                merchantOrderRepository,
                paymentAttemptRepository,
                paymentEventRepository,
                investigationRepository,
                investigationHistoryRepository,
                new ObjectMapper());
        order = new MerchantOrder();
        order.setId(UUID.randomUUID());
        order.setMerchantOrderReference("internal-reference");
        order.setAmount(new BigDecimal("12.00"));
        order.setCurrency("USD");
        order.setOrderStatus(MerchantOrder.OrderStatus.FAILED);
        attempt = new PaymentAttempt();
        attempt.setId(UUID.randomUUID());
        attempt.setOrder(order);
        attempt.setProvider("PAYPAL_SANDBOX");
        attempt.setPaymentStatus(PaymentAttempt.PaymentStatus.FAILED);
        lenient().when(merchantOrderRepository.findById(order.getId()))
                .thenReturn(Optional.of(order));
        lenient().when(paymentAttemptRepository.findByOrder_Id(order.getId()))
                .thenReturn(List.of(attempt));
        lenient().when(paymentEventRepository.findByPaymentAttempt_IdInOrderByReceivedAtAsc(
                List.of(attempt.getId()))).thenReturn(List.of());
        lenient().when(investigationRepository.findFirstByOrder_IdOrderByCreatedAtDescIdDesc(order.getId()))
                .thenReturn(Optional.empty());
        lenient().when(investigationRepository.save(any(Investigation.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));
        lenient().when(investigationHistoryRepository.save(any(InvestigationHistory.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));
    }

    @Test
    void grantsRetryEligibilityOnlyWithFreshVerifiedDenialForEveryAttemptAndPersistsAudit() {
        PaymentEvent denial = event("PAYMENT.CAPTURE.DENIED",
                PaymentEvent.SourceType.PROVIDER,
                PaymentEvent.VerificationStatus.VERIFIED,
                OffsetDateTime.now().minusMinutes(2),
                "{\"create_time\":\"" + OffsetDateTime.now().minusMinutes(2) + "\"}");
        when(paymentEventRepository.findByPaymentAttempt_IdInOrderByReceivedAtAsc(
                List.of(attempt.getId()))).thenReturn(List.of(denial));

        InvestigationResponse response = service.investigate(order.getId());

        assertEquals("RETRY_ELIGIBLE", response.decision());
        assertEquals("1.0", response.rulesVersion());
        assertEquals("PROVIDER_CREATE_TIME",
                response.evidence().attempts().get(0).events().get(0).timestampSource());
        assertEquals("FRESH", response.evidence().attempts().get(0).events().get(0).freshness());
        ArgumentCaptor<Investigation> investigationCaptor =
                ArgumentCaptor.forClass(Investigation.class);
        verify(investigationRepository).save(investigationCaptor.capture());
        assertEquals("RETRY_ELIGIBLE", investigationCaptor.getValue().getDecision().name());
        assertFalse(investigationCaptor.getValue().getEvidenceSnapshot().contains("internal-reference"));
        ArgumentCaptor<InvestigationHistory> historyCaptor =
                ArgumentCaptor.forClass(InvestigationHistory.class);
        verify(investigationHistoryRepository).save(historyCaptor.capture());
        assertEquals("CREATED", historyCaptor.getValue().getAction());
        assertEquals(Investigation.Decision.RETRY_ELIGIBLE, historyCaptor.getValue().getNewDecision());
        assertNotNull(historyCaptor.getValue().getCreatedAt());
    }

    @Test
    void providerDenialFindingReferencesOnlyTheSupportingVerifiedEvent() {
        PaymentEvent denial = event("PAYMENT.CAPTURE.DENIED",
                PaymentEvent.SourceType.PROVIDER,
                PaymentEvent.VerificationStatus.VERIFIED,
                OffsetDateTime.now().minusMinutes(2),
                "{\"create_time\":\"" + OffsetDateTime.now().minusMinutes(2) + "\"}");
        when(paymentEventRepository.findByPaymentAttempt_IdInOrderByReceivedAtAsc(
                List.of(attempt.getId()))).thenReturn(List.of(denial));

        InvestigationResponse response = service.investigate(order.getId());

        assertEquals("RETRY_ELIGIBLE", response.decision());
        AutopsyFinding finding = response.findings().stream()
                .filter(item -> item.code().name().equals("PROVIDER_DENIAL"))
                .findFirst().orElseThrow();
        assertEquals(AutopsyFinding.RootCauseCategory.PROVIDER_DENIAL,
                finding.rootCauseCategory());
        assertEquals(List.of(new AutopsyFinding.EvidenceReference(
                        AutopsyFinding.EvidenceType.PAYMENT_EVENT, denial.getId())),
                finding.evidence());
        assertTrue(finding.rootCauseUncertain());
        assertTrue(finding.recommendedAction().toLowerCase().contains("review"));
    }

    @Test
    void pendingUnverifiedAndMissingFindingsReferenceTheirPersistedEvidence() {
        PaymentAttempt pendingAttempt = attempt(
                "PAYPAL_SANDBOX", PaymentAttempt.PaymentStatus.PENDING);
        PaymentAttempt missingAttempt = attempt(
                "PAYPAL_SANDBOX", PaymentAttempt.PaymentStatus.CREATED);
        PaymentEvent simulated = event("PAYMENT.PENDING",
                PaymentEvent.SourceType.SIMULATED,
                PaymentEvent.VerificationStatus.NOT_APPLICABLE,
                OffsetDateTime.now(),
                "{}");
        simulated.setPaymentAttempt(pendingAttempt);
        when(paymentAttemptRepository.findByOrder_Id(order.getId()))
                .thenReturn(List.of(pendingAttempt, missingAttempt));
        when(paymentEventRepository.findByPaymentAttempt_IdInOrderByReceivedAtAsc(any()))
                .thenReturn(List.of(simulated));

        InvestigationResponse response = service.investigate(order.getId());

        assertEquals("WAIT", response.decision());
        assertFindingReferences(response, "PENDING_PAYMENT", pendingAttempt.getId());
        assertFindingReferences(response, "UNVERIFIED_EVIDENCE", simulated.getId());
        assertFindingReferences(response, "MISSING_EVIDENCE", missingAttempt.getId());
    }

    @Test
    void staleConflictingAndConfirmedFindingsAreEvidenceLinkedWithoutChangingDecision() {
        PaymentEvent denial = event("PAYMENT.CAPTURE.DENIED",
                PaymentEvent.SourceType.PROVIDER,
                PaymentEvent.VerificationStatus.VERIFIED,
                OffsetDateTime.now().minusMinutes(30),
                "{\"create_time\":\"" + OffsetDateTime.now().minusMinutes(30) + "\"}");
        PaymentEvent completion = event("PAYMENT.CAPTURE.COMPLETED",
                PaymentEvent.SourceType.PROVIDER,
                PaymentEvent.VerificationStatus.VERIFIED,
                OffsetDateTime.now().minusMinutes(29),
                "{\"create_time\":\"" + OffsetDateTime.now().minusMinutes(29) + "\"}");
        when(paymentEventRepository.findByPaymentAttempt_IdInOrderByReceivedAtAsc(
                List.of(attempt.getId()))).thenReturn(List.of(denial, completion));
        order.setOrderStatus(MerchantOrder.OrderStatus.PAID);

        InvestigationResponse response = service.investigate(order.getId());

        assertEquals("RECONCILE", response.decision());
        assertFindingReferences(response, "STALE_EVIDENCE", denial.getId());
        assertFindingReferences(response, "STALE_EVIDENCE", completion.getId());
        assertFindingReferences(response, "CONFLICTING_OUTCOMES", order.getId());
        assertFindingReferences(response, "PAYMENT_CONFIRMED", order.getId());
    }

    private void assertFindingReferences(
            InvestigationResponse response, String code, UUID evidenceId) {
        AutopsyFinding finding = response.findings().stream()
                .filter(item -> item.code().name().equals(code))
                .findFirst().orElseThrow();
        assertTrue(finding.evidence().stream().anyMatch(reference -> evidenceId.equals(reference.id())));
        assertTrue(finding.rootCauseUncertain());
    }

    @Test
    void requiresEveryAttemptToHaveDenialEvidence() {
        PaymentAttempt second = new PaymentAttempt();
        second.setId(UUID.randomUUID());
        second.setOrder(order);
        second.setProvider("PAYPAL_SANDBOX");
        second.setPaymentStatus(PaymentAttempt.PaymentStatus.CREATED);
        when(paymentAttemptRepository.findByOrder_Id(order.getId())).thenReturn(List.of(attempt, second));
        when(paymentEventRepository.findByPaymentAttempt_IdInOrderByReceivedAtAsc(
                any())).thenReturn(List.of(event(
                "PAYMENT.CAPTURE.DENIED",
                PaymentEvent.SourceType.PROVIDER,
                PaymentEvent.VerificationStatus.VERIFIED,
                OffsetDateTime.now().minusMinutes(1),
                "{}")));

        InvestigationResponse response = service.investigate(order.getId());

        assertEquals("WAIT", response.decision());
        assertEquals(2, response.evidence().attempts().size());
    }

    @Test
    void multipleAttemptsWithoutEventsAndSimulatedPendingRemainUncertain() {
        PaymentAttempt secondWithoutEvents = attempt("PAYPAL_SANDBOX",
                PaymentAttempt.PaymentStatus.CREATED);
        PaymentAttempt thirdWithoutEvents = attempt("PAYPAL_SANDBOX",
                PaymentAttempt.PaymentStatus.UNKNOWN);
        PaymentAttempt simulatedPendingAttempt = attempt("PAYPAL_SANDBOX",
                PaymentAttempt.PaymentStatus.PENDING);
        PaymentAttempt legacyAttempt = attempt("INVALID_PROVIDER",
                PaymentAttempt.PaymentStatus.FAILED);
        List<PaymentAttempt> attempts = List.of(
                attempt, secondWithoutEvents, thirdWithoutEvents,
                simulatedPendingAttempt, legacyAttempt);
        PaymentEvent simulatedPending = event(
                "PAYMENT.PENDING",
                PaymentEvent.SourceType.SIMULATED,
                PaymentEvent.VerificationStatus.NOT_APPLICABLE,
                OffsetDateTime.now(),
                "{}");
        simulatedPending.setPaymentAttempt(simulatedPendingAttempt);

        when(paymentAttemptRepository.findByOrder_Id(order.getId())).thenReturn(attempts);
        when(paymentEventRepository.findByPaymentAttempt_IdInOrderByReceivedAtAsc(
                any())).thenReturn(List.of(simulatedPending));

        InvestigationResponse response = service.investigate(order.getId());

        assertEquals("WAIT", response.decision());
        assertEquals("INSUFFICIENT_OR_STALE_EVIDENCE", response.reason());
        assertEquals(5, response.evidence().attempts().size());
        assertEquals(1, response.evidence().attempts().stream()
                .mapToInt(evidence -> evidence.events().size())
                .sum());
        assertEquals("INVALID_PROVIDER",
                response.evidence().attempts().stream()
                        .filter(evidence -> "INVALID_PROVIDER".equals(evidence.provider()))
                        .findFirst()
                        .orElseThrow()
                        .provider());
        assertEquals(0, response.evidence().attempts().stream()
                .filter(evidence -> "INVALID_PROVIDER".equals(evidence.provider()))
                .findFirst()
                .orElseThrow()
                .events().size());
        assertEquals("SIMULATED",
                response.evidence().attempts().stream()
                        .flatMap(evidence -> evidence.events().stream())
                        .findFirst()
                        .orElseThrow()
                        .sourceType());
        assertFalse(response.evidence().attempts().stream()
                .flatMap(evidence -> evidence.events().stream())
                .anyMatch(eventEvidence -> eventEvidence.trustedPayPalEvidence()));
    }

    @Test
    void unsupportedLegacyProviderBlocksRetryDespiteFreshVerifiedPaypalDenial() {
        PaymentEvent freshDenial = event(
                "PAYMENT.CAPTURE.DENIED",
                PaymentEvent.SourceType.PROVIDER,
                PaymentEvent.VerificationStatus.VERIFIED,
                OffsetDateTime.now().minusMinutes(1),
                "{}");
        PaymentAttempt legacyAttempt = attempt("INVALID_PROVIDER",
                PaymentAttempt.PaymentStatus.FAILED);
        when(paymentAttemptRepository.findByOrder_Id(order.getId()))
                .thenReturn(List.of(attempt, legacyAttempt));
        when(paymentEventRepository.findByPaymentAttempt_IdInOrderByReceivedAtAsc(
                any())).thenReturn(List.of(freshDenial));

        InvestigationResponse response = service.investigate(order.getId());

        assertEquals("WAIT", response.decision());
        assertEquals("INSUFFICIENT_OR_STALE_EVIDENCE", response.reason());
        assertEquals(2, response.evidence().attempts().size());
        assertEquals(0, response.evidence().attempts().stream()
                .filter(evidence -> "INVALID_PROVIDER".equals(evidence.provider()))
                .findFirst()
                .orElseThrow()
                .events().size());
    }

    @Test
    void simulatedOrUnverifiedEventsPreventRetryEligibility() {
        when(paymentEventRepository.findByPaymentAttempt_IdInOrderByReceivedAtAsc(
                List.of(attempt.getId()))).thenReturn(List.of(
                event("PAYMENT.CAPTURE.DENIED", PaymentEvent.SourceType.PROVIDER,
                        PaymentEvent.VerificationStatus.VERIFIED,
                        OffsetDateTime.now().minusMinutes(1), "{}"),
                event("PAYMENT.CAPTURE.DENIED", PaymentEvent.SourceType.SIMULATED,
                        PaymentEvent.VerificationStatus.NOT_APPLICABLE,
                        OffsetDateTime.now(), "{}")));

        assertEquals("WAIT", service.investigate(order.getId()).decision());
    }

    @Test
    void staleProviderTimestampBlocksRetryEvenWhenRecentlyReceived() {
        PaymentEvent denial = event("PAYMENT.CAPTURE.DENIED",
                PaymentEvent.SourceType.PROVIDER,
                PaymentEvent.VerificationStatus.VERIFIED,
                OffsetDateTime.now(),
                "{\"create_time\":\"" + OffsetDateTime.now().minusMinutes(16) + "\"}");
        when(paymentEventRepository.findByPaymentAttempt_IdInOrderByReceivedAtAsc(
                List.of(attempt.getId()))).thenReturn(List.of(denial));

        assertEquals("WAIT", service.investigate(order.getId()).decision());
    }

    @Test
    void malformedProviderTimestampIsNotReplacedByReceiptTime() {
        PaymentEvent denial = event("PAYMENT.CAPTURE.DENIED",
                PaymentEvent.SourceType.PROVIDER,
                PaymentEvent.VerificationStatus.VERIFIED,
                OffsetDateTime.now(),
                "{\"create_time\":\"not-a-timestamp\"}");
        when(paymentEventRepository.findByPaymentAttempt_IdInOrderByReceivedAtAsc(
                List.of(attempt.getId()))).thenReturn(List.of(denial));

        InvestigationResponse response = service.investigate(order.getId());

        assertEquals("WAIT", response.decision());
        assertEquals("STALE_OR_UNKNOWN",
                response.evidence().attempts().get(0).events().get(0).freshness());
        assertEquals("PROVIDER_CREATE_TIME",
                response.evidence().attempts().get(0).events().get(0).timestampSource());
    }

    @Test
    void denialAndCompletionEvidenceRequiresReconciliation() {
        when(paymentEventRepository.findByPaymentAttempt_IdInOrderByReceivedAtAsc(
                List.of(attempt.getId()))).thenReturn(List.of(
                event("PAYMENT.CAPTURE.DENIED", PaymentEvent.SourceType.PROVIDER,
                        PaymentEvent.VerificationStatus.VERIFIED,
                        OffsetDateTime.now().minusMinutes(2), "{}"),
                event("PAYMENT.CAPTURE.COMPLETED", PaymentEvent.SourceType.PROVIDER,
                        PaymentEvent.VerificationStatus.VERIFIED,
                        OffsetDateTime.now().minusMinutes(1), "{}")));

        InvestigationResponse response = service.investigate(order.getId());

        assertEquals("RECONCILE", response.decision());
        assertEquals("CONFLICTING_PAYMENT_EVIDENCE", response.reason());
    }

    @Test
    void paidOrderOrLaterVerifiedEventConflictingWithDenialRequiresReconciliation() {
        OffsetDateTime deniedAt = OffsetDateTime.now().minusMinutes(2);
        PaymentEvent denial = event("PAYMENT.CAPTURE.DENIED",
                PaymentEvent.SourceType.PROVIDER,
                PaymentEvent.VerificationStatus.VERIFIED,
                deniedAt,
                "{}");
        when(paymentEventRepository.findByPaymentAttempt_IdInOrderByReceivedAtAsc(
                List.of(attempt.getId()))).thenReturn(List.of(denial));
        order.setOrderStatus(MerchantOrder.OrderStatus.PAID);
        assertEquals("RECONCILE", service.investigate(order.getId()).decision());

        order.setOrderStatus(MerchantOrder.OrderStatus.FAILED);
        PaymentEvent later = event("PAYMENT.CAPTURE.PENDING",
                PaymentEvent.SourceType.PROVIDER,
                PaymentEvent.VerificationStatus.VERIFIED,
                deniedAt.plusMinutes(1),
                "{}");
        when(paymentEventRepository.findByPaymentAttempt_IdInOrderByReceivedAtAsc(
                List.of(attempt.getId()))).thenReturn(List.of(denial, later));
        assertEquals("RECONCILE", service.investigate(order.getId()).decision());
    }

    @Test
    void completedProviderPaymentConflictsWithNonPaidMerchantOrder() {
        when(paymentEventRepository.findByPaymentAttempt_IdInOrderByReceivedAtAsc(
                List.of(attempt.getId()))).thenReturn(List.of(event(
                "PAYMENT.CAPTURE.COMPLETED",
                PaymentEvent.SourceType.PROVIDER,
                PaymentEvent.VerificationStatus.VERIFIED,
                OffsetDateTime.now().minusMinutes(1),
                "{}")));

        for (MerchantOrder.OrderStatus status : List.of(
                MerchantOrder.OrderStatus.CREATED,
                MerchantOrder.OrderStatus.PENDING,
                MerchantOrder.OrderStatus.FAILED,
                MerchantOrder.OrderStatus.CANCELLED)) {
            order.setOrderStatus(status);
            assertEquals("RECONCILE", service.investigate(order.getId()).decision());
        }
    }

    @Test
    void pendingOrderWithOnlyTerminalDeniedAttemptsRequiresReconciliation() {
        order.setOrderStatus(MerchantOrder.OrderStatus.PENDING);
        when(paymentEventRepository.findByPaymentAttempt_IdInOrderByReceivedAtAsc(
                List.of(attempt.getId()))).thenReturn(List.of(event(
                "PAYMENT.CAPTURE.DENIED",
                PaymentEvent.SourceType.PROVIDER,
                PaymentEvent.VerificationStatus.VERIFIED,
                OffsetDateTime.now().minusMinutes(1),
                "{}")));

        assertEquals("RECONCILE", service.investigate(order.getId()).decision());
    }

    @Test
    void pendingOrUnknownVerifiedEventsPreventRetryEligibility() {
        when(paymentEventRepository.findByPaymentAttempt_IdInOrderByReceivedAtAsc(
                List.of(attempt.getId()))).thenReturn(List.of(
                event("PAYMENT.CAPTURE.PENDING", PaymentEvent.SourceType.PROVIDER,
                        PaymentEvent.VerificationStatus.VERIFIED,
                        OffsetDateTime.now().minusMinutes(2), "{}"),
                event("PAYMENT.CAPTURE.DENIED", PaymentEvent.SourceType.PROVIDER,
                        PaymentEvent.VerificationStatus.VERIFIED,
                        OffsetDateTime.now().minusMinutes(1), "{}")));

        assertEquals("WAIT", service.investigate(order.getId()).decision());

        when(paymentEventRepository.findByPaymentAttempt_IdInOrderByReceivedAtAsc(
                List.of(attempt.getId()))).thenReturn(List.of(
                event("PAYMENT.CAPTURE.OTHER", PaymentEvent.SourceType.PROVIDER,
                        PaymentEvent.VerificationStatus.VERIFIED,
                        OffsetDateTime.now().minusMinutes(1), "{}")));
        assertEquals("WAIT", service.investigate(order.getId()).decision());
    }

    @Test
    void historyRecordsThePreviousRunDecision() {
        Investigation previous = new Investigation();
        previous.setDecision(Investigation.Decision.WAIT);
        when(investigationRepository.findFirstByOrder_IdOrderByCreatedAtDescIdDesc(order.getId()))
                .thenReturn(Optional.of(previous));

        service.investigate(order.getId());

        ArgumentCaptor<InvestigationHistory> historyCaptor =
                ArgumentCaptor.forClass(InvestigationHistory.class);
        verify(investigationHistoryRepository).save(historyCaptor.capture());
        assertEquals(Investigation.Decision.WAIT,
                historyCaptor.getValue().getPreviousDecision());
    }

    @Test
    void successfulPaymentIsWaitAndDoesNotAuthorizeRetry() {
        order.setOrderStatus(MerchantOrder.OrderStatus.PAID);
        attempt.setPaymentStatus(PaymentAttempt.PaymentStatus.SUCCEEDED);
        when(paymentEventRepository.findByPaymentAttempt_IdInOrderByReceivedAtAsc(
                List.of(attempt.getId()))).thenReturn(List.of(
                event("PAYMENT.CAPTURE.COMPLETED", PaymentEvent.SourceType.PROVIDER,
                        PaymentEvent.VerificationStatus.VERIFIED,
                        OffsetDateTime.now(), "{}")));

        InvestigationResponse response = service.investigate(order.getId());

        assertEquals("WAIT", response.decision());
        assertEquals("PAYMENT_CONFIRMED", response.reason());
    }

    @Test
    void aSuccessfulLaterAttemptPreventsRetryAfterAnEarlierDenial() {
        order.setOrderStatus(MerchantOrder.OrderStatus.PAID);
        PaymentAttempt successfulAttempt = new PaymentAttempt();
        successfulAttempt.setId(UUID.randomUUID());
        successfulAttempt.setOrder(order);
        successfulAttempt.setProvider("PAYPAL_SANDBOX");
        successfulAttempt.setPaymentStatus(PaymentAttempt.PaymentStatus.SUCCEEDED);
        when(paymentAttemptRepository.findByOrder_Id(order.getId()))
                .thenReturn(List.of(attempt, successfulAttempt));
        PaymentEvent denial = event("PAYMENT.CAPTURE.DENIED",
                PaymentEvent.SourceType.PROVIDER,
                PaymentEvent.VerificationStatus.VERIFIED,
                OffsetDateTime.now().minusMinutes(2),
                "{}");
        PaymentEvent completion = event("PAYMENT.CAPTURE.COMPLETED",
                PaymentEvent.SourceType.PROVIDER,
                PaymentEvent.VerificationStatus.VERIFIED,
                OffsetDateTime.now().minusMinutes(1),
                "{}");
        completion.setPaymentAttempt(successfulAttempt);
        when(paymentEventRepository.findByPaymentAttempt_IdInOrderByReceivedAtAsc(any()))
                .thenReturn(List.of(denial, completion));

        InvestigationResponse response = service.investigate(order.getId());

        assertEquals("WAIT", response.decision());
        assertEquals("PAYMENT_CONFIRMED", response.reason());
    }

    @Test
    void pendingOrCancelledMerchantOrderDoesNotBecomeRetryEligible() {
        PaymentEvent denial = event("PAYMENT.CAPTURE.DENIED",
                PaymentEvent.SourceType.PROVIDER,
                PaymentEvent.VerificationStatus.VERIFIED,
                OffsetDateTime.now().minusMinutes(1),
                "{}");
        when(paymentEventRepository.findByPaymentAttempt_IdInOrderByReceivedAtAsc(
                List.of(attempt.getId()))).thenReturn(List.of(denial));

        order.setOrderStatus(MerchantOrder.OrderStatus.PENDING);
        assertEquals("RECONCILE", service.investigate(order.getId()).decision());

        order.setOrderStatus(MerchantOrder.OrderStatus.CANCELLED);
        assertEquals("WAIT", service.investigate(order.getId()).decision());
        assertEquals("MERCHANT_ORDER_CANCELLED", service.investigate(order.getId()).reason());
    }

    @Test
    void fallsBackToReceivedAtOnlyWhenProviderTimestampIsAbsent() {
        PaymentEvent denial = event("PAYMENT.CAPTURE.DENIED",
                PaymentEvent.SourceType.PROVIDER,
                PaymentEvent.VerificationStatus.VERIFIED,
                OffsetDateTime.now().minusMinutes(1),
                "{}");
        when(paymentEventRepository.findByPaymentAttempt_IdInOrderByReceivedAtAsc(
                List.of(attempt.getId()))).thenReturn(List.of(denial));

        InvestigationResponse response = service.investigate(order.getId());

        assertEquals("RETRY_ELIGIBLE", response.decision());
        assertEquals("RECEIVED_AT_FALLBACK",
                response.evidence().attempts().get(0).events().get(0).timestampSource());
    }

    @Test
    void missingOrderFailsWithoutWritingInvestigation() {
        UUID missingId = UUID.randomUUID();
        when(merchantOrderRepository.findById(missingId)).thenReturn(Optional.empty());

        assertThrows(MerchantOrderNotFoundException.class, () -> service.investigate(missingId));

        verify(investigationRepository, never()).save(any());
        verify(investigationHistoryRepository, never()).save(any());
    }

    private PaymentEvent event(
            String type,
            PaymentEvent.SourceType source,
            PaymentEvent.VerificationStatus verification,
            OffsetDateTime receivedAt,
            String payload) {
        PaymentEvent event = new PaymentEvent();
        event.setId(UUID.randomUUID());
        event.setPaymentAttempt(attempt);
        event.setProvider("PAYPAL_SANDBOX");
        event.setEventType(type);
        event.setSourceType(source);
        event.setVerificationStatus(verification);
        event.setProcessingStatus(PaymentEvent.ProcessingStatus.PROCESSED);
        event.setReceivedAt(receivedAt);
        event.setPayload(payload);
        return event;
    }

    private PaymentAttempt attempt(
            String provider, PaymentAttempt.PaymentStatus paymentStatus) {
        PaymentAttempt additionalAttempt = new PaymentAttempt();
        additionalAttempt.setId(UUID.randomUUID());
        additionalAttempt.setOrder(order);
        additionalAttempt.setProvider(provider);
        additionalAttempt.setPaymentStatus(paymentStatus);
        return additionalAttempt;
    }
}
