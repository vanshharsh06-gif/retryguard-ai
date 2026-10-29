package com.retryguard_ai.service;

import com.retryguard_ai.ai.ExplanationInput;
import com.retryguard_ai.ai.ExplanationProvider;
import com.retryguard_ai.ai.ExplanationProviderFailureException;
import com.retryguard_ai.dto.InvestigationExplanationResponse;
import com.retryguard_ai.entity.Investigation;
import com.retryguard_ai.exception.InvestigationNotFoundException;
import com.retryguard_ai.repository.InvestigationRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import tools.jackson.databind.ObjectMapper;

import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class InvestigationExplanationServiceTest {

    @Mock private InvestigationRepository investigationRepository;
    @Mock private ExplanationProvider explanationProvider;
    @Mock private InvestigationExplanationPersistenceService persistenceService;

    private InvestigationExplanationService service;
    private Investigation investigation;

    @BeforeEach
    void setUp() {
        service = new InvestigationExplanationService(
                investigationRepository,
                explanationProvider,
                persistenceService,
                new ObjectMapper());
        investigation = new Investigation();
        investigation.setId(UUID.randomUUID());
        investigation.setDecision(Investigation.Decision.WAIT);
        investigation.setDecisionReason("INSUFFICIENT_EVIDENCE");
        investigation.setRulesVersion("1.0");
        investigation.setExplanation("Original deterministic explanation");
        investigation.setEvidenceSnapshot("""
                {"orderStatus":"FAILED","investigatedAt":"2026-10-17T10:00:00Z",
                 "freshnessThresholdMinutes":15,"attempts":[{"paymentAttemptId":"%s",
                 "provider":"PAYPAL_SANDBOX","paymentStatus":"FAILED","events":[{
                 "eventId":"%s","eventType":"PAYMENT.CAPTURE.DENIED","provider":"PAYPAL_SANDBOX",
                 "sourceType":"PROVIDER","verificationStatus":"VERIFIED",
                 "receivedAt":"2026-10-17T10:00:00Z","providerEventAt":"2026-10-17T10:00:00Z",
                 "effectiveEvidenceAt":"2026-10-17T10:00:00Z",
                 "timestampSource":"PROVIDER_CREATE_TIME","freshness":"FRESH",
                 "trustedPayPalEvidence":true,"payload":"private raw payload",
                 "providerPaymentReference":"private-provider-reference"},
                 {"eventId":"%s","eventType":"PAYMENT.CAPTURE.RESPONSE",
                 "provider":"PAYPAL_SANDBOX","sourceType":"PROVIDER",
                 "verificationStatus":"NOT_APPLICABLE","trustedPayPalEvidence":false}]}]}
                """.formatted(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID()));
        investigation.setFindings("""
                [{"code":"PROVIDER_DENIAL","title":"Verified provider denial",
                  "description":"Private finding description","rootCauseCategory":"PROVIDER_DENIAL",
                  "severity":"HIGH","evidence":[{"type":"PAYMENT_EVENT","id":"%s"}],
                  "recommendedAction":"Private operator action","rootCauseUncertain":true}]
                """.formatted(UUID.randomUUID()));
        lenient().when(investigationRepository.findById(investigation.getId()))
                .thenReturn(Optional.of(investigation));
    }

    @Test
    void persistsGeminiExplanationWithoutChangingDecisionAndSendsOnlyAllowlistedEvidence() {
        when(explanationProvider.explain(any())).thenReturn(
                "The investigation is waiting because its evidence must be assessed conservatively.");

        InvestigationExplanationResponse response = service.explain(investigation.getId());

        assertEquals("GEMINI", response.source().name());
        assertEquals("WAIT", response.decision());
        assertEquals("INSUFFICIENT_EVIDENCE", response.reason());
        assertEquals("1.0", response.rulesVersion());
        assertEquals(
                "The investigation is waiting because its evidence must be assessed conservatively.",
                response.explanation());
        assertEquals(Investigation.Decision.WAIT, investigation.getDecision());
        ArgumentCaptor<ExplanationInput> inputCaptor =
                ArgumentCaptor.forClass(ExplanationInput.class);
        verify(explanationProvider).explain(inputCaptor.capture());
        ExplanationInput input = inputCaptor.getValue();
        assertEquals("WAIT", input.decision());
        assertEquals("FAILED", input.orderStatus());
        assertEquals("FRESH", input.attempts().get(0).events().get(0).freshness());
        assertEquals("PAYMENT.CAPTURE.RESPONSE",
                input.attempts().get(0).events().get(1).eventType());
        assertEquals("PROVIDER",
                input.attempts().get(0).events().get(1).sourceType());
        assertFalse(input.attempts().get(0).events().get(1).trusted());
        assertEquals("PROVIDER_DENIAL", input.findings().get(0).code());
        assertEquals("PROVIDER_DENIAL", input.findings().get(0).category());
        assertEquals("HIGH", input.findings().get(0).severity());
        assertEquals(java.util.List.of("PAYMENT_EVENT"),
                input.findings().get(0).evidenceTypes());
        String serializedInput = new ObjectMapper().writeValueAsString(input);
        assertFalse(serializedInput.contains(investigation.getId().toString()));
        assertFalse(serializedInput.contains("private raw payload"));
        assertFalse(serializedInput.contains("private-provider-reference"));
        assertFalse(serializedInput.contains("Private finding description"));
        assertFalse(serializedInput.contains("Private operator action"));
        var invocationOrder = inOrder(explanationProvider, persistenceService);
        invocationOrder.verify(explanationProvider).explain(any());
        invocationOrder
                .verify(persistenceService)
                .saveExplanation(investigation.getId(), response.explanation());
    }

    @Test
    void providerFailureUsesAndPersistsExplicitSafeFallback() {
        when(explanationProvider.explain(any()))
                .thenThrow(new ExplanationProviderFailureException());

        InvestigationExplanationResponse response = service.explain(investigation.getId());

        assertEquals(InvestigationExplanationResponse.Source.DETERMINISTIC_FALLBACK,
                response.source());
        assertEquals("WAIT", response.decision());
        assertEquals("INSUFFICIENT_EVIDENCE", response.reason());
        assertEquals(Investigation.Decision.WAIT, investigation.getDecision());
        org.junit.jupiter.api.Assertions.assertTrue(
                response.explanation().contains("does not authorize a payment action"));
        verify(persistenceService).saveExplanation(
                investigation.getId(), response.explanation());
    }

    @Test
    void unsafeOrOversizedTextFallsBackRatherThanReturningActionInstructions() {
        when(explanationProvider.explain(any()))
                .thenReturn("Please retry the payment now.", "x".repeat(1201));

        InvestigationExplanationResponse unsafeResponse = service.explain(investigation.getId());
        InvestigationExplanationResponse oversizedResponse = service.explain(investigation.getId());

        assertEquals(InvestigationExplanationResponse.Source.DETERMINISTIC_FALLBACK,
                unsafeResponse.source());
        assertEquals(InvestigationExplanationResponse.Source.DETERMINISTIC_FALLBACK,
                oversizedResponse.source());
        assertEquals(Investigation.Decision.WAIT, investigation.getDecision());
        verify(persistenceService, times(2)).saveExplanation(
                investigation.getId(), oversizedResponse.explanation());
    }

    @Test
    void unknownInvestigationDoesNotCallProviderOrPersist() {
        UUID missingId = UUID.randomUUID();
        when(investigationRepository.findById(missingId)).thenReturn(Optional.empty());

        assertThrows(InvestigationNotFoundException.class, () -> service.explain(missingId));

        verify(explanationProvider, never()).explain(any());
        verify(persistenceService, never()).saveExplanation(any(), any());
    }

    @Test
    void persistenceServiceChangesOnlyTheExplanationField() {
        when(investigationRepository.updateExplanationById(
                investigation.getId(), "Persisted explanation")).thenReturn(1);
        InvestigationExplanationPersistenceService actualPersistenceService =
                new InvestigationExplanationPersistenceService(investigationRepository);

        actualPersistenceService.saveExplanation(investigation.getId(), "Persisted explanation");

        assertEquals(Investigation.Decision.WAIT, investigation.getDecision());
        assertEquals("INSUFFICIENT_EVIDENCE", investigation.getDecisionReason());
        assertEquals("1.0", investigation.getRulesVersion());
        verify(investigationRepository).updateExplanationById(
                investigation.getId(), "Persisted explanation");
    }
}
