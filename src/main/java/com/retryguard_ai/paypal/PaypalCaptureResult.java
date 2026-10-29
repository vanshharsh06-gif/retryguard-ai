package com.retryguard_ai.paypal;

public record PaypalCaptureResult(
        String orderId, String captureId, String status, String amount, String currency) {
}
