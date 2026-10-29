package com.retryguard_ai.exception;

import java.util.UUID;

public class InvestigationNotFoundException extends RuntimeException {

    public InvestigationNotFoundException(UUID investigationId) {
        super("Investigation '" + investigationId + "' was not found.");
    }
}
