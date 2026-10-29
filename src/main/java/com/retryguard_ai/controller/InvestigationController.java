package com.retryguard_ai.controller;

import com.retryguard_ai.dto.InvestigationResponse;
import com.retryguard_ai.service.InvestigationService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;
import java.util.List;

@RestController
@RequestMapping("/api/orders/{orderId}/investigations")
public class InvestigationController {

    private final InvestigationService investigationService;

    public InvestigationController(InvestigationService investigationService) {
        this.investigationService = investigationService;
    }

    @GetMapping
    public ResponseEntity<List<InvestigationResponse>> listForOrder(
            @PathVariable UUID orderId) {
        return ResponseEntity.ok(investigationService.getInvestigationsForOrder(orderId));
    }

    @PostMapping
    public ResponseEntity<InvestigationResponse> investigate(@PathVariable UUID orderId) {
        return ResponseEntity.ok(investigationService.investigate(orderId));
    }
}
