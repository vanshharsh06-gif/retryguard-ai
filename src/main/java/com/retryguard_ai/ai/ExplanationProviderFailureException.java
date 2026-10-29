package com.retryguard_ai.ai;

public class ExplanationProviderFailureException extends ExplanationProviderException {

    public ExplanationProviderFailureException() {
        super("Gemini could not provide an explanation.");
    }
}
