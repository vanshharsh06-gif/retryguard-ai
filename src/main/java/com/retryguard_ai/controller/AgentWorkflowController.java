package com.retryguard_ai.controller;

import com.retryguard_ai.dto.AgentWorkflowResponse;
import com.retryguard_ai.dto.AgentWorkflowListResponse;
import com.retryguard_ai.service.AgentWorkflowService;
import com.retryguard_ai.service.StuckOrderDetectionService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;

@RestController
@RequestMapping("/api/agent")
public class AgentWorkflowController {

    private final AgentWorkflowService workflowService;
    private final StuckOrderDetectionService detectionService;

    public AgentWorkflowController(
            AgentWorkflowService workflowService,
            StuckOrderDetectionService detectionService) {
        this.workflowService = workflowService;
        this.detectionService = detectionService;
    }

    @PostMapping("/workflows/orders/{orderId}")
    public ResponseEntity<AgentWorkflowResponse> runWorkflow(@PathVariable UUID orderId) {
        AgentWorkflowResponse response = workflowService.runWorkflow(orderId);
        return ResponseEntity.ok(response);
    }

    @GetMapping("/workflows/{workflowId}")
    public ResponseEntity<AgentWorkflowResponse> getWorkflow(@PathVariable UUID workflowId) {
        AgentWorkflowResponse response = workflowService.getWorkflow(workflowId);
        return ResponseEntity.ok(response);
    }

    @GetMapping("/workflows")
    public ResponseEntity<List<AgentWorkflowListResponse>> listWorkflows() {
        List<AgentWorkflowResponse> workflows = workflowService.getRecentWorkflows();
        List<AgentWorkflowListResponse> list = workflows.stream()
                .map(w -> new AgentWorkflowListResponse(
                        w.id(), w.orderId(), w.status(), w.summary(),
                        w.startedAt(), w.completedAt(), w.toolCallsUsed()))
                .collect(Collectors.toList());
        return ResponseEntity.ok(list);
    }

    @PostMapping("/scans/run")
    public ResponseEntity<ScanResponse> runManualScan() {
        int processed = detectionService.runManualScan();
        return ResponseEntity.ok(new ScanResponse(processed, "Manual scan completed"));
    }

    public record ScanResponse(int ordersProcessed, String message) {}
}