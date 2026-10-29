package com.retryguard_ai.exception;

import java.util.UUID;

public class PaymentAttemptNotFoundException extends RuntimeException {

    public PaymentAttemptNotFoundException(UUID paymentAttemptId) {
        super("Payment attempt '" + paymentAttemptId + "' was not found.");
    }
}
