package com.retryguard_ai.dto;

import java.math.BigDecimal;
import java.util.UUID;

public record PaypalPaymentResponse(
        UUID paymentAttemptId,
        BigDecimal amount,
        String currency,
        String paymentStatus,
        String providerStatus,
        String providerOrderId,
        String providerCaptureId,
        String approvalUrl) {
}
