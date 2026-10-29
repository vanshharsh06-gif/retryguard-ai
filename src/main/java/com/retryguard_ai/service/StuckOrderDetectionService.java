package com.retryguard_ai.service;

import com.retryguard_ai.ai.AgentProperties;
import com.retryguard_ai.entity.MerchantOrder;
import com.retryguard_ai.entity.PaymentAttempt;
import com.retryguard_ai.repository.AgentWorkflowRepository;
import com.retryguard_ai.repository.MerchantOrderRepository;
import com.retryguard_ai.repository.PaymentAttemptRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

@Service
public class StuckOrderDetectionService {

    private static final Logger log = LoggerFactory.getLogger(StuckOrderDetectionService.class);
    private static final String PAYPAL_SANDBOX = "PAYPAL_SANDBOX";

    private final AgentProperties properties;
    private final MerchantOrderRepository orderRepository;
    private final PaymentAttemptRepository attemptRepository;
    private final AgentWorkflowRepository workflowRepository;
    private final AgentWorkflowService workflowService;

    public StuckOrderDetectionService(
            AgentProperties properties,
            MerchantOrderRepository orderRepository,
            PaymentAttemptRepository attemptRepository,
            AgentWorkflowRepository workflowRepository,
            AgentWorkflowService workflowService) {
        this.properties = properties;
        this.orderRepository = orderRepository;
        this.attemptRepository = attemptRepository;
        this.workflowRepository = workflowRepository;
        this.workflowService = workflowService;
    }

    @Scheduled(fixedDelayString = "${retryguard.ai.agent.scan-interval-ms:300000}")
    @Transactional
    public void scanForStuckOrders() {
        if (!properties.enabled() || !properties.schedulerEnabled()) {
            return;
        }

        OffsetDateTime threshold = OffsetDateTime.now().minusMinutes(properties.stuckOrderMinutes());
        List<MerchantOrder> stuckOrders = orderRepository
                .findByOrderStatusInAndCreatedAtBefore(
                        List.of(MerchantOrder.OrderStatus.CREATED, MerchantOrder.OrderStatus.PENDING),
                        threshold);

        int processed = 0;
        for (MerchantOrder order : stuckOrders) {
            if (processed >= properties.maxRunsPerScan()) {
                break;
            }

            // Prevent duplicate/ concurrent workflows for the same order
            if (workflowRepository.findRunningByOrderId(order.getId()).isPresent()) {
                log.debug("Skipping order {}: workflow already running", order.getId());
                continue;
            }

            List<PaymentAttempt> paypalAttempts = attemptRepository.findByOrder_IdAndProvider(order.getId(), PAYPAL_SANDBOX);
            if (paypalAttempts.isEmpty()) {
                continue;
            }

            try {
                workflowService.runWorkflow(order.getId());
                processed++;
            } catch (Exception e) {
                log.warn("Agent workflow failed for order {}: {}", order.getId(), e.getMessage());
            }
        }

        if (processed > 0) {
            log.info("Agent scheduler processed {} stuck orders", processed);
        }
    }

    @Transactional
    public int runManualScan() {
        if (!properties.enabled()) {
            throw new IllegalStateException("Agent is disabled via configuration");
        }

        OffsetDateTime threshold = OffsetDateTime.now().minusMinutes(properties.stuckOrderMinutes());
        List<MerchantOrder> stuckOrders = orderRepository
                .findByOrderStatusInAndCreatedAtBefore(
                        List.of(MerchantOrder.OrderStatus.CREATED, MerchantOrder.OrderStatus.PENDING),
                        threshold);

        int processed = 0;
        for (MerchantOrder order : stuckOrders) {
            if (processed >= properties.maxRunsPerScan()) {
                break;
            }

            List<PaymentAttempt> paypalAttempts = attemptRepository.findByOrder_IdAndProvider(order.getId(), PAYPAL_SANDBOX);
            if (paypalAttempts.isEmpty()) {
                continue;
            }

            try {
                workflowService.runWorkflow(order.getId());
                processed++;
            } catch (Exception e) {
                log.warn("Manual agent workflow failed for order {}: {}", order.getId(), e.getMessage());
            }
        }

        return processed;
    }
}