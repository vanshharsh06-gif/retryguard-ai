package com.retryguard_ai.repository;

import com.retryguard_ai.entity.Investigation;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface InvestigationRepository extends JpaRepository<Investigation, UUID> {

    List<Investigation> findByOrder_IdOrderByCreatedAtDesc(UUID orderId);

    @EntityGraph(attributePaths = "order")
    Optional<Investigation> findFirstByOrder_IdOrderByCreatedAtDescIdDesc(UUID orderId);

    @EntityGraph(attributePaths = "order")
    List<Investigation> findTop50ByOrder_IdOrderByCreatedAtDescIdDesc(UUID orderId);

    @EntityGraph(attributePaths = "order")
    @Override
    Optional<Investigation> findById(UUID id);

    @Modifying
    @Query("update Investigation i set i.explanation = :explanation where i.id = :investigationId")
    int updateExplanationById(
            @Param("investigationId") UUID investigationId,
            @Param("explanation") String explanation);
}
