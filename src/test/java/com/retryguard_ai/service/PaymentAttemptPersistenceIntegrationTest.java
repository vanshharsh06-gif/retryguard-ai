package com.retryguard_ai.service;

import com.retryguard_ai.dto.CreateOrderRequest;
import com.retryguard_ai.dto.CreatePaymentAttemptRequest;
import com.retryguard_ai.entity.PaymentAttempt;
import com.retryguard_ai.repository.PaymentAttemptRepository;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.annotation.Rollback;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

@SpringBootTest
@Transactional
@Rollback
class PaymentAttemptPersistenceIntegrationTest {

    @Autowired
    private MerchantOrderService merchantOrderService;

    @Autowired
    private PaymentAttemptService paymentAttemptService;

    @Autowired
    private PaymentAttemptRepository paymentAttemptRepository;

    @Autowired
    private EntityManager entityManager;

    @Test
    void createPaymentAttemptPersistsAndReloadsWithItsOrder() {
        String merchantOrderReference = "attempt-integration-" + UUID.randomUUID();
        var order = merchantOrderService.createOrder(
                new CreateOrderRequest(merchantOrderReference, new BigDecimal("42.75"), "USD"));

        PaymentAttempt paymentAttempt = paymentAttemptService.createPaymentAttempt(
                order.getId(), new CreatePaymentAttemptRequest("PAYPAL_SANDBOX"));
        entityManager.flush();
        UUID paymentAttemptId = paymentAttempt.getId();
        entityManager.clear();

        PaymentAttempt persistedAttempt = paymentAttemptRepository.findById(paymentAttemptId)
                .orElseThrow();

        assertNotNull(persistedAttempt.getId());
        assertEquals(order.getId(), persistedAttempt.getOrder().getId());
        assertEquals("PAYPAL_SANDBOX", persistedAttempt.getProvider());
        assertEquals(new BigDecimal("42.75"), persistedAttempt.getAmount());
        assertEquals("USD", persistedAttempt.getCurrency());
        assertEquals(PaymentAttempt.PaymentStatus.CREATED, persistedAttempt.getPaymentStatus());
        assertNotNull(persistedAttempt.getCreatedAt());
        assertNotNull(persistedAttempt.getUpdatedAt());
    }
}
