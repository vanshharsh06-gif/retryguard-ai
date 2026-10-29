package com.retryguard_ai.repository;

import com.retryguard_ai.entity.InvestigationHistory;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface InvestigationHistoryRepository extends JpaRepository<InvestigationHistory, UUID> {


    List<InvestigationHistory> findByInvestigation_IdOrderByCreatedAtDesc(UUID investigationId);

    List<InvestigationHistory> findByInvestigation_IdOrderByCreatedAtAscIdAsc(UUID investigationId);
}
