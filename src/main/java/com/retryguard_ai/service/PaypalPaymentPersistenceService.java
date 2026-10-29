package com.retryguard_ai.service;

import com.retryguard_ai.entity.PaymentAttempt;
import com.retryguard_ai.entity.MerchantOrder;
import com.retryguard_ai.entity.PaymentEvent;
import com.retryguard_ai.exception.PaymentAttemptNotFoundException;
import com.retryguard_ai.exception.PaypalProviderException;
import com.retryguard_ai.paypal.PaypalCaptureResult;
import com.retryguard_ai.paypal.PaypalOrderResult;
import com.retryguard_ai.repository.PaymentAttemptRepository;
import com.retryguard_ai.repository.PaymentEventRepository;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.Locale;
import java.util.UUID;

@Service
public class PaypalPaymentPersistenceService {

    private final PaymentAttemptRepository paymentAttemptRepository;
    private final PaymentEventRepository paymentEventRepository;
    private final ObjectMapper objectMapper;

    public PaypalPaymentPersistenceService(
            PaymentAttemptRepository paymentAttemptRepository,
            PaymentEventRepository paymentEventRepository,
            ObjectMapper objectMapper) {
        this.paymentAttemptRepository = paymentAttemptRepository;
        this.paymentEventRepository = paymentEventRepository;
        this.objectMapper = objectMapper;
    }

    @Transactional
    public PaymentAttempt recordOrder(UUID attemptId, PaypalOrderResult result) {
        PaymentAttempt attempt = findAttempt(attemptId, false);
        attempt.setProviderPaymentReference(result.orderId());
        attempt.setProviderStatus(result.status());
        attempt.setPaymentStatus(PaymentAttempt.PaymentStatus.PENDING);
        return paymentAttemptRepository.save(attempt);
    }

    @Transactional
    public PaymentAttempt recordCapture(UUID attemptId, PaypalCaptureResult result) {
        PaymentAttempt attempt = findAttempt(attemptId, true);
        validateCapture(attempt, result);

        PaymentAttempt.PaymentStatus nextStatus = mapStatus(result.status());
        PaymentAttempt.PaymentStatus currentStatus = attempt.getPaymentStatus();
        if (currentStatus != PaymentAttempt.PaymentStatus.SUCCEEDED
                && !((currentStatus == PaymentAttempt.PaymentStatus.FAILED
                        || currentStatus == PaymentAttempt.PaymentStatus.CANCELLED)
                        && (nextStatus == PaymentAttempt.PaymentStatus.PENDING
                                || nextStatus == PaymentAttempt.PaymentStatus.UNKNOWN))) {
            attempt.setPaymentStatus(nextStatus);
            attempt.setProviderStatus(result.status());
        }
        attempt.setProviderCaptureReference(result.captureId());
        if (nextStatus == PaymentAttempt.PaymentStatus.SUCCEEDED) {
            attempt.getOrder().setOrderStatus(MerchantOrder.OrderStatus.PAID);
        }
        PaymentAttempt savedAttempt = paymentAttemptRepository.save(attempt);
        paymentEventRepository.save(captureResponseEvent(attempt, result));
        return savedAttempt;
    }

    private PaymentEvent captureResponseEvent(
            PaymentAttempt attempt, PaypalCaptureResult result) {
        PaymentEvent event = new PaymentEvent();
        event.setPaymentAttempt(attempt);
        event.setProvider(attempt.getProvider());
        event.setEventType("PAYMENT.CAPTURE.RESPONSE");
        event.setSourceType(PaymentEvent.SourceType.PROVIDER);
        event.setVerificationStatus(PaymentEvent.VerificationStatus.NOT_APPLICABLE);
        event.setProcessingStatus(PaymentEvent.ProcessingStatus.PROCESSED);
        event.setProcessedAt(OffsetDateTime.now());
        try {
            event.setPayload(objectMapper.writeValueAsString(result));
        } catch (JacksonException exception) {
            throw new IllegalStateException(
                    "PayPal capture response could not be recorded.", exception);
        }
        return event;
    }

    private void validateCapture(PaymentAttempt attempt, PaypalCaptureResult result) {
        if (result == null
                || result.captureId() == null || result.captureId().isBlank()
                || result.status() == null || result.status().isBlank()
                || result.orderId() == null
                || !result.orderId().equals(attempt.getProviderPaymentReference())
                || result.amount() == null
                || result.currency() == null
                || !result.currency().equalsIgnoreCase(attempt.getCurrency())) {
            throw invalidCaptureResponse();
        }
        try {
            if (new BigDecimal(result.amount()).compareTo(attempt.getAmount()) != 0) {
                throw invalidCaptureResponse();
            }
        } catch (NumberFormatException exception) {
            throw invalidCaptureResponse();
        }
        String existingCaptureId = attempt.getProviderCaptureReference();
        if (existingCaptureId != null && !existingCaptureId.equals(result.captureId())) {
            throw invalidCaptureResponse();
        }
    }

    private PaypalProviderException invalidCaptureResponse() {
        return new PaypalProviderException(
                HttpStatus.BAD_GATEWAY,
                "PayPal returned a capture that does not match the payment attempt.");
    }

    private PaymentAttempt findAttempt(UUID attemptId, boolean lockForUpdate) {
        return (lockForUpdate
                ? paymentAttemptRepository.findByIdForUpdate(attemptId)
                : paymentAttemptRepository.findById(attemptId))
                .orElseThrow(() -> new PaymentAttemptNotFoundException(attemptId));
    }

    private PaymentAttempt.PaymentStatus mapStatus(String providerStatus) {
        if (providerStatus == null) {
            return PaymentAttempt.PaymentStatus.UNKNOWN;
        }
        return switch (providerStatus.toUpperCase(Locale.ROOT)) {
            case "COMPLETED" -> PaymentAttempt.PaymentStatus.SUCCEEDED;
            case "PENDING" -> PaymentAttempt.PaymentStatus.PENDING;
            case "DECLINED", "DENIED", "FAILED" -> PaymentAttempt.PaymentStatus.FAILED;
            default -> PaymentAttempt.PaymentStatus.UNKNOWN;
        };
    }
}
