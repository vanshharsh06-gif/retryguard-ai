package com.retryguard_ai.ai;

public class InvalidExplanationResponseException extends ExplanationProviderException {

    public InvalidExplanationResponseException() {
        super("Gemini returned an invalid explanation.");
    }
}
