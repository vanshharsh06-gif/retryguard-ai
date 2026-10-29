package com.retryguard_ai.service;

import com.retryguard_ai.dto.CreateOrderRequest;
import com.retryguard_ai.entity.MerchantOrder;
import com.retryguard_ai.repository.MerchantOrderRepository;
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
class MerchantOrderPersistenceIntegrationTest {

    @Autowired
    private MerchantOrderService merchantOrderService;

    @Autowired
    private MerchantOrderRepository merchantOrderRepository;

    @Autowired
    private EntityManager entityManager;

    @Test
    void createOrderPersistsOrderAndReloadsItFromPostgres() {
        String merchantOrderReference = "integration-" + UUID.randomUUID();
        CreateOrderRequest request = new CreateOrderRequest(
                merchantOrderReference, new BigDecimal("42.75"), "USD");

        merchantOrderService.createOrder(request);
        entityManager.flush();
        entityManager.clear();

        MerchantOrder persistedOrder = merchantOrderRepository
                .findByMerchantOrderReference(merchantOrderReference)
                .orElseThrow();

        assertNotNull(persistedOrder.getId());
        assertEquals(merchantOrderReference, persistedOrder.getMerchantOrderReference());
        assertEquals(new BigDecimal("42.75"), persistedOrder.getAmount());
        assertEquals("USD", persistedOrder.getCurrency());
        assertEquals(MerchantOrder.OrderStatus.CREATED, persistedOrder.getOrderStatus());
        assertNotNull(persistedOrder.getCreatedAt());
        assertNotNull(persistedOrder.getUpdatedAt());
    }
}
