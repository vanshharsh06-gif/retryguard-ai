package com.retryguard_ai.service;

import com.retryguard_ai.entity.AgentWorkflow;
import com.retryguard_ai.entity.AgentWorkflowStep;
import com.retryguard_ai.repository.AgentWorkflowRepository;
import com.retryguard_ai.repository.AgentWorkflowStepRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;

/**
 * Persistence service for agent workflows.
 *
 * This bean is injected into {@link AgentWorkflowService} so that
 * {@link #persistError(AgentWorkflow, Exception)} is invoked through the
 * Spring transaction proxy, ensuring {@code PROPAGATION_REQUIRES_NEW} is
 * actually applied. Self-invocation within {@link AgentWorkflowService} would
 * bypass the proxy and the {@code REQUIRES_NEW} semantics would be lost.
 */
@Service
public class AgentWorkflowPersistenceService {

    private final AgentWorkflowRepository workflowRepository;
    private final AgentWorkflowStepRepository stepRepository;

    public AgentWorkflowPersistenceService(
            AgentWorkflowRepository workflowRepository,
            AgentWorkflowStepRepository stepRepository) {
        this.workflowRepository = workflowRepository;
        this.stepRepository = stepRepository;
    }

    /**
     * Persist a newly created RUNNING workflow and commit it immediately.
     * This method runs in its own short transaction so the database row is
     * visible before any external HTTP call is made.
     */
    @Transactional
    public AgentWorkflow persistRunning(AgentWorkflow workflow) {
        return workflowRepository.saveAndFlush(workflow);
    }

    /**
     * Persist ERROR status independently using {@code PROPAGATION_REQUIRES_NEW}.
     * Because this method lives in a separate Spring-managed bean, the
     * transactional proxy is used and {@code REQUIRES_NEW} is honored even
     * when called from {@link AgentWorkflowService#runWorkflow} after an
     * exception. The outer transaction (if any) can roll back without
     * erasing this failure record.
     *
     * Error messages are sanitized so that credentials, tokens, or provider
     * secrets are never stored in the workflow record.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public AgentWorkflow persistError(AgentWorkflow workflow, Exception e) {
        workflow.setStatus("ERROR");
        workflow.setSummary("Workflow execution failed: " + sanitizeErrorMessage(e.getMessage()));
        workflow.setCompletedAt(OffsetDateTime.now());
        return workflowRepository.saveAndFlush(workflow);
    }

    /**
     * Persist completed workflow status and counters in a short transaction.
     */
    @Transactional
    public AgentWorkflow persistCompleted(AgentWorkflow workflow) {
        return workflowRepository.save(workflow);
    }

    /**
     * Persist a workflow step in its own short transaction.
     */
    @Transactional
    public void persistStep(AgentWorkflowStep step) {
        stepRepository.save(step);
    }

    /**
     * Sanitize error messages to avoid leaking credentials, tokens, or
     * provider secrets into the stored workflow record.
     */
    private String sanitizeErrorMessage(String message) {
        if (message == null) {
            return "unknown error";
        }
        return message
                .replaceAll("(?i)(bearer\\s+)[^\\s]+", "$1***")
                .replaceAll("(?i)(api[-_]?key[=:]+)[^\\s&]+", "$1***")
                .replaceAll("(?i)(password[=:]+)[^\\s&]+", "$1***")
                .replaceAll("(?i)(secret[=:]+)[^\\s&]+", "$1***")
                .replaceAll("(?i)(token[=:]+)[^\\s&]+", "$1***");
    }
}
