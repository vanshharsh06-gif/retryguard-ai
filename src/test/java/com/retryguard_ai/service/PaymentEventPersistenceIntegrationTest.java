package com.retryguard_ai.service;

import com.retryguard_ai.dto.CreateOrderRequest;
import com.retryguard_ai.dto.CreatePaymentAttemptRequest;
import com.retryguard_ai.dto.RecordPaymentEventRequest;
import com.retryguard_ai.entity.MerchantOrder;
import com.retryguard_ai.entity.PaymentAttempt;
import com.retryguard_ai.entity.PaymentEvent;
import com.retryguard_ai.repository.PaymentAttemptRepository;
import com.retryguard_ai.repository.PaymentEventRepository;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.annotation.Rollback;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

import java.math.BigDecimal;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

@SpringBootTest
@Transactional
@Rollback
class PaymentEventPersistenceIntegrationTest {

    @Autowired
    private MerchantOrderService merchantOrderService;

    @Autowired
    private PaymentAttemptService paymentAttemptService;

    @Autowired
    private PaymentEventService paymentEventService;

    @Autowired
    private PaymentEventRepository paymentEventRepository;

    @Autowired
    private PaymentAttemptRepository paymentAttemptRepository;

    @Autowired
    private PaypalWebhookEventProcessor paypalWebhookEventProcessor;

    @Autowired
    private PaymentTimelineService paymentTimelineService;

    @Autowired
    private EntityManager entityManager;

    @Autowired
    private ObjectMapper objectMapper;

    @Test
    void duplicateEventIdReturnsOriginalRecordWithoutCreatingAnother() throws Exception {
        var order = merchantOrderService.createOrder(new CreateOrderRequest(
                "event-integration-" + UUID.randomUUID(), new BigDecimal("42.75"), "USD"));
        var attempt = paymentAttemptService.createPaymentAttempt(
                order.getId(), new CreatePaymentAttemptRequest("PAYPAL_SANDBOX"));
        RecordPaymentEventRequest firstRequest = new RecordPaymentEventRequest(
                "event-" + UUID.randomUUID(), "PAYMENT.PENDING", Map.of("state", "pending"));

        PaymentEventCreationResult created =
                paymentEventService.recordSimulatedEvent(attempt.getId(), firstRequest);
        entityManager.flush();
        entityManager.clear();
        PaymentEvent persisted = paymentEventRepository.findById(created.event().getId())
                .orElseThrow();

        PaymentEventCreationResult duplicate = paymentEventService.recordSimulatedEvent(
                attempt.getId(),
                new RecordPaymentEventRequest(firstRequest.getProviderEventId(),
                        "PAYMENT.SUCCEEDED", Map.of("state", "succeeded")));

        assertEquals(true, created.created());
        assertEquals(false, duplicate.created());
        assertSame(persisted, duplicate.event());
        assertEquals(PaymentEvent.SourceType.SIMULATED, persisted.getSourceType());
        assertEquals(PaymentEvent.VerificationStatus.NOT_APPLICABLE,
                persisted.getVerificationStatus());
        assertEquals(PaymentEvent.ProcessingStatus.RECEIVED,
                persisted.getProcessingStatus());
        assertEquals("PAYMENT.PENDING", persisted.getEventType());
        Map<String, Object> persistedPayload = objectMapper.readValue(
                persisted.getPayload(), new TypeReference<>() { });
        assertEquals("pending", persistedPayload.get("state"));
        assertNotNull(persisted.getReceivedAt());
        assertEquals(1, paymentEventRepository
                .findByPaymentAttempt_Id(attempt.getId()).size());
    }

    @Test
    void verifiedCompletedCapturePersistsOnceAndUpdatesAttemptAndOrder() throws Exception {
        var order = merchantOrderService.createOrder(new CreateOrderRequest(
                "paypal-event-integration-" + UUID.randomUUID(),
                new BigDecimal("42.75"), "USD"));
        var attempt = paymentAttemptService.createPaymentAttempt(
                order.getId(), new CreatePaymentAttemptRequest("PAYPAL_SANDBOX"));
        String eventId = "WH-" + UUID.randomUUID();
        String payload = """
                {
                  "id": "%s",
                  "event_type": "PAYMENT.CAPTURE.COMPLETED",
                  "resource": {
                    "id": "CAP-%s",
                    "amount": {"currency_code": "USD", "value": "42.75"},
                    "supplementary_data": {"related_ids": {"order_id": "ORDER-%s"}}
                  }
                }
                """.formatted(eventId, UUID.randomUUID(), UUID.randomUUID());
        String captureId = objectMapper.readTree(payload)
                .get("resource").get("id").asString();
        String paypalOrderId = objectMapper.readTree(payload)
                .get("resource").get("supplementary_data").get("related_ids")
                .get("order_id").asString();
        attempt.setProviderPaymentReference(paypalOrderId);
        paymentAttemptRepository.save(attempt);
        entityManager.flush();

        boolean created = paypalWebhookEventProcessor.recordVerifiedEvent(
                objectMapper.readTree(payload), payload);
        entityManager.flush();
        entityManager.clear();

        PaymentAttempt updatedAttempt = paymentAttemptRepository.findById(attempt.getId())
                .orElseThrow();
        MerchantOrder updatedOrder = updatedAttempt.getOrder();
        PaymentEvent persistedEvent = paymentEventRepository
                .findByProviderAndProviderEventId("PAYPAL_SANDBOX", eventId)
                .orElseThrow();
        assertEquals(true, created);
        assertEquals(PaymentAttempt.PaymentStatus.SUCCEEDED, updatedAttempt.getPaymentStatus());
        assertEquals(captureId, updatedAttempt.getProviderCaptureReference());
        assertEquals(MerchantOrder.OrderStatus.PAID, updatedOrder.getOrderStatus());
        assertEquals(PaymentEvent.ProcessingStatus.PROCESSED, persistedEvent.getProcessingStatus());
        var timeline = paymentTimelineService.getOrderTimeline(order.getId());
        assertTrue(timeline.entries().stream().anyMatch(
                entry -> eventId.equals(entry.providerEventId())));

        boolean duplicate = paypalWebhookEventProcessor.recordVerifiedEvent(
                objectMapper.readTree(payload), payload);
        assertEquals(false, duplicate);
        assertEquals(1, paymentEventRepository.findByPaymentAttempt_Id(attempt.getId()).size());
        assertEquals(PaymentAttempt.PaymentStatus.SUCCEEDED,
                paymentAttemptRepository.findById(attempt.getId()).orElseThrow()
                        .getPaymentStatus());
    }
}
