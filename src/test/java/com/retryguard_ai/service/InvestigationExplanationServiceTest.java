package com.retryguard_ai.service;

import com.retryguard_ai.dto.InvestigationExplanationResponse;
import com.retryguard_ai.entity.Investigation;
import com.retryguard_ai.exception.InvestigationNotFoundException;
import com.retryguard_ai.repository.InvestigationRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import tools.jackson.databind.ObjectMapper;

import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class InvestigationExplanationServiceTest {

    @Mock private InvestigationRepository investigationRepository;
    @Mock private InvestigationExplanationPersistenceService persistenceService;

    private InvestigationExplanationService service;
    private Investigation investigation;

    @BeforeEach
    void setUp() {
        service = new InvestigationExplanationService(
                investigationRepository,
                persistenceService,
                new ObjectMapper());
        investigation = new Investigation();
        investigation.setId(UUID.randomUUID());
        investigation.setDecision(Investigation.Decision.WAIT);
        investigation.setDecisionReason("INSUFFICIENT_EVIDENCE");
        investigation.setRulesVersion("1.0");
        investigation.setExplanation("Original deterministic explanation");
        investigation.setEvidenceSnapshot("{}");
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
    void buildsDeterministicExplanationFromDecisionReasonAndFindingsWithoutChangingDecision() {
        InvestigationExplanationResponse response = service.explain(investigation.getId());

        assertEquals(InvestigationExplanationResponse.Source.DETERMINISTIC, response.source());
        assertEquals("WAIT", response.decision());
        assertEquals("INSUFFICIENT_EVIDENCE", response.reason());
        assertEquals("1.0", response.rulesVersion());
        // Decision must never be changed by producing an explanation.
        assertEquals(Investigation.Decision.WAIT, investigation.getDecision());

        String explanation = response.explanation();
        assertTrue(explanation.contains("WAIT"), "explanation should cite the decision");
        assertTrue(explanation.contains("INSUFFICIENT_EVIDENCE"), "explanation should cite the reason");
        assertTrue(explanation.contains("1.0"), "explanation should cite the rules version");
        assertTrue(explanation.contains("PROVIDER_DENIAL"), "explanation should summarize findings");
        assertTrue(explanation.contains("HIGH"), "explanation should include finding severity");
        assertTrue(explanation.contains("does not authorize a payment action"),
                "explanation must carry the no-action safety statement");

        verify(persistenceService).saveExplanation(investigation.getId(), explanation);
    }

    @Test
    void explanationNeverContainsActionInstructions() {
        InvestigationExplanationResponse response = service.explain(investigation.getId());

        String lower = response.explanation().toLowerCase();
        assertTrue(lower.contains("does not authorize a payment action"));
        assertTrue(!lower.contains("please retry")
                && !lower.contains("you should retry")
                && !lower.contains("proceed to capture")
                && !lower.contains("initiate a refund"));
    }

    @Test
    void handlesInvestigationWithNoFindings() {
        investigation.setFindings("[]");

        InvestigationExplanationResponse response = service.explain(investigation.getId());

        assertEquals(InvestigationExplanationResponse.Source.DETERMINISTIC, response.source());
        assertTrue(response.explanation().contains("No evidence-based findings were recorded"));
        verify(persistenceService).saveExplanation(investigation.getId(), response.explanation());
    }

    @Test
    void handlesUnparseableFindingsByFallingBackToDecisionOnlyExplanation() {
        investigation.setFindings("not valid json");

        InvestigationExplanationResponse response = service.explain(investigation.getId());

        assertEquals(InvestigationExplanationResponse.Source.DETERMINISTIC, response.source());
        assertTrue(response.explanation().contains("WAIT"));
        assertTrue(response.explanation().contains("does not authorize a payment action"));
        verify(persistenceService).saveExplanation(investigation.getId(), response.explanation());
    }

    @Test
    void unknownInvestigationDoesNotPersist() {
        UUID missingId = UUID.randomUUID();
        when(investigationRepository.findById(missingId)).thenReturn(Optional.empty());

        assertThrows(InvestigationNotFoundException.class, () -> service.explain(missingId));

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
                eq(investigation.getId()), eq("Persisted explanation"));
    }
}
