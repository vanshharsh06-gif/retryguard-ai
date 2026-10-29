package com.retryguard_ai.controller;

import com.retryguard_ai.dto.InvestigationExplanationResponse;
import com.retryguard_ai.service.InvestigationExplanationService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

@RestController
@RequestMapping("/api/investigations")
public class InvestigationExplanationController {

    private final InvestigationExplanationService explanationService;

    public InvestigationExplanationController(InvestigationExplanationService explanationService) {
        this.explanationService = explanationService;
    }

    @PostMapping("/{investigationId}/explanation")
    public ResponseEntity<InvestigationExplanationResponse> explain(
            @PathVariable UUID investigationId) {
        return ResponseEntity.ok(explanationService.explain(investigationId));
    }
}
