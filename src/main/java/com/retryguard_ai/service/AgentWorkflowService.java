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
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.support.SQLErrorCodeSQLExceptionTranslator;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Agent workflow orchestration service.
 *
 * Transaction boundaries are carefully separated from external network calls:
 *
 * <ul>
 *   <li>The RUNNING workflow is persisted and committed via
 *       {@link AgentWorkflowPersistenceService#persistRunning(AgentWorkflow)}
 *       <b>before</b> any Groq HTTP call is made.</li>
 *   <li>The Groq agent execution ({@link AgentOrchestrator#runWorkflow}) runs
 *       <b>without</b> an active database transaction.</li>
 *   <li>Workflow results, steps, and ERROR status are persisted using separate
 *       short transactions via {@link AgentWorkflowPersistenceService}.</li>
 *   <li>ERROR persistence uses {@code PROPAGATION_REQUIRES_NEW} through a
 *       separate Spring-managed bean so self-invocation cannot bypass the
 *       transactional proxy.</li>
 * </ul>
 */
@Service
public class AgentWorkflowService {

    private static final Logger log = LoggerFactory.getLogger(AgentWorkflowService.class);

    private final AgentOrchestrator agentOrchestrator;
    private final AgentWorkflowRepository workflowRepository;
    private final AgentWorkflowStepRepository stepRepository;
    private final MerchantOrderRepository orderRepository;
    private final ReconciliationSafetyService reconciliationService;
    private final AgentProperties agentProperties;
    private final AgentWorkflowPersistenceService persistenceService;
    private final ObjectMapper objectMapper;

    public AgentWorkflowService(
            AgentOrchestrator agentOrchestrator,
            AgentWorkflowRepository workflowRepository,
            AgentWorkflowStepRepository stepRepository,
            MerchantOrderRepository orderRepository,
            ReconciliationSafetyService reconciliationService,
            AgentProperties agentProperties,
            AgentWorkflowPersistenceService persistenceService,
            ObjectMapper objectMapper) {
        this.agentOrchestrator = agentOrchestrator;
        this.workflowRepository = workflowRepository;
        this.stepRepository = stepRepository;
        this.orderRepository = orderRepository;
        this.reconciliationService = reconciliationService;
        this.agentProperties = agentProperties;
        this.persistenceService = persistenceService;
        this.objectMapper = objectMapper;
    }

    /**
     * Log the resolved agent configuration once at startup so operators can
     * diagnose "SKIPPED · AGENT_DISABLED" without exposing the API key value.
     */
    @PostConstruct
    void logAgentConfiguration() {
        boolean enabled = agentProperties.enabled();
        boolean groqConfigured = agentProperties.groq() != null
                && agentProperties.groq().isConfigured();
        log.info("Agent configuration resolved: enabled={} groqConfigured={} model={}",
                enabled,
                groqConfigured,
                agentProperties.groq() == null ? "" : agentProperties.groq().model());
    }

    /**
     * Execute the agent workflow for the given order.
     *
     * <p>Transaction boundaries:
     * <ol>
     *   <li>Validate order and check for existing RUNNING workflow (read-only).</li>
     *   <li>Persist RUNNING workflow via {@code persistenceService.persistRunning}
 *       — short transaction, committed before Groq call.</li>
     *   <li>Execute Groq agent workflow — <b>no database transaction active</b>.</li>
     *   <li>Persist results/steps via {@code persistenceService.persistCompleted}
 *       — short transaction.</li>
     *   <li>On exception, persist ERROR via
 *       {@code persistenceService.persistError} — {@code REQUIRES_NEW},
 *       committed independently even if outer transaction rolls back.</li>
     * </ol>
     */
    public AgentWorkflowResponse runWorkflow(UUID orderId) {
        // Validate the order first (read-only)
        MerchantOrder order = orderRepository.findById(orderId)
                .orElseThrow(() -> new IllegalArgumentException("Order not found: " + orderId));

        if (order.getOrderStatus() == MerchantOrder.OrderStatus.PAID) {
            return createSkippedWorkflow(orderId, "ORDER_ALREADY_PAID", "Order is already PAID");
        }

        if (!agentProperties.enabled()) {
            return createSkippedWorkflow(orderId, "AGENT_DISABLED", "Agent is disabled via configuration");
        }

        // NOTE: a missing Groq API key is handled downstream by GroqAgentProvider,
        // which returns a PROVIDER_UNAVAILABLE result with a clear message. That
        // status is persisted and surfaced to the dashboard, so no additional
        // service-level guard is required here.

        // Check for existing running workflow (application-level guard;
        // DB partial unique index enforces at database level)
        var runningWorkflow = workflowRepository.findRunningByOrderId(orderId);
        if (runningWorkflow.isPresent()) {
            return createSkippedWorkflow(orderId, "WORKFLOW_ALREADY_RUNNING",
                    "A workflow is already running for this order");
        }

        // Create and persist RUNNING workflow, commit before external call
        AgentWorkflow workflow = new AgentWorkflow();
        workflow.setOrder(order);
        workflow.setStatus("RUNNING");
        workflow.setStartedAt(OffsetDateTime.now());
        workflow.setProviderName("GROQ");
        AgentWorkflow saved;
        try {
            saved = persistenceService.persistRunning(workflow);
        } catch (DataIntegrityViolationException e) {
            // Check if this is the specific PostgreSQL partial unique index violation
            // for one RUNNING workflow per order. Propagate unrelated DB errors.
            if (isWorkflowConcurrencyViolation(e)) {
                // Another concurrent request created a RUNNING workflow for this order.
                // Return conflict response — do NOT invoke Groq.
                return createSkippedWorkflow(orderId, "WORKFLOW_ALREADY_RUNNING",
                        "A workflow is already running for this order (database constraint)");
            }
            throw e;
        }

        // Execute Groq agent workflow WITHOUT an open database transaction.
        // The Spring transactional proxy is NOT involved here — no DB
        // connection is held while waiting for the Groq HTTP response.
        GroqAgentProvider.AgentRunResult result;
        try {
            result = agentOrchestrator.runWorkflow(orderId.toString());
        } catch (Exception e) {
            // Persist ERROR independently so it is not rolled back.
            // Invoked through the separate persistenceService bean so
            // PROPAGATION_REQUIRES_NEW is honored by the Spring proxy.
            persistenceService.persistError(saved, e);
            throw new RuntimeException("Agent workflow failed", e);
        }

        // Persist final status and steps using short transactions
        return persistResults(saved, result);
    }

    /**
     * Persist final workflow status, counters, steps, and reconciliation
     * outcome using short transactions via the persistence service.
     * Steps are persisted BEFORE the workflow status is set to COMPLETED,
     * ensuring a step persistence failure cannot leave a misleading COMPLETED workflow.
     * All transactional database work is delegated to
     * AgentWorkflowPersistenceService, which is a separate Spring-managed
     * bean with proper @Transactional annotations.
     * <p>
     * NOTE: This method is private and does NOT have @Transactional.
     * Spring's proxy-based transaction management does not apply to private methods,
     * so all database operations must go through the persistence service's
     * public transactional methods.
     */
    private AgentWorkflowResponse persistResults(AgentWorkflow workflow, GroqAgentProvider.AgentRunResult result) {
        workflow.setStatus(result.status());
        workflow.setSummary(result.summary());
        workflow.setToolCallsUsed(result.toolCallsUsed());
        workflow.setModelTurnsUsed(result.modelTurnsUsed());
        workflow.setCompletedAt(OffsetDateTime.now());

        if (result.usage() != null) {
            workflow.setPromptTokens(result.usage().promptTokens());
            workflow.setCompletionTokens(result.usage().completionTokens());
            workflow.setTotalTokens(result.usage().totalTokens());
        }

        // Persist all workflow steps FIRST, before marking the workflow COMPLETED.
        // If any step persistence fails, an ERROR status will be persisted instead.
        boolean stepsFailed = false;
        for (GroqAgentProvider.AgentStep step : result.steps()) {
            AgentWorkflowStep stepEntity = new AgentWorkflowStep();
            stepEntity.setWorkflow(workflow);
            stepEntity.setStepType(step.type());
            stepEntity.setStepName(step.name());
            try {
                stepEntity.setInputSummary(step.input() != null ? objectMapper.writeValueAsString(step.input()) : null);
            } catch (Exception e) {
                stepEntity.setInputSummary("{\"error\": \"serialization failed\"}");
            }
            try {
                stepEntity.setOutputSummary(step.output() != null ? objectMapper.writeValueAsString(step.output()) : null);
            } catch (Exception e) {
                stepEntity.setOutputSummary("{\"error\": \"serialization failed\"}");
            }
            stepEntity.setError(step.error());
            stepEntity.setCreatedAt(OffsetDateTime.now());
            try {
                persistenceService.persistStep(stepEntity);
            } catch (Exception e) {
                stepsFailed = true;
                break;
            }
        }

        if (stepsFailed) {
            // Step persistence failed — persist ERROR status independently
            workflow.setStatus("ERROR");
            workflow.setSummary("Workflow execution failed: Step persistence error");
            workflow.setCompletedAt(OffsetDateTime.now());
            persistenceService.persistError(workflow, new RuntimeException("Step persistence failed"));
            return toResponse(workflow);
        }

        AgentWorkflow saved = persistenceService.persistCompleted(workflow);

        // Handle reconciliation recommendation — reconciliation service uses
        // its own transaction and performs independent Java-side provider
        // verification before any local payment-state change.
        if ("RECONCILE_LOCAL_ORDER".equals(result.summary())) {
            UUID attemptId = extractAttemptIdFromSteps(result.steps());
            if (attemptId != null) {
                ReconciliationSafetyService.ReconciliationResult reconResult =
                        reconciliationService.reconcileIfAuthorized(saved.getOrder().getId(), attemptId);
                if (reconResult.success()) {
                    saved.setStatus("RECONCILED");
                    saved.setSummary("RECONCILED: " + reconResult.message());
                } else {
                    saved.setStatus("ESCALATED");
                    saved.setSummary("RECONCILIATION_FAILED: " + reconResult.message());
                }
                persistenceService.persistCompleted(saved);
            } else {
                saved.setStatus("WAITING_FOR_EVIDENCE");
                saved.setSummary("WAITING_FOR_EVIDENCE: No attempt ID found in verification steps");
                persistenceService.persistCompleted(saved);
            }
        } else if ("WAIT_FOR_WEBHOOK".equals(result.summary())) {
            saved.setStatus("WAITING_FOR_WEBHOOK");
            persistenceService.persistCompleted(saved);
        } else if ("ESCALATE".equals(result.summary())) {
            saved.setStatus("ESCALATED");
            persistenceService.persistCompleted(saved);
        }

        return toResponse(saved);
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
        AgentWorkflow saved = persistenceService.persistRunning(workflow);
        return toResponse(saved);
    }

    /**
     * Check if a DataIntegrityViolationException is caused by the PostgreSQL
     * partial unique index uq_agent_workflows_one_running_per_order.
     * This is the specific constraint that enforces one RUNNING workflow per order.
     * Unrelated database constraint violations are propagated.
     */
    private boolean isWorkflowConcurrencyViolation(DataIntegrityViolationException e) {
        if (e == null) {
            return false;
        }

        // Inspect the exception chain for a PostgreSQL constraint violation
        // referencing the specific constraint name.
        Throwable cause = e.getCause();
        while (cause != null) {
            String message = cause.toString();
            if (message != null && message.contains("uq_agent_workflows_one_running_per_order")) {
                return true;
            }
            cause = cause.getCause();
        }

        // Also check the top-level DataIntegrityViolationException message
        String message = e.getMessage();
        if (message != null && message.contains("uq_agent_workflows_one_running_per_order")) {
            return true;
        }

        return false;
    }
}
