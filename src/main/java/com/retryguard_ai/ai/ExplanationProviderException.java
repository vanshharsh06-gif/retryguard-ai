package com.retryguard_ai.ai;

public abstract class ExplanationProviderException extends RuntimeException {

    protected ExplanationProviderException(String message) {
        super(message);
    }
}
