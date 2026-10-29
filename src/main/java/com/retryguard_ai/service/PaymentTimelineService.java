package com.retryguard_ai.service;

import com.retryguard_ai.dto.OrderTimelineEntry;
import com.retryguard_ai.dto.OrderTimelineResponse;
import com.retryguard_ai.entity.MerchantOrder;
import com.retryguard_ai.entity.PaymentAttempt;
import com.retryguard_ai.entity.PaymentEvent;
import com.retryguard_ai.exception.MerchantOrderNotFoundException;
import com.retryguard_ai.repository.MerchantOrderRepository;
import com.retryguard_ai.repository.PaymentAttemptRepository;
import com.retryguard_ai.repository.PaymentEventRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;

@Service
public class PaymentTimelineService {

    private final MerchantOrderRepository merchantOrderRepository;
    private final PaymentAttemptRepository paymentAttemptRepository;
    private final PaymentEventRepository paymentEventRepository;

    public PaymentTimelineService(
            MerchantOrderRepository merchantOrderRepository,
            PaymentAttemptRepository paymentAttemptRepository,
            PaymentEventRepository paymentEventRepository) {
        this.merchantOrderRepository = merchantOrderRepository;
        this.paymentAttemptRepository = paymentAttemptRepository;
        this.paymentEventRepository = paymentEventRepository;
    }

    @Transactional(readOnly = true)
    public OrderTimelineResponse getOrderTimeline(UUID orderId) {
        MerchantOrder order = merchantOrderRepository.findById(orderId)
                .orElseThrow(() -> new MerchantOrderNotFoundException(orderId));
        List<PaymentAttempt> attempts =
                paymentAttemptRepository.findByOrder_IdOrderByCreatedAtAsc(orderId);

        List<UUID> attemptIds = attempts.stream().map(PaymentAttempt::getId).toList();
        List<PaymentEvent> events = attemptIds.isEmpty()
                ? List.of()
                : paymentEventRepository.findByPaymentAttempt_IdInOrderByReceivedAtAsc(attemptIds);

        List<OrderTimelineEntry> entries = new ArrayList<>();
        entries.add(new OrderTimelineEntry(
                order.getId(),
                "ORDER_CREATED",
                order.getCreatedAt(),
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                order.getAmount(),
                order.getCurrency()));

        attempts.stream().map(this::toTimelineEntry).forEach(entries::add);
        events.stream().map(this::toTimelineEntry).forEach(entries::add);
        entries.sort(Comparator
                .comparing(
                        OrderTimelineEntry::occurredAt,
                        Comparator.nullsLast(Comparator.naturalOrder()))
                .thenComparing(OrderTimelineEntry::entryType)
                .thenComparing(OrderTimelineEntry::id));

        return new OrderTimelineResponse(
                order.getId(),
                order.getMerchantOrderReference(),
                order.getOrderStatus().name(),
                List.copyOf(entries));
    }

    private OrderTimelineEntry toTimelineEntry(PaymentAttempt attempt) {
        return new OrderTimelineEntry(
                attempt.getId(),
                "PAYMENT_ATTEMPT_CREATED",
                attempt.getCreatedAt(),
                attempt.getProvider(),
                attempt.getPaymentStatus().name(),
                null,
                null,
                null,
                null,
                null,
                attempt.getAmount(),
                attempt.getCurrency());
    }

    private OrderTimelineEntry toTimelineEntry(PaymentEvent event) {
        return new OrderTimelineEntry(
                event.getId(),
                "PAYMENT_EVENT_RECEIVED",
                event.getReceivedAt(),
                event.getProvider(),
                null,
                event.getEventType(),
                event.getSourceType().name(),
                event.getVerificationStatus().name(),
                event.getProcessingStatus().name(),
                event.getProviderEventId(),
                null,
                null);
    }
}
