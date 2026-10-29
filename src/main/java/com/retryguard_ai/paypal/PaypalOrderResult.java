package com.retryguard_ai.paypal;

public record PaypalOrderResult(String orderId, String status, String approvalUrl) {
}
