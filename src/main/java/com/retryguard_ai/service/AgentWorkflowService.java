package com.retryguard_ai.service;

import com.retryguard_ai.ai.AgentOrchestrator;
import com.retryguard_ai.ai.AgentProperties;
import com.retryguard_ai.ai.GroqAgentProvider;
import com.retryguard_ai.dto.AgentWorkflowResponse;
import com.retryguard_ai.dto.AgentWorkflowStepResponse;
import com.retryguard_ai.entity.AgentWorkflow;
import com.retryguard_ai.entity.AgentWorkflowStep;
import com.retryguard_ai.entity.MerchantOrder;
import com.retryguard_ai.repository.AgentWorkflowRepository;
import com.retryguard_ai.repository.AgentWorkflowStepRepository;
import com.retryguard_ai.repository.MerchantOrderRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;

@Service
public class AgentWorkflowService {

    private final AgentOrchestrator agentOrchestrator;
    private final AgentWorkflowRepository workflowRepository;
    private final AgentWorkflowStepRepository stepRepository;
    private final MerchantOrderRepository orderRepository;
    private final ReconciliationSafetyService reconciliationService;
    private final AgentProperties agentProperties;

    public AgentWorkflowService(
            AgentOrchestrator agentOrchestrator,
            AgentWorkflowRepository workflowRepository,
            AgentWorkflowStepRepository stepRepository,
            MerchantOrderRepository orderRepository,
            ReconciliationSafetyService reconciliationService,
            AgentProperties agentProperties) {
        this.agentOrchestrator = agentOrchestrator;
        this.workflowRepository = workflowRepository;
        this.stepRepository = stepRepository;
        this.orderRepository = orderRepository;
        this.reconciliationService = reconciliationService;
        this.agentProperties = agentProperties;
    }

    @Transactional
    public AgentWorkflowResponse runWorkflow(UUID orderId) {
        MerchantOrder order = orderRepository.findById(orderId)
                .orElseThrow(() -> new IllegalArgumentException("Order not found: " + orderId));

        if (order.getOrderStatus() == MerchantOrder.OrderStatus.PAID) {
            return createSkippedWorkflow(orderId, "ORDER_ALREADY_PAID", "Order is already PAID");
        }

        if (!agentProperties.enabled()) {
            return createSkippedWorkflow(orderId, "AGENT_DISABLED", "Agent is disabled via configuration");
        }

        var runningWorkflow = workflowRepository.findRunningByOrderId(orderId);
        if (runningWorkflow.isPresent()) {
            return createSkippedWorkflow(orderId, "WORKFLOW_ALREADY_RUNNING", "A workflow is already running for this order");
        }

        AgentWorkflow workflow = new AgentWorkflow();
        workflow.setOrder(order);
        workflow.setStatus("RUNNING");
        workflow.setStartedAt(OffsetDateTime.now());
        workflow.setProviderName("GROQ");
        AgentWorkflow saved = workflowRepository.saveAndFlush(workflow);

        try {
            // Execute agent workflow outside of transaction - will be managed by outer
            GroqAgentProvider.AgentRunResult result = agentOrchestrator.runWorkflow(orderId.toString());

            // Now update workflow status within transaction
            saved.setStatus(result.status());
            saved.setSummary(result.summary());
            saved.setToolCallsUsed(result.toolCallsUsed());
            saved.setModelTurnsUsed(result.modelTurnsUsed());
            saved.setCompletedAt(OffsetDateTime.now());

            if (result.usage() != null) {
                saved.setPromptTokens(result.usage().promptTokens());
                saved.setCompletionTokens(result.usage().completionTokens());
                saved.setTotalTokens(result.usage().totalTokens());
            }

            workflowRepository.save(saved);

            for (GroqAgentProvider.AgentStep step : result.steps()) {
                AgentWorkflowStep stepEntity = new AgentWorkflowStep();
                stepEntity.setWorkflow(saved);
                stepEntity.setStepType(step.type());
                stepEntity.setStepName(step.name());
                stepEntity.setInputSummary(step.input() != null ? step.input().toString() : null);
                stepEntity.setOutputSummary(step.output() != null ? step.output().toString() : null);
                stepEntity.setError(step.error());
                stepEntity.setCreatedAt(OffsetDateTime.now());
                stepRepository.save(stepEntity);
            }

            // Handle reconciliation recommendation outside of the main transaction context
            // to avoid keeping DB transaction open during HTTP calls to PayPal
            if ("RECONCILE_LOCAL_ORDER".equals(result.summary())) {
                UUID attemptId = extractAttemptIdFromSteps(result.steps());
                if (attemptId != null) {
                    // Reconciliation is handled by the separate Java safety service
                    // which performs verified local reconciliation without open transaction
                    ReconciliationSafetyService.ReconciliationResult reconResult =
                            reconciliationService.reconcileIfAuthorized(orderId, attemptId);
                    if (reconResult.success()) {
                        saved.setStatus("RECONCILED");
                        saved.setSummary("RECONCILED: " + reconResult.message());
                    } else {
                        saved.setStatus("ESCALATED");
                        saved.setSummary("RECONCILIATION_FAILED: " + reconResult.message());
                    }
                    workflowRepository.save(saved);
                } else {
                    saved.setStatus("WAITING_FOR_EVIDENCE");
                    saved.setSummary("WAITING_FOR_EVIDENCE: No attempt ID found in verification steps");
                    workflowRepository.save(saved);
                }
            } else if ("WAIT_FOR_WEBHOOK".equals(result.summary())) {
                saved.setStatus("WAITING_FOR_WEBHOOK");
                workflowRepository.save(saved);
            } else if ("ESCALATE".equals(result.summary())) {
                saved.setStatus("ESCALATED");
                workflowRepository.save(saved);
            }

            return toResponse(saved);
        } catch (Exception e) {
            saved.setStatus("ERROR");
            saved.setSummary("Workflow execution failed: " + e.getMessage());
            saved.setCompletedAt(OffsetDateTime.now());
            workflowRepository.save(saved);
            throw new RuntimeException("Agent workflow failed", e);
        }
    }

    private UUID extractAttemptIdFromSteps(List<GroqAgentProvider.AgentStep> steps) {
        for (GroqAgentProvider.AgentStep step : steps) {
            if ("TOOL_CALL".equals(step.type()) && "verify_paypal_capture".equals(step.name())) {
                if (step.input() instanceof java.util.Map<?, ?> map) {
                    Object attemptId = map.get("attemptId");
                    if (attemptId instanceof String str) {
                        try {
                            return UUID.fromString(str);
                        } catch (IllegalArgumentException ignored) {}
                    }
                }
            }
        }
        return null;
    }

    @Transactional(readOnly = true)
    public AgentWorkflowResponse getWorkflow(UUID workflowId) {
        AgentWorkflow workflow = workflowRepository.findById(workflowId)
                .orElseThrow(() -> new IllegalArgumentException("Workflow not found: " + workflowId));
        return toResponse(workflow);
    }

    @Transactional(readOnly = true)
    public List<AgentWorkflowResponse> getWorkflowsForOrder(UUID orderId) {
        return workflowRepository.findByOrderIdOrderByStartedAtDesc(orderId)
                .stream()
                .map(this::toResponse)
                .collect(Collectors.toList());
    }

    @Transactional(readOnly = true)
    public List<AgentWorkflowResponse> getRecentWorkflows() {
        return workflowRepository.findTop50ByOrderByStartedAtDesc()
                .stream()
                .map(this::toResponse)
                .collect(Collectors.toList());
    }

    private AgentWorkflowResponse toResponse(AgentWorkflow workflow) {
        List<AgentWorkflowStep> steps = stepRepository.findByWorkflowIdOrderByCreatedAtAsc(workflow.getId());
        return new AgentWorkflowResponse(
                workflow.getId(),
                workflow.getOrder().getId(),
                workflow.getStatus(),
                workflow.getSummary(),
                workflow.getToolCallsUsed(),
                workflow.getModelTurnsUsed(),
                workflow.getStartedAt(),
                workflow.getCompletedAt(),
                steps.stream().map(this::toStepResponse).collect(Collectors.toList())
        );
    }

    private AgentWorkflowStepResponse toStepResponse(AgentWorkflowStep step) {
        return new AgentWorkflowStepResponse(
                step.getId(),
                step.getWorkflow().getId(),
                step.getStepType(),
                step.getStepName(),
                truncate(step.getInputSummary(), 500),
                truncate(step.getOutputSummary(), 500),
                step.getError(),
                step.getCreatedAt()
        );
    }

    private String truncate(String value, int maxLength) {
        if (value == null) return null;
        if (value.length() <= maxLength) return value;
        return value.substring(0, maxLength) + "...";
    }

    private AgentWorkflowResponse createSkippedWorkflow(UUID orderId, String code, String message) {
        AgentWorkflow workflow = new AgentWorkflow();
        MerchantOrder order = orderRepository.findById(orderId)
                .orElseThrow(() -> new IllegalArgumentException("Order not found: " + orderId));
        workflow.setOrder(order);
        workflow.setStatus("SKIPPED");
        workflow.setSummary(code + ": " + message);
        workflow.setStartedAt(OffsetDateTime.now());
        workflow.setCompletedAt(OffsetDateTime.now());
        AgentWorkflow saved = workflowRepository.save(workflow);
        return toResponse(saved);
    }
}