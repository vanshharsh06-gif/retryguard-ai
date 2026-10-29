package com.retryguard_ai.exception;

public class InvalidPaymentEventPayloadException extends RuntimeException {

    public InvalidPaymentEventPayloadException(String message) {
        super(message);
    }

    public InvalidPaymentEventPayloadException(String message, Throwable cause) {
        super(message, cause);
    }
}
