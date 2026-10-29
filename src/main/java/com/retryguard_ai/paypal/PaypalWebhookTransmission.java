package com.retryguard_ai.paypal;

public record PaypalWebhookTransmission(
        String authAlgo,
        String certUrl,
        String transmissionId,
        String transmissionSignature,
        String transmissionTime) {

    public boolean isComplete() {
        return present(authAlgo) && present(certUrl) && present(transmissionId)
                && present(transmissionSignature) && present(transmissionTime);
    }

    private boolean present(String value) {
        return value != null && !value.isBlank();
    }
}
