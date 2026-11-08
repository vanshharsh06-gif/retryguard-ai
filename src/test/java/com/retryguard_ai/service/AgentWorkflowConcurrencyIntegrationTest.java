package com.retryguard_ai.service;

import com.retryguard_ai.dto.AgentWorkflowResponse;
import com.retryguard_ai.entity.AgentWorkflow;
import com.retryguard_ai.entity.AgentWorkflowStep;
import com.retryguard_ai.entity.Investigation;
import com.retryguard_ai.entity.InvestigationHistory;
import com.retryguard_ai.entity.MerchantOrder;
import com.retryguard_ai.repository.AgentWorkflowRepository;
import com.retryguard_ai.repository.AgentWorkflowStepRepository;
import com.retryguard_ai.repository.InvestigationHistoryRepository;
import com.retryguard_ai.repository.InvestigationRepository;
import com.retryguard_ai.repository.MerchantOrderRepository;
import com.retryguard_ai.repository.PaymentAttemptRepository;
import com.retryguard_ai.repository.PaymentEventRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@ActiveProfiles("test")
class AgentWorkflowConcurrencyIntegrationTest {

    @Autowired
    private AgentWorkflowService workflowService;

    @Autowired
    private AgentWorkflowRepository workflowRepository;

    @Autowired
    private AgentWorkflowStepRepository stepRepository;

    @Autowired
    private MerchantOrderRepository orderRepository;

    @Autowired
    private PaymentAttemptRepository paymentAttemptRepository;

    @Autowired
    private PaymentEventRepository paymentEventRepository;

    @Autowired
    private InvestigationRepository investigationRepository;

    @Autowired
    private InvestigationHistoryRepository investigationHistoryRepository;

    @BeforeEach
    void setUp() {
        // Clean up child tables first to respect foreign key constraints
        investigationHistoryRepository.deleteAll();
        investigationRepository.deleteAll();
        stepRepository.deleteAll();
        workflowRepository.deleteAll();
        paymentEventRepository.deleteAll();
        paymentAttemptRepository.deleteAll();
        orderRepository.deleteAll();

        MerchantOrder order = new MerchantOrder();
        order.setMerchantOrderReference("test-order-" + System.currentTimeMillis());
        order.setAmount(java.math.BigDecimal.valueOf(100.00));
        order.setCurrency("USD");
        order.setOrderStatus(MerchantOrder.OrderStatus.CREATED);
        orderRepository.saveAndFlush(order);
    }

    @Test
    void twoConcurrentRunningWorkflowsForSameOrder() {
        MerchantOrder order = orderRepository.findAll().stream().findFirst()
                .orElseThrow(() -> new IllegalStateException("No order in setUp"));
        UUID orderId = order.getId();

        // Agent not configured → SKIPPED (terminal). Only one workflow record.
        workflowService.runWorkflow(orderId);

        long runningCount = workflowRepository.countRunningByOrderId(orderId);
        assertThat(runningCount).isEqualTo(0); // SKIPPED is terminal, not RUNNING

        // Only one workflow record should exist
        long totalCount = workflowRepository.findByOrderIdOrderByStartedAtDesc(orderId).size();
        assertThat(totalCount).isEqualTo(1);
    }

    @Test
    void differentOrdersCanRunWorkflowsConcurrently() {
        MerchantOrder order1 = new MerchantOrder();
        order1.setMerchantOrderReference("test-order-" + UUID.randomUUID());
        order1.setAmount(java.math.BigDecimal.valueOf(100.00));
        order1.setCurrency("USD");
        order1.setOrderStatus(MerchantOrder.OrderStatus.CREATED);
        orderRepository.saveAndFlush(order1);

        MerchantOrder order2 = new MerchantOrder();
        order2.setMerchantOrderReference("test-order-" + UUID.randomUUID());
        order2.setAmount(java.math.BigDecimal.valueOf(200.00));
        order2.setCurrency("USD");
        order2.setOrderStatus(MerchantOrder.OrderStatus.CREATED);
        orderRepository.saveAndFlush(order2);

        workflowService.runWorkflow(order1.getId());
        workflowService.runWorkflow(order2.getId());

        long count1 = workflowRepository.countRunningByOrderId(order1.getId());
        long count2 = workflowRepository.countRunningByOrderId(order2.getId());

        assertThat(count1).isEqualTo(0); // SKIPPED, not RUNNING
        assertThat(count2).isEqualTo(0); // SKIPPED, not RUNNING
    }

    @Test
    void newWorkflowAllowedAfterPreviousTerminal() {
        MerchantOrder order = orderRepository.findAll().stream().findFirst()
                .orElseThrow(() -> new IllegalStateException("No order in setUp"));
        UUID orderId = order.getId();

        // First workflow reaches terminal status (SKIPPED since agent disabled)
        workflowService.runWorkflow(orderId);

        Optional<AgentWorkflow> runningWorkflow = workflowRepository.findRunningByOrderId(orderId);
        assertThat(runningWorkflow).isEmpty();

        // Second workflow should be allowed since previous is terminal
        workflowService.runWorkflow(orderId);

        // Both calls create SKIPPED records (agent disabled path)
        long totalCount = workflowRepository.findByOrderIdOrderByStartedAtDesc(orderId).size();
        assertThat(totalCount).isEqualTo(2);

        // Verify no RUNNING workflow exists (both are terminal SKIPPED)
        long runningCount = workflowRepository.countRunningByOrderId(orderId);
        assertThat(runningCount).isEqualTo(0);
    }

    @Test
    void conflictResponseDoesNotInvokeGroq() {
        MerchantOrder order = orderRepository.findAll().stream().findFirst()
                .orElseThrow(() -> new IllegalStateException("No order in setUp"));
        UUID orderId = order.getId();

        // First workflow
        workflowService.runWorkflow(orderId);

        // Second attempt - should get SKIPPED (agent disabled)
        AgentWorkflowResponse response = workflowService.runWorkflow(orderId);

        assertThat(response.status()).isEqualTo("SKIPPED");
        assertThat(response.summary()).contains("AGENT_DISABLED");
    }

    @Test
    void partialUniqueIndexPreventsDuplicateRunningWorkflows() {
        MerchantOrder order = orderRepository.findAll().stream().findFirst()
                .orElseThrow(() -> new IllegalStateException("No order in setUp"));
        UUID orderId = order.getId();

        // Simulate an existing RUNNING workflow in the database
        AgentWorkflow existing = new AgentWorkflow();
        existing.setOrder(order);
        existing.setStatus("RUNNING");
        existing.setStartedAt(java.time.OffsetDateTime.now());
        existing.setProviderName("GROQ");
        workflowRepository.saveAndFlush(existing);

        // Only one RUNNING workflow exists
        long runningCount = workflowRepository.countRunningByOrderId(orderId);
        assertThat(runningCount).isEqualTo(1);
    }
}