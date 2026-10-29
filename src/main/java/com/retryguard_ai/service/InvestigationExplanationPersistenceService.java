package com.retryguard_ai.service;

import com.retryguard_ai.exception.InvestigationNotFoundException;
import com.retryguard_ai.repository.InvestigationRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

@Service
public class InvestigationExplanationPersistenceService {

    private final InvestigationRepository investigationRepository;

    public InvestigationExplanationPersistenceService(
            InvestigationRepository investigationRepository) {
        this.investigationRepository = investigationRepository;
    }

    @Transactional
    public void saveExplanation(UUID investigationId, String explanation) {
        if (investigationRepository.updateExplanationById(investigationId, explanation) == 0) {
            throw new InvestigationNotFoundException(investigationId);
        }
    }
}
