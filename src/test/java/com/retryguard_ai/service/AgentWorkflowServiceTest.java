package com.retryguard_ai.service;

import com.retryguard_ai.ai.AgentOrchestrator;
import com.retryguard_ai.ai.AgentProperties;
import com.retryguard_ai.ai.GroqAgentProvider;
import com.retryguard_ai.dto.AgentWorkflowResponse;
import com.retryguard_ai.entity.AgentWorkflow;
import com.retryguard_ai.entity.AgentWorkflowStep;
import com.retryguard_ai.entity.MerchantOrder;
import com.retryguard_ai.repository.AgentWorkflowRepository;
import com.retryguard_ai.repository.AgentWorkflowStepRepository;
import com.retryguard_ai.repository.MerchantOrderRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;
import static org.mockito.Mockito.times;

@ExtendWith(MockitoExtension.class)
class AgentWorkflowServiceTest {

    @Mock private AgentOrchestrator agentOrchestrator;
    @Mock private AgentWorkflowRepository workflowRepository;
    @Mock private AgentWorkflowStepRepository stepRepository;
    @Mock private MerchantOrderRepository orderRepository;
    @Mock private ReconciliationSafetyService reconciliationService;
    @Mock private AgentProperties agentProperties;

    private AgentWorkflowService service;
    private MerchantOrder order;
    private UUID orderId;

    @BeforeEach
    void setUp() {
        service = new AgentWorkflowService(
                agentOrchestrator, workflowRepository, stepRepository, orderRepository,
                reconciliationService, agentProperties);

        orderId = UUID.randomUUID();
        order = new MerchantOrder();
        order.setId(orderId);
        order.setMerchantOrderReference("order-ref-123");
        order.setAmount(new java.math.BigDecimal("100.00"));
        order.setCurrency("USD");
        order.setOrderStatus(MerchantOrder.OrderStatus.CREATED);
    }

    private void stubSkippedPath() {
        when(orderRepository.findById(orderId)).thenReturn(Optional.of(order));
        when(workflowRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(stepRepository.findByWorkflowIdOrderByCreatedAtAsc(any())).thenReturn(List.of());
    }

    private void stubFullPath() {
        when(agentProperties.enabled()).thenReturn(true);
        when(orderRepository.findById(orderId)).thenReturn(Optional.of(order));
        when(workflowRepository.findRunningByOrderId(orderId)).thenReturn(Optional.empty());
        when(workflowRepository.saveAndFlush(any())).thenAnswer(inv -> inv.getArgument(0));
        when(workflowRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(stepRepository.findByWorkflowIdOrderByCreatedAtAsc(any())).thenReturn(List.of());
    }

    @Test
    void returnsSkippedWhenOrderAlreadyPaid() {
        order.setOrderStatus(MerchantOrder.OrderStatus.PAID);
        stubSkippedPath();

        AgentWorkflowResponse response = service.runWorkflow(orderId);

        assertThat(response.status()).isEqualTo("SKIPPED");
        assertThat(response.summary()).contains("ORDER_ALREADY_PAID");
        verify(agentOrchestrator, never()).runWorkflow(anyString());
    }

    @Test
    void returnsSkippedWhenAgentDisabled() {
        when(agentProperties.enabled()).thenReturn(false);
        stubSkippedPath();

        AgentWorkflowResponse response = service.runWorkflow(orderId);

        assertThat(response.status()).isEqualTo("SKIPPED");
        assertThat(response.summary()).contains("AGENT_DISABLED");
        verify(agentOrchestrator, never()).runWorkflow(anyString());
    }

    @Test
    void returnsSkippedWhenWorkflowAlreadyRunning() {
        when(agentProperties.enabled()).thenReturn(true);
        AgentWorkflow runningWorkflow = new AgentWorkflow();
        runningWorkflow.setId(UUID.randomUUID());
        runningWorkflow.setOrder(order);
        runningWorkflow.setStatus("RUNNING");
        runningWorkflow.setStartedAt(OffsetDateTime.now());

        when(orderRepository.findById(orderId)).thenReturn(Optional.of(order));
        when(workflowRepository.findRunningByOrderId(orderId)).thenReturn(Optional.of(runningWorkflow));
        when(workflowRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(stepRepository.findByWorkflowIdOrderByCreatedAtAsc(any())).thenReturn(List.of());

        AgentWorkflowResponse response = service.runWorkflow(orderId);

        assertThat(response.status()).isEqualTo("SKIPPED");
        assertThat(response.summary()).contains("WORKFLOW_ALREADY_RUNNING");
        verify(agentOrchestrator, never()).runWorkflow(anyString());
    }

    @Test
    void createsWorkflowAndRunsAgentWhenEligible() {
        stubFullPath();

        GroqAgentProvider.AgentRunResult result = new GroqAgentProvider.AgentRunResult(
                "COMPLETED", "RECONCILE_LOCAL_ORDER", List.of(), 2, 1, null);
        when(agentOrchestrator.runWorkflow(orderId.toString())).thenReturn(result);

        AgentWorkflowResponse response = service.runWorkflow(orderId);

        assertThat(response.status()).isEqualTo("WAITING_FOR_EVIDENCE");
        assertThat(response.summary()).contains("WAITING_FOR_EVIDENCE");
        verify(agentOrchestrator).runWorkflow(orderId.toString());
    }

    @Test
    void reconcilesWhenAgentRecommendsAndAttemptIdFound() {
        stubFullPath();

        UUID attemptId = UUID.randomUUID();
        GroqAgentProvider.AgentStep toolStep = new GroqAgentProvider.AgentStep(
                "TOOL_CALL", "verify_paypal_capture",
                java.util.Map.of("attemptId", attemptId.toString()), null, null);
        GroqAgentProvider.AgentRunResult result = new GroqAgentProvider.AgentRunResult(
                "COMPLETED", "RECONCILE_LOCAL_ORDER", List.of(toolStep), 1, 1, null);
        when(agentOrchestrator.runWorkflow(orderId.toString())).thenReturn(result);

        ReconciliationSafetyService.ReconciliationResult reconResult =
                ReconciliationSafetyService.ReconciliationResult.success("RECONCILED", "Order reconciled");
        when(reconciliationService.reconcileIfAuthorized(orderId, attemptId)).thenReturn(reconResult);

        AgentWorkflowResponse response = service.runWorkflow(orderId);

        assertThat(response.status()).isEqualTo("RECONCILED");
        assertThat(response.summary()).contains("RECONCILED");
        verify(reconciliationService).reconcileIfAuthorized(orderId, attemptId);
    }

    @Test
    void escalatesWhenReconciliationFails() {
        stubFullPath();

        UUID attemptId = UUID.randomUUID();
        GroqAgentProvider.AgentStep toolStep = new GroqAgentProvider.AgentStep(
                "TOOL_CALL", "verify_paypal_capture",
                java.util.Map.of("attemptId", attemptId.toString()), null, null);
        GroqAgentProvider.AgentRunResult result = new GroqAgentProvider.AgentRunResult(
                "COMPLETED", "RECONCILE_LOCAL_ORDER", List.of(toolStep), 1, 1, null);
        when(agentOrchestrator.runWorkflow(orderId.toString())).thenReturn(result);

        ReconciliationSafetyService.ReconciliationResult reconResult =
                ReconciliationSafetyService.ReconciliationResult.failure("AMOUNT_MISMATCH", "Amount mismatch");
        when(reconciliationService.reconcileIfAuthorized(orderId, attemptId)).thenReturn(reconResult);

        AgentWorkflowResponse response = service.runWorkflow(orderId);

        assertThat(response.status()).isEqualTo("ESCALATED");
        assertThat(response.summary()).contains("RECONCILIATION_FAILED");
    }

    @Test
    void setsWaitingForWebhookWhenAgentRecommends() {
        stubFullPath();

        GroqAgentProvider.AgentRunResult result = new GroqAgentProvider.AgentRunResult(
                "COMPLETED", "WAIT_FOR_WEBHOOK", List.of(), 2, 1, null);
        when(agentOrchestrator.runWorkflow(orderId.toString())).thenReturn(result);

        AgentWorkflowResponse response = service.runWorkflow(orderId);

        assertThat(response.status()).isEqualTo("WAITING_FOR_WEBHOOK");
        verify(reconciliationService, never()).reconcileIfAuthorized(any(), any());
    }

    @Test
    void escalatesWhenAgentRecommendsEscalate() {
        stubFullPath();

        GroqAgentProvider.AgentRunResult result = new GroqAgentProvider.AgentRunResult(
                "COMPLETED", "ESCALATE", List.of(), 2, 1, null);
        when(agentOrchestrator.runWorkflow(orderId.toString())).thenReturn(result);

        AgentWorkflowResponse response = service.runWorkflow(orderId);

        assertThat(response.status()).isEqualTo("ESCALATED");
        verify(reconciliationService, never()).reconcileIfAuthorized(any(), any());
    }

    @Test
    void handlesProviderErrorFromAgent() {
        stubFullPath();

        GroqAgentProvider.AgentRunResult result = new GroqAgentProvider.AgentRunResult(
                "PROVIDER_ERROR", "GROQ API key invalid", List.of(), 0, 0, null);
        when(agentOrchestrator.runWorkflow(orderId.toString())).thenReturn(result);

        AgentWorkflowResponse response = service.runWorkflow(orderId);

        assertThat(response.status()).isEqualTo("PROVIDER_ERROR");
        assertThat(response.summary()).contains("GROQ API key invalid");
    }

    @Test
    void handlesBudgetExceededFromAgent() {
        stubFullPath();

        GroqAgentProvider.AgentRunResult result = new GroqAgentProvider.AgentRunResult(
                "BUDGET_EXCEEDED", "Maximum tool calls exceeded", List.of(), 4, 3, null);
        when(agentOrchestrator.runWorkflow(orderId.toString())).thenReturn(result);

        AgentWorkflowResponse response = service.runWorkflow(orderId);

        assertThat(response.status()).isEqualTo("BUDGET_EXCEEDED");
        assertThat(response.summary()).contains("Maximum tool calls exceeded");
    }

    @Test
    void savesStepsAndPersistsWorkflow() {
        stubFullPath();

        GroqAgentProvider.AgentStep step1 = new GroqAgentProvider.AgentStep(
                "TOOL_CALL", "get_order_summary",
                java.util.Map.of("orderId", orderId.toString()),
                java.util.Map.of("status", "CREATED"), null);
        GroqAgentProvider.AgentStep step2 = new GroqAgentProvider.AgentStep(
                "TOOL_CALL", "final_recommendation",
                java.util.Map.of("recommendation", "WAIT_FOR_WEBHOOK", "reason", "Need more info"),
                null, null);

        GroqAgentProvider.AgentRunResult result = new GroqAgentProvider.AgentRunResult(
                "COMPLETED", "WAIT_FOR_WEBHOOK", List.of(step1, step2), 2, 1, null);
        when(agentOrchestrator.runWorkflow(orderId.toString())).thenReturn(result);

        AgentWorkflowResponse response = service.runWorkflow(orderId);

        assertThat(response.status()).isEqualTo("WAITING_FOR_WEBHOOK");
        verify(stepRepository, times(2)).save(any(AgentWorkflowStep.class));
        verify(workflowRepository, atLeast(2)).save(any(AgentWorkflow.class));
    }
}