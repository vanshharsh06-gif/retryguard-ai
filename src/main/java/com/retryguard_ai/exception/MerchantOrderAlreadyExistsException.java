package com.retryguard_ai.exception;

public class MerchantOrderAlreadyExistsException extends RuntimeException {

    public MerchantOrderAlreadyExistsException(String merchantOrderReference) {
        super("An order with reference '" + merchantOrderReference + "' already exists.");
    }
}
