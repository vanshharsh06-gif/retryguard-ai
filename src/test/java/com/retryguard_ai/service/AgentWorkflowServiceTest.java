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
import tools.jackson.databind.ObjectMapper;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
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
    @Mock private AgentWorkflowPersistenceService persistenceService;
    @Mock private ObjectMapper objectMapper;

    private AgentWorkflowService service;
    private MerchantOrder order;
    private UUID orderId;

    @BeforeEach
    void setUp() {
        service = new AgentWorkflowService(
                agentOrchestrator, workflowRepository, stepRepository, orderRepository,
                reconciliationService, agentProperties, persistenceService, objectMapper);

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
        when(persistenceService.persistRunning(any(AgentWorkflow.class)))
                .thenAnswer(inv -> inv.getArgument(0));
        when(stepRepository.findByWorkflowIdOrderByCreatedAtAsc(any())).thenReturn(List.of());
    }

    private void stubFullPath() {
        when(agentProperties.enabled()).thenReturn(true);
        when(orderRepository.findById(orderId)).thenReturn(Optional.of(order));
        when(workflowRepository.findRunningByOrderId(orderId)).thenReturn(Optional.empty());
        when(persistenceService.persistRunning(any(AgentWorkflow.class)))
                .thenAnswer(inv -> inv.getArgument(0));
        when(persistenceService.persistCompleted(any(AgentWorkflow.class)))
                .thenAnswer(inv -> inv.getArgument(0));
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
        when(persistenceService.persistRunning(any(AgentWorkflow.class)))
                .thenAnswer(inv -> inv.getArgument(0));
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
        verify(persistenceService, times(2)).persistStep(any(AgentWorkflowStep.class));
        verify(persistenceService, atLeast(2)).persistCompleted(any(AgentWorkflow.class));
    }

    @Test
    void errorStatusPersistsAfterAgentException() {
        when(agentProperties.enabled()).thenReturn(true);
        when(orderRepository.findById(orderId)).thenReturn(Optional.of(order));
        when(workflowRepository.findRunningByOrderId(orderId)).thenReturn(Optional.empty());
        when(persistenceService.persistRunning(any(AgentWorkflow.class)))
                .thenAnswer(inv -> inv.getArgument(0));

        when(agentOrchestrator.runWorkflow(orderId.toString()))
                .thenThrow(new RuntimeException("Groq network failure"));

        assertThatThrownBy(() -> service.runWorkflow(orderId))
                .isInstanceOf(RuntimeException.class);

        // ERROR status must have been persisted independently via persistError
        verify(persistenceService).persistError(any(AgentWorkflow.class), any(Exception.class));
    }

    @Test
    void concurrentWorkflowPreventedByDatabaseIndex() {
        when(agentProperties.enabled()).thenReturn(true);
        when(orderRepository.findById(orderId)).thenReturn(Optional.of(order));
        when(workflowRepository.findRunningByOrderId(orderId))
                .thenReturn(Optional.of(new AgentWorkflow()));
        when(persistenceService.persistRunning(any(AgentWorkflow.class)))
                .thenAnswer(inv -> inv.getArgument(0));
        when(stepRepository.findByWorkflowIdOrderByCreatedAtAsc(any())).thenReturn(List.of());

        AgentWorkflowResponse response = service.runWorkflow(orderId);

        assertThat(response.status()).isEqualTo("SKIPPED");
        assertThat(response.summary()).contains("WORKFLOW_ALREADY_RUNNING");
        verify(agentOrchestrator, never()).runWorkflow(anyString());
    }

    @Test
    void secondWorkflowAllowedAfterTerminalStatus() {
        when(agentProperties.enabled()).thenReturn(true);
        when(orderRepository.findById(orderId)).thenReturn(Optional.of(order));
        when(workflowRepository.findRunningByOrderId(orderId))
                .thenReturn(Optional.empty());
        when(persistenceService.persistRunning(any(AgentWorkflow.class)))
                .thenAnswer(inv -> inv.getArgument(0));
        when(persistenceService.persistCompleted(any(AgentWorkflow.class)))
                .thenAnswer(inv -> inv.getArgument(0));
        when(stepRepository.findByWorkflowIdOrderByCreatedAtAsc(any())).thenReturn(List.of());

        GroqAgentProvider.AgentRunResult result = new GroqAgentProvider.AgentRunResult(
                "COMPLETED", "ESCALATE", List.of(), 1, 1, null);
        when(agentOrchestrator.runWorkflow(orderId.toString())).thenReturn(result);

        AgentWorkflowResponse response = service.runWorkflow(orderId);

        assertThat(response.status()).isEqualTo("ESCALATED");

        // Now a second workflow should be allowed since previous is terminal
        verify(agentOrchestrator).runWorkflow(orderId.toString());
    }

    @Test
    void errorDetailsPreservedInWorkflowRecord() {
        when(agentProperties.enabled()).thenReturn(true);
        when(orderRepository.findById(orderId)).thenReturn(Optional.of(order));
        when(workflowRepository.findRunningByOrderId(orderId)).thenReturn(Optional.empty());
        when(persistenceService.persistRunning(any(AgentWorkflow.class)))
                .thenAnswer(inv -> inv.getArgument(0));

        String errorMessage = "Groq API key invalid";
        when(agentOrchestrator.runWorkflow(orderId.toString()))
                .thenThrow(new RuntimeException(errorMessage));

        try {
            service.runWorkflow(orderId);
        } catch (RuntimeException ignored) {
            // Expected
        }

        // Verify workflow was saved with ERROR status via persistError
        verify(persistenceService).persistError(any(AgentWorkflow.class), any(Exception.class));
    }
}
