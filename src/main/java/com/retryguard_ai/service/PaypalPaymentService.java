package com.retryguard_ai.service;

import com.retryguard_ai.entity.PaymentAttempt;
import com.retryguard_ai.exception.PaypalOperationNotAllowedException;
import com.retryguard_ai.paypal.PaypalCaptureResult;
import com.retryguard_ai.paypal.PaypalClient;
import com.retryguard_ai.paypal.PaypalOrderResult;
import com.retryguard_ai.repository.PaymentAttemptRepository;
import org.springframework.stereotype.Service;

import java.util.UUID;

@Service
public class PaypalPaymentService {

    private static final String PAYPAL_SANDBOX = "PAYPAL_SANDBOX";

    private final PaymentAttemptRepository paymentAttemptRepository;
    private final PaypalClient paypalClient;
    private final PaypalPaymentPersistenceService persistenceService;

    public PaypalPaymentService(
            PaymentAttemptRepository paymentAttemptRepository,
            PaypalClient paypalClient,
            PaypalPaymentPersistenceService persistenceService) {
        this.paymentAttemptRepository = paymentAttemptRepository;
        this.paypalClient = paypalClient;
        this.persistenceService = persistenceService;
    }

    public PaypalPaymentResult createOrder(UUID attemptId) {
        PaymentAttempt attempt = findAttempt(attemptId);
        validateProvider(attempt);
        if (attempt.getProviderPaymentReference() != null) {
            throw new PaypalOperationNotAllowedException(
                    "A PayPal order already exists for this payment attempt.");
        }
        PaypalOrderResult providerResult = paypalClient.createOrder(attempt);
        PaymentAttempt updated = persistenceService.recordOrder(attemptId, providerResult);
        return result(updated, providerResult.approvalUrl());
    }

    public PaypalPaymentResult captureOrder(UUID attemptId) {
        PaymentAttempt attempt = findAttempt(attemptId);
        validateProvider(attempt);
        String providerOrderId = attempt.getProviderPaymentReference();
        if (providerOrderId == null || providerOrderId.isBlank()) {
            throw new PaypalOperationNotAllowedException(
                    "Create a PayPal order before requesting capture.");
        }
        if (attempt.getPaymentStatus() == PaymentAttempt.PaymentStatus.SUCCEEDED) {
            throw new PaypalOperationNotAllowedException(
                    "This payment attempt is already marked successful.");
        }

        PaypalCaptureResult providerResult =
                paypalClient.captureOrder(attemptId, providerOrderId);
        PaymentAttempt updated = persistenceService.recordCapture(attemptId, providerResult);
        return result(updated, null);
    }

    public void validateReturn(UUID attemptId, String returnedOrderToken) {
        validateRedirectOrder(attemptId, returnedOrderToken);
    }

    public void validateCancellation(UUID attemptId, String returnedOrderToken) {
        validateRedirectOrder(attemptId, returnedOrderToken);
    }

    private void validateRedirectOrder(UUID attemptId, String returnedOrderToken) {
        PaymentAttempt attempt = findAttempt(attemptId);
        validateProvider(attempt);
        String storedOrderId = attempt.getProviderPaymentReference();
        if (returnedOrderToken == null || returnedOrderToken.isBlank()
                || storedOrderId == null || !storedOrderId.equals(returnedOrderToken)) {
            throw new PaypalOperationNotAllowedException(
                    "The PayPal redirect could not be matched to this payment attempt.");
        }
    }

    private PaymentAttempt findAttempt(UUID attemptId) {
        return paymentAttemptRepository.findWithOrderById(attemptId)
                .orElseThrow(() ->
                        new com.retryguard_ai.exception.PaymentAttemptNotFoundException(attemptId));
    }

    private void validateProvider(PaymentAttempt attempt) {
        if (!PAYPAL_SANDBOX.equals(attempt.getProvider())) {
            throw new PaypalOperationNotAllowedException(
                    "PayPal Sandbox operations require a PAYPAL_SANDBOX payment attempt.");
        }
    }

    private PaypalPaymentResult result(PaymentAttempt attempt, String approvalUrl) {
        return new PaypalPaymentResult(
                attempt.getId(),
                attempt.getAmount(),
                attempt.getCurrency(),
                attempt.getPaymentStatus().name(),
                attempt.getProviderStatus(),
                attempt.getProviderPaymentReference(),
                attempt.getProviderCaptureReference(),
                approvalUrl);
    }

    public record PaypalPaymentResult(
            UUID paymentAttemptId,
            java.math.BigDecimal amount,
            String currency,
            String paymentStatus,
            String providerStatus,
            String providerOrderId,
            String providerCaptureId,
            String approvalUrl) {
    }
}
