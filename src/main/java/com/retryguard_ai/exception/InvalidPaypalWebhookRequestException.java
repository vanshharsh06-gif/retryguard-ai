package com.retryguard_ai.exception;

public class InvalidPaypalWebhookRequestException extends RuntimeException {

    public InvalidPaypalWebhookRequestException(String message) {
        super(message);
    }
}
