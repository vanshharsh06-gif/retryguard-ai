package com.retryguard_ai.entity;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.CreationTimestamp;

import java.time.OffsetDateTime;
import java.util.UUID;

@Entity
@Table(name = "investigation_history")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
public class InvestigationHistory {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "investigation_id", nullable = false)
    private Investigation investigation;

    @Column(nullable = false, length = 50)
    private String action;

    @Enumerated(EnumType.STRING)
    @Column(name = "previous_decision", length = 30)
    private Investigation.Decision previousDecision;

    @Enumerated(EnumType.STRING)
    @Column(name = "new_decision", length = 30)
    private Investigation.Decision newDecision;

    @Column(nullable = false, columnDefinition = "text")
    private String reason;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private OffsetDateTime createdAt;

    // Manual getters/setters for Lombok compatibility
    public UUID getId() { return id; }
    public void setId(UUID id) { this.id = id; }
    public Investigation getInvestigation() { return investigation; }
    public void setInvestigation(Investigation investigation) { this.investigation = investigation; }
    public String getAction() { return action; }
    public void setAction(String action) { this.action = action; }
    public Investigation.Decision getPreviousDecision() { return previousDecision; }
    public void setPreviousDecision(Investigation.Decision previousDecision) { this.previousDecision = previousDecision; }
    public Investigation.Decision getNewDecision() { return newDecision; }
    public void setNewDecision(Investigation.Decision newDecision) { this.newDecision = newDecision; }
    public String getReason() { return reason; }
    public void setReason(String reason) { this.reason = reason; }
    public OffsetDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(OffsetDateTime createdAt) { this.createdAt = createdAt; }
}