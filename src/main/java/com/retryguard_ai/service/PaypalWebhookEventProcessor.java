package com.retryguard_ai.service;

import com.retryguard_ai.entity.PaymentAttempt;
import com.retryguard_ai.entity.PaymentEvent;
import com.retryguard_ai.entity.MerchantOrder;
import com.retryguard_ai.exception.InvalidPaypalWebhookRequestException;
import com.retryguard_ai.exception.ProviderEventIdConflictException;
import com.retryguard_ai.repository.PaymentAttemptRepository;
import com.retryguard_ai.repository.PaymentEventRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.JsonNode;

import java.nio.charset.StandardCharsets;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;

@Service
public class PaypalWebhookEventProcessor {

    private static final String PROVIDER = "PAYPAL_SANDBOX";
    private static final int MAX_PAYLOAD_BYTES = 64 * 1024;

    private final PaymentAttemptRepository paymentAttemptRepository;
    private final PaymentEventRepository paymentEventRepository;

    public PaypalWebhookEventProcessor(
            PaymentAttemptRepository paymentAttemptRepository,
            PaymentEventRepository paymentEventRepository) {
        this.paymentAttemptRepository = paymentAttemptRepository;
        this.paymentEventRepository = paymentEventRepository;
    }

    @Transactional
    public boolean recordVerifiedEvent(JsonNode webhookEvent, String originalPayload) {
        String eventId = text(webhookEvent, "id");
        String eventType = text(webhookEvent, "event_type");
        if (eventId == null || eventId.length() > 200
                || eventType == null || eventType.length() > 100
                || originalPayload.getBytes(StandardCharsets.UTF_8).length > MAX_PAYLOAD_BYTES) {
            throw new InvalidPaypalWebhookRequestException("Invalid webhook event.");
        }

        paymentEventRepository.lockIdempotencyKey(PROVIDER, eventId);
        var existing = paymentEventRepository.findByProviderAndProviderEventId(PROVIDER, eventId);
        if (existing.isPresent()) {
            PaymentEvent original = existing.get();
            if (original.getSourceType() != PaymentEvent.SourceType.PROVIDER
                    || original.getVerificationStatus() != PaymentEvent.VerificationStatus.VERIFIED) {
                throw new ProviderEventIdConflictException();
            }
            return false;
        }

        String captureId = nestedText(webhookEvent, "resource", "id");
        String orderId = nestedText(
                webhookEvent, "resource", "supplementary_data", "related_ids", "order_id");
        PaymentAttempt.PaymentStatus mappedStatus = mappedStatus(eventType);
        AttemptResolution resolution = resolveAttempt(
                captureId, orderId, webhookEvent, mappedStatus);
        PaymentAttempt attempt = resolution.attempt();
        PaymentEvent event = new PaymentEvent();
        event.setPaymentAttempt(attempt);
        event.setProvider(PROVIDER);
        event.setProviderEventId(eventId);
        event.setEventType(eventType);
        event.setSourceType(PaymentEvent.SourceType.PROVIDER);
        event.setVerificationStatus(PaymentEvent.VerificationStatus.VERIFIED);
        event.setProcessingStatus(PaymentEvent.ProcessingStatus.RECEIVED);
        event.setPayload(originalPayload);

        if (!resolution.valid()) {
            event.setProcessingStatus(PaymentEvent.ProcessingStatus.FAILED);
        } else if (attempt != null && mappedStatus != null) {
            applyStatus(attempt, mappedStatus, eventType, captureId);
            paymentAttemptRepository.save(attempt);
            event.setProcessingStatus(PaymentEvent.ProcessingStatus.PROCESSED);
            event.setProcessedAt(OffsetDateTime.now());
        }
        paymentEventRepository.save(event);
        return true;
    }

    private AttemptResolution resolveAttempt(
            String captureId, String orderId, JsonNode event,
            PaymentAttempt.PaymentStatus mappedStatus) {
        List<PaymentAttempt> orderMatches = orderId == null
                ? List.of()
                : paymentAttemptRepository.findByProviderAndProviderPaymentReference(
                        PROVIDER, orderId);
        List<PaymentAttempt> captureMatches = captureId == null
                ? List.of()
                : paymentAttemptRepository.findByProviderAndProviderCaptureReference(
                        PROVIDER, captureId);

        PaymentAttempt candidate = null;
        boolean valid = true;
        if (orderId != null) {
            if (orderMatches.size() == 1) {
                candidate = orderMatches.get(0);
            } else if (orderMatches.size() > 1) {
                valid = false;
            } else if (captureMatches.size() == 1) {
                candidate = captureMatches.get(0);
                valid = false;
            }
        } else if (captureMatches.size() == 1) {
            candidate = captureMatches.get(0);
            if (mappedStatus == PaymentAttempt.PaymentStatus.SUCCEEDED) {
                valid = false;
            }
        } else if (captureMatches.size() > 1) {
            valid = false;
        }

        if (candidate != null && captureId != null) {
            if (captureMatches.size() > 1
                    || (captureMatches.size() == 1
                            && !captureMatches.get(0).getId().equals(candidate.getId()))
                    || (candidate.getProviderCaptureReference() != null
                            && !candidate.getProviderCaptureReference().equals(captureId))) {
                valid = false;
            }
        } else if (candidate != null
                && mappedStatus == PaymentAttempt.PaymentStatus.SUCCEEDED) {
            valid = false;
        }
        if (orderId == null && mappedStatus == PaymentAttempt.PaymentStatus.SUCCEEDED) {
            valid = false;
        }

        if (candidate != null) {
            candidate = paymentAttemptRepository.findByIdForUpdate(candidate.getId())
                    .orElse(null);
            if (candidate == null
                    || !PROVIDER.equals(candidate.getProvider())
                    || (orderId != null
                            && !orderId.equals(candidate.getProviderPaymentReference()))
                    || (captureId != null
                            && candidate.getProviderCaptureReference() != null
                            && !captureId.equals(candidate.getProviderCaptureReference()))) {
                valid = false;
            }
        }

        if (candidate != null && mappedStatus == PaymentAttempt.PaymentStatus.SUCCEEDED
                && !matchesAmountAndCurrency(candidate, event)) {
            valid = false;
        }
        return new AttemptResolution(candidate, valid);
    }

    private boolean matchesAmountAndCurrency(PaymentAttempt attempt, JsonNode event) {
        String amount = nestedText(event, "resource", "amount", "value");
        String currency = nestedText(event, "resource", "amount", "currency_code");
        if (amount == null || currency == null
                || !attempt.getCurrency().equalsIgnoreCase(currency)) {
            return false;
        }
        try {
            return new BigDecimal(amount).compareTo(attempt.getAmount()) == 0;
        } catch (NumberFormatException exception) {
            return false;
        }
    }

    private PaymentAttempt.PaymentStatus mappedStatus(String eventType) {
        return switch (eventType) {
            case "PAYMENT.CAPTURE.COMPLETED" -> PaymentAttempt.PaymentStatus.SUCCEEDED;
            case "PAYMENT.CAPTURE.PENDING" -> PaymentAttempt.PaymentStatus.PENDING;
            case "PAYMENT.CAPTURE.DENIED" -> PaymentAttempt.PaymentStatus.FAILED;
            default -> null;
        };
    }

    private void applyStatus(
            PaymentAttempt attempt, PaymentAttempt.PaymentStatus newStatus,
            String eventType, String captureId) {
        PaymentAttempt.PaymentStatus currentStatus = attempt.getPaymentStatus();
        if (currentStatus == PaymentAttempt.PaymentStatus.SUCCEEDED
                && newStatus != PaymentAttempt.PaymentStatus.SUCCEEDED) {
            return;
        }
        if ((currentStatus == PaymentAttempt.PaymentStatus.FAILED
                || currentStatus == PaymentAttempt.PaymentStatus.CANCELLED)
                && newStatus == PaymentAttempt.PaymentStatus.PENDING) {
            return;
        }
        if (captureId != null && attempt.getProviderCaptureReference() == null) {
            attempt.setProviderCaptureReference(captureId);
        }
        attempt.setPaymentStatus(newStatus);
        attempt.setProviderStatus(switch (eventType) {
            case "PAYMENT.CAPTURE.COMPLETED" -> "COMPLETED";
            case "PAYMENT.CAPTURE.PENDING" -> "PENDING";
            case "PAYMENT.CAPTURE.DENIED" -> "DENIED";
            default -> attempt.getProviderStatus();
        });
        if (newStatus == PaymentAttempt.PaymentStatus.SUCCEEDED) {
            attempt.getOrder().setOrderStatus(MerchantOrder.OrderStatus.PAID);
        }
    }

    private String text(JsonNode node, String property) {
        JsonNode value = node.get(property);
        return value == null || !value.isString() || value.asString().isBlank()
                ? null : value.asString();
    }

    private String nestedText(JsonNode node, String... path) {
        JsonNode value = node;
        for (String property : path) {
            if (value == null) {
                return null;
            }
            value = value.get(property);
        }
        return value == null || !value.isString() || value.asString().isBlank()
                ? null : value.asString();
    }

    private record AttemptResolution(PaymentAttempt attempt, boolean valid) {
    }
}
