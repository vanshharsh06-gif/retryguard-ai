package com.retryguard_ai.controller;

import com.retryguard_ai.dto.OrderTimelineResponse;
import com.retryguard_ai.service.PaymentTimelineService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

@RestController
@RequestMapping("/api/orders/{orderId}/timeline")
public class PaymentTimelineController {

    private final PaymentTimelineService paymentTimelineService;

    public PaymentTimelineController(PaymentTimelineService paymentTimelineService) {
        this.paymentTimelineService = paymentTimelineService;
    }

    @GetMapping
    public ResponseEntity<OrderTimelineResponse> getOrderTimeline(
            @PathVariable UUID orderId) {
        return ResponseEntity.ok(paymentTimelineService.getOrderTimeline(orderId));
    }
}
