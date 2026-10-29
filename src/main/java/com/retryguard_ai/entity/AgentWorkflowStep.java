package com.retryguard_ai.entity;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.OffsetDateTime;
import java.util.UUID;

@Entity
@Table(name = "agent_workflow_steps")
@Getter
@Setter
@AllArgsConstructor
@NoArgsConstructor
public class AgentWorkflowStep {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "workflow_id", nullable = false)
    private AgentWorkflow workflow;

    @Column(name = "step_type", nullable = false, length = 50)
    private String stepType;

    @Column(name = "step_name", nullable = false, length = 100)
    private String stepName;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "input_summary", columnDefinition = "jsonb")
    private String inputSummary;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "output_summary", columnDefinition = "jsonb")
    private String outputSummary;

    @Column(name = "error", columnDefinition = "text")
    private String error;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private OffsetDateTime createdAt;

    // Manual getters/setters for Lombok compatibility
    public UUID getId() { return id; }
    public void setId(UUID id) { this.id = id; }
    public AgentWorkflow getWorkflow() { return workflow; }
    public void setWorkflow(AgentWorkflow workflow) { this.workflow = workflow; }
    public String getStepType() { return stepType; }
    public void setStepType(String stepType) { this.stepType = stepType; }
    public String getStepName() { return stepName; }
    public void setStepName(String stepName) { this.stepName = stepName; }
    public String getInputSummary() { return inputSummary; }
    public void setInputSummary(String inputSummary) { this.inputSummary = inputSummary; }
    public String getOutputSummary() { return outputSummary; }
    public void setOutputSummary(String outputSummary) { this.outputSummary = outputSummary; }
    public String getError() { return error; }
    public void setError(String error) { this.error = error; }
    public OffsetDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(OffsetDateTime createdAt) { this.createdAt = createdAt; }
}