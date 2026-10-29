package com.retryguard_ai.repository;

import com.retryguard_ai.entity.AgentWorkflow;
import com.retryguard_ai.entity.AgentWorkflowStep;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.UUID;

@Repository
public interface AgentWorkflowStepRepository extends JpaRepository<AgentWorkflowStep, UUID> {

    @Query("SELECT s FROM AgentWorkflowStep s WHERE s.workflow.id = :workflowId ORDER BY s.createdAt ASC, s.id ASC")
    List<AgentWorkflowStep> findByWorkflowIdOrderByCreatedAtAsc(@Param("workflowId") UUID workflowId);
}