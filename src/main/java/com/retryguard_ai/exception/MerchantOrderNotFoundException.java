package com.retryguard_ai.exception;

import java.util.UUID;

public class MerchantOrderNotFoundException extends RuntimeException {

    public MerchantOrderNotFoundException(UUID orderId) {
        super("Merchant order '" + orderId + "' was not found.");
    }
}
