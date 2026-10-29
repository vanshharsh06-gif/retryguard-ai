package com.retryguard_ai.exception;

import org.springframework.http.HttpStatus;

public class PaypalProviderException extends RuntimeException {

    private final HttpStatus status;

    public PaypalProviderException(HttpStatus status, String safeMessage) {
        super(safeMessage);
        this.status = status;
    }

    public HttpStatus getStatus() {
        return status;
    }
}
