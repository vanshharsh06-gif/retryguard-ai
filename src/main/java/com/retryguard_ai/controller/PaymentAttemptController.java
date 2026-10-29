package com.retryguard_ai.controller;

import com.retryguard_ai.dto.CreatePaymentAttemptRequest;
import com.retryguard_ai.dto.CreatePaymentAttemptResponse;
import com.retryguard_ai.entity.PaymentAttempt;
import com.retryguard_ai.service.PaymentAttemptService;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

@RestController
@RequestMapping("/api/orders/{orderId}/payment-attempts")
public class PaymentAttemptController {

    private final PaymentAttemptService paymentAttemptService;

    public PaymentAttemptController(PaymentAttemptService paymentAttemptService) {
        this.paymentAttemptService = paymentAttemptService;
    }

    @PostMapping
    public ResponseEntity<CreatePaymentAttemptResponse> createPaymentAttempt(
            @PathVariable UUID orderId,
            @Valid @RequestBody CreatePaymentAttemptRequest request) {
        PaymentAttempt paymentAttempt =
                paymentAttemptService.createPaymentAttempt(orderId, request);
        CreatePaymentAttemptResponse response = new CreatePaymentAttemptResponse(
                paymentAttempt.getId(),
                paymentAttempt.getOrder().getId(),
                paymentAttempt.getProvider(),
                paymentAttempt.getAmount(),
                paymentAttempt.getCurrency(),
                paymentAttempt.getPaymentStatus().name(),
                paymentAttempt.getProviderPaymentReference(),
                paymentAttempt.getCreatedAt());

        return ResponseEntity.status(HttpStatus.CREATED).body(response);
    }
}
