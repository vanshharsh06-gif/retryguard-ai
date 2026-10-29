package com.retryguard_ai.repository;

import com.retryguard_ai.entity.AgentWorkflow;
import com.retryguard_ai.entity.MerchantOrder;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface AgentWorkflowRepository extends JpaRepository<AgentWorkflow, UUID> {

    Optional<AgentWorkflow> findFirstByOrder_IdOrderByStartedAtDescIdDesc(UUID orderId);

    @Query("SELECT w FROM AgentWorkflow w WHERE w.order.id = :orderId ORDER BY w.startedAt DESC, w.id DESC")
    List<AgentWorkflow> findByOrderIdOrderByStartedAtDesc(@Param("orderId") UUID orderId);

    @Query("SELECT w FROM AgentWorkflow w ORDER BY w.startedAt DESC, w.id DESC")
    List<AgentWorkflow> findTop50ByOrderByStartedAtDesc();

    @Query("SELECT COUNT(w) FROM AgentWorkflow w WHERE w.order.id = :orderId AND w.status = 'RUNNING'")
    long countRunningByOrderId(@Param("orderId") UUID orderId);

    @Query("SELECT w FROM AgentWorkflow w WHERE w.order.id = :orderId AND w.status = 'RUNNING'")
    Optional<AgentWorkflow> findRunningByOrderId(@Param("orderId") UUID orderId);
}