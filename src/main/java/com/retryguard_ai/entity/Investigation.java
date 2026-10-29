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
@Table(name = "investigations")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
public class Investigation {

  @Id
  @GeneratedValue(strategy = GenerationType.UUID)
  private UUID id;

  @ManyToOne(fetch = FetchType.LAZY, optional = false)
  @JoinColumn(name = "order_id", nullable = false)
  private MerchantOrder order;

  @ManyToOne(fetch = FetchType.LAZY)
  @JoinColumn(name = "payment_attempt_id")
  private PaymentAttempt paymentAttempt;

  @Enumerated(EnumType.STRING)
  @Column(nullable = false, length = 30)
  private Decision decision;

  @Column(name = "decision_reason", nullable = false, columnDefinition = "text")
  private String decisionReason;

  @JdbcTypeCode(SqlTypes.JSON)
  @Column(name = "evidence_snapshot", nullable = false, columnDefinition = "jsonb")
  private String evidenceSnapshot = "{}";

  @JdbcTypeCode(SqlTypes.JSON)
  @Column(name = "findings", nullable = false, columnDefinition = "jsonb")
  private String findings = "[]";

  @Column(name = "rules_version", nullable = false, length = 30)
  private String rulesVersion = "1.0";

  @Column(columnDefinition = "text")
  private String explanation;

  @CreationTimestamp
  @Column(name = "created_at", nullable = false, updatable = false)
  private OffsetDateTime createdAt;

  public enum Decision {
    WAIT,
    RECONCILE,
    RETRY_ELIGIBLE
  }

  // Manual getters/setters for Lombok compatibility
  public UUID getId() { return id; }
  public void setId(UUID id) { this.id = id; }
  public MerchantOrder getOrder() { return order; }
  public void setOrder(MerchantOrder order) { this.order = order; }
  public PaymentAttempt getPaymentAttempt() { return paymentAttempt; }
  public void setPaymentAttempt(PaymentAttempt paymentAttempt) { this.paymentAttempt = paymentAttempt; }
  public Decision getDecision() { return decision; }
  public void setDecision(Decision decision) { this.decision = decision; }
  public String getDecisionReason() { return decisionReason; }
  public void setDecisionReason(String decisionReason) { this.decisionReason = decisionReason; }
  public String getEvidenceSnapshot() { return evidenceSnapshot; }
  public void setEvidenceSnapshot(String evidenceSnapshot) { this.evidenceSnapshot = evidenceSnapshot; }
  public String getFindings() { return findings; }
  public void setFindings(String findings) { this.findings = findings; }
  public String getRulesVersion() { return rulesVersion; }
  public void setRulesVersion(String rulesVersion) { this.rulesVersion = rulesVersion; }
  public String getExplanation() { return explanation; }
  public void setExplanation(String explanation) { this.explanation = explanation; }
  public OffsetDateTime getCreatedAt() { return createdAt; }
  public void setCreatedAt(OffsetDateTime createdAt) { this.createdAt = createdAt; }
}