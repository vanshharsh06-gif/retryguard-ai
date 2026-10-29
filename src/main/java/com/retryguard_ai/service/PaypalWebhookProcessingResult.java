package com.retryguard_ai.service;

public record PaypalWebhookProcessingResult(boolean verified, boolean duplicate) {
}
