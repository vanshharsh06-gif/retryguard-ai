package com.retryguard_ai.ai;

public class ExplanationProviderUnavailableException extends ExplanationProviderException {

    public ExplanationProviderUnavailableException() {
        super("Gemini explanation is not configured.");
    }
}
