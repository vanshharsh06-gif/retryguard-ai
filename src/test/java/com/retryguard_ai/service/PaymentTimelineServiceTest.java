package com.retryguard_ai.service;

import com.retryguard_ai.dto.OrderTimelineResponse;
import com.retryguard_ai.entity.MerchantOrder;
import com.retryguard_ai.entity.PaymentAttempt;
import com.retryguard_ai.entity.PaymentEvent;
import com.retryguard_ai.exception.MerchantOrderNotFoundException;
import com.retryguard_ai.repository.MerchantOrderRepository;
import com.retryguard_ai.repository.PaymentAttemptRepository;
import com.retryguard_ai.repository.PaymentEventRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class PaymentTimelineServiceTest {

    @Mock
    private MerchantOrderRepository merchantOrderRepository;

    @Mock
    private PaymentAttemptRepository paymentAttemptRepository;

    @Mock
    private PaymentEventRepository paymentEventRepository;

    @InjectMocks
    private PaymentTimelineService paymentTimelineService;

    @Test
    void getOrderTimelineCombinesAndChronologicallySortsOrderAttemptAndEventEntries() {
        UUID orderId = UUID.randomUUID();
        UUID attemptId = UUID.randomUUID();
        OffsetDateTime orderCreatedAt = OffsetDateTime.parse("2026-10-17T10:00:00Z");
        OffsetDateTime attemptCreatedAt = orderCreatedAt.plusMinutes(1);
        OffsetDateTime eventReceivedAt = attemptCreatedAt.plusMinutes(1);

        MerchantOrder order = new MerchantOrder();
        order.setId(orderId);
        order.setMerchantOrderReference("order-ref");
        order.setAmount(new BigDecimal("18.25"));
        order.setCurrency("USD");
        order.setOrderStatus(MerchantOrder.OrderStatus.CREATED);
        order.setCreatedAt(orderCreatedAt);

        PaymentAttempt attempt = new PaymentAttempt();
        attempt.setId(attemptId);
        attempt.setOrder(order);
        attempt.setProvider("PAYPAL_SANDBOX");
        attempt.setAmount(order.getAmount());
        attempt.setCurrency(order.getCurrency());
        attempt.setPaymentStatus(PaymentAttempt.PaymentStatus.UNKNOWN);
        attempt.setCreatedAt(attemptCreatedAt);

        PaymentEvent event = new PaymentEvent();
        event.setId(UUID.randomUUID());
        event.setPaymentAttempt(attempt);
        event.setProvider("PAYPAL_SANDBOX");
        event.setProviderEventId("event-1");
        event.setEventType("PAYMENT.PENDING");
        event.setSourceType(PaymentEvent.SourceType.SIMULATED);
        event.setVerificationStatus(PaymentEvent.VerificationStatus.NOT_APPLICABLE);
        event.setProcessingStatus(PaymentEvent.ProcessingStatus.RECEIVED);
        event.setReceivedAt(eventReceivedAt);

        when(merchantOrderRepository.findById(orderId)).thenReturn(Optional.of(order));
        when(paymentAttemptRepository.findByOrder_IdOrderByCreatedAtAsc(orderId))
                .thenReturn(List.of(attempt));
        when(paymentEventRepository.findByPaymentAttempt_IdInOrderByReceivedAtAsc(List.of(attemptId)))
                .thenReturn(List.of(event));

        OrderTimelineResponse timeline = paymentTimelineService.getOrderTimeline(orderId);

        assertEquals(orderId, timeline.orderId());
        assertEquals("order-ref", timeline.merchantOrderReference());
        assertEquals(3, timeline.entries().size());
        assertEquals(List.of(
                        "ORDER_CREATED",
                        "PAYMENT_ATTEMPT_CREATED",
                        "PAYMENT_EVENT_RECEIVED"),
                timeline.entries().stream().map(entry -> entry.entryType()).toList());
        assertEquals(List.of(orderCreatedAt, attemptCreatedAt, eventReceivedAt),
                timeline.entries().stream().map(entry -> entry.occurredAt()).toList());
        assertEquals("UNKNOWN", timeline.entries().get(1).currentStatus());
        assertEquals("SIMULATED", timeline.entries().get(2).sourceType());
        assertEquals("NOT_APPLICABLE", timeline.entries().get(2).verificationStatus());
    }

    @Test
    void getOrderTimelineReturnsOrderOnlyWhenThereAreNoAttempts() {
        UUID orderId = UUID.randomUUID();
        MerchantOrder order = new MerchantOrder();
        order.setId(orderId);
        order.setMerchantOrderReference("order-ref");
        order.setOrderStatus(MerchantOrder.OrderStatus.CREATED);
        order.setCreatedAt(OffsetDateTime.parse("2026-10-17T10:00:00Z"));
        when(merchantOrderRepository.findById(orderId)).thenReturn(Optional.of(order));
        when(paymentAttemptRepository.findByOrder_IdOrderByCreatedAtAsc(orderId))
                .thenReturn(List.of());

        OrderTimelineResponse timeline = paymentTimelineService.getOrderTimeline(orderId);

        assertEquals(1, timeline.entries().size());
        assertEquals("ORDER_CREATED", timeline.entries().get(0).entryType());
        verify(paymentEventRepository, never())
                .findByPaymentAttempt_IdInOrderByReceivedAtAsc(List.of());
    }

    @Test
    void getOrderTimelineRejectsUnknownOrder() {
        UUID orderId = UUID.randomUUID();
        when(merchantOrderRepository.findById(orderId)).thenReturn(Optional.empty());

        assertThrows(MerchantOrderNotFoundException.class,
                () -> paymentTimelineService.getOrderTimeline(orderId));
        verify(paymentAttemptRepository, never()).findByOrder_IdOrderByCreatedAtAsc(orderId);
    }
}
