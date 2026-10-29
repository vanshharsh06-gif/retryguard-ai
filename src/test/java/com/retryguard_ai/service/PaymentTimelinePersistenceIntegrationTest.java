package com.retryguard_ai.service;

import com.retryguard_ai.dto.CreateOrderRequest;
import com.retryguard_ai.dto.CreatePaymentAttemptRequest;
import com.retryguard_ai.dto.RecordPaymentEventRequest;
import com.retryguard_ai.dto.OrderTimelineResponse;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.annotation.Rollback;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

@SpringBootTest
@Transactional
@Rollback
class PaymentTimelinePersistenceIntegrationTest {

    @Autowired
    private MerchantOrderService merchantOrderService;

    @Autowired
    private PaymentAttemptService paymentAttemptService;

    @Autowired
    private PaymentEventService paymentEventService;

    @Autowired
    private PaymentTimelineService paymentTimelineService;

    @Autowired
    private EntityManager entityManager;

    @Test
    void reconstructsChronologicalTimelineFromPostgresRecords() {
        var order = merchantOrderService.createOrder(new CreateOrderRequest(
                "timeline-integration-" + UUID.randomUUID(), new BigDecimal("27.40"), "USD"));
        entityManager.flush();
        var attempt = paymentAttemptService.createPaymentAttempt(
                order.getId(), new CreatePaymentAttemptRequest("PAYPAL_SANDBOX"));
        entityManager.flush();
        paymentEventService.recordSimulatedEvent(
                attempt.getId(),
                new RecordPaymentEventRequest("timeline-event-" + UUID.randomUUID(),
                        "PAYMENT.PENDING", Map.of("state", "pending")));
        entityManager.flush();
        entityManager.clear();

        OrderTimelineResponse timeline = paymentTimelineService.getOrderTimeline(order.getId());

        assertEquals(3, timeline.entries().size());
        assertEquals("ORDER_CREATED", timeline.entries().get(0).entryType());
        assertEquals("PAYMENT_ATTEMPT_CREATED", timeline.entries().get(1).entryType());
        assertEquals("PAYMENT_EVENT_RECEIVED", timeline.entries().get(2).entryType());
        assertTrue(isChronological(timeline));
        assertEquals("SIMULATED", timeline.entries().get(2).sourceType());
        assertEquals("NOT_APPLICABLE", timeline.entries().get(2).verificationStatus());
        assertEquals("RECEIVED", timeline.entries().get(2).processingStatus());
    }

    private boolean isChronological(OrderTimelineResponse timeline) {
        for (int index = 1; index < timeline.entries().size(); index++) {
            OffsetDateTime previous = timeline.entries().get(index - 1).occurredAt();
            OffsetDateTime current = timeline.entries().get(index).occurredAt();
            if (previous.isAfter(current)) {
                return false;
            }
        }
        return true;
    }
}
