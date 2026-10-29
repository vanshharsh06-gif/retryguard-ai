package com.retryguard_ai.service;

import com.retryguard_ai.entity.PaymentEvent;

public record PaymentEventCreationResult(PaymentEvent event, boolean created) {
}
