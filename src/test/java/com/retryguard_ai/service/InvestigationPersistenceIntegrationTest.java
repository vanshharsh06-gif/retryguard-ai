package com.retryguard_ai.service;

import com.retryguard_ai.dto.CreateOrderRequest;
import com.retryguard_ai.dto.CreatePaymentAttemptRequest;
import com.retryguard_ai.dto.InvestigationResponse;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.annotation.Rollback;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

@SpringBootTest
@Transactional
@Rollback
class InvestigationPersistenceIntegrationTest {

    @Autowired private MerchantOrderService merchantOrderService;
    @Autowired private PaymentAttemptService paymentAttemptService;
    @Autowired private InvestigationService investigationService;
    @Autowired private EntityManager entityManager;

    @Test
    void persistsFindingsAndReadsInvestigationListAndChronologicalHistoryFromPostgres() {
        var order = merchantOrderService.createOrder(new CreateOrderRequest(
                "investigation-findings-" + UUID.randomUUID(), new BigDecimal("31.25"), "USD"));
        paymentAttemptService.createPaymentAttempt(
                order.getId(), new CreatePaymentAttemptRequest("PAYPAL_SANDBOX"));

        InvestigationResponse first = investigationService.investigate(order.getId());
        InvestigationResponse second = investigationService.investigate(order.getId());
        entityManager.flush();
        entityManager.clear();

        InvestigationResponse reloaded = investigationService.getInvestigation(first.id());
        var investigations = investigationService.getInvestigationsForOrder(order.getId());
        var history = investigationService.getInvestigationHistory(second.id());

        assertFalse(reloaded.findings().isEmpty());
        assertEquals("MISSING_EVIDENCE", reloaded.findings().get(0).code().name());
        assertEquals(2, investigations.size());
        assertEquals(second.id(), investigations.get(0).id());
        assertEquals(first.id(), investigations.get(1).id());
        assertEquals(1, history.size());
        assertEquals("CREATED", history.get(0).action());
        assertEquals(first.decision(), history.get(0).previousDecision());
        assertEquals(second.decision(), history.get(0).newDecision());
        assertTrue(history.get(0).createdAt().equals(first.createdAt())
                || history.get(0).createdAt().isAfter(first.createdAt()));
    }
}