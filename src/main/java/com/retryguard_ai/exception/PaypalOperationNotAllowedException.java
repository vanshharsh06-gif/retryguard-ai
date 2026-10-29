package com.retryguard_ai.exception;

public class PaypalOperationNotAllowedException extends RuntimeException {

    public PaypalOperationNotAllowedException(String message) {
        super(message);
    }
}
