package com.retryguard_ai.controller;

import com.retryguard_ai.dto.InvestigationHistoryResponse;
import com.retryguard_ai.dto.InvestigationResponse;
import com.retryguard_ai.service.InvestigationService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/investigations")
public class InvestigationQueryController {

    private final InvestigationService investigationService;

    public InvestigationQueryController(InvestigationService investigationService) {
        this.investigationService = investigationService;
    }

    @GetMapping("/{investigationId}")
    public ResponseEntity<InvestigationResponse> getInvestigation(
            @PathVariable UUID investigationId) {
        return ResponseEntity.ok(investigationService.getInvestigation(investigationId));
    }

    @GetMapping("/{investigationId}/history")
    public ResponseEntity<List<InvestigationHistoryResponse>> getHistory(
            @PathVariable UUID investigationId) {
        return ResponseEntity.ok(investigationService.getInvestigationHistory(investigationId));
    }
}
