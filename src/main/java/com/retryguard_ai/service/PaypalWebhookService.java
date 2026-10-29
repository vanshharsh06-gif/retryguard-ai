package com.retryguard_ai.service;

import com.retryguard_ai.exception.InvalidPaypalWebhookRequestException;
import com.retryguard_ai.exception.InvalidPaypalWebhookSignatureException;
import com.retryguard_ai.paypal.PaypalClient;
import com.retryguard_ai.paypal.PaypalWebhookTransmission;
import org.springframework.stereotype.Service;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.nio.charset.StandardCharsets;

@Service
public class PaypalWebhookService {

    private static final Logger LOGGER = LoggerFactory.getLogger(PaypalWebhookService.class);
    private static final int MAX_PAYLOAD_BYTES = 64 * 1024;

    private final PaypalClient paypalClient;
    private final PaypalWebhookEventProcessor eventProcessor;
    private final ObjectMapper objectMapper;

    public PaypalWebhookService(
            PaypalClient paypalClient,
            PaypalWebhookEventProcessor eventProcessor,
            ObjectMapper objectMapper) {
        this.paypalClient = paypalClient;
        this.eventProcessor = eventProcessor;
        this.objectMapper = objectMapper;
    }

    public PaypalWebhookProcessingResult receive(
            PaypalWebhookTransmission transmission, String originalPayload) {
        if (transmission == null || !transmission.isComplete()
                || originalPayload == null || originalPayload.isBlank()
                || originalPayload.getBytes(StandardCharsets.UTF_8).length > MAX_PAYLOAD_BYTES) {
            throw new InvalidPaypalWebhookRequestException("Invalid webhook request.");
        }

        JsonNode event = parseEvent(originalPayload);
        if (!paypalClient.verifyWebhookSignature(transmission, event)) {
            LOGGER.warn("PayPal webhook signature verification failed.");
            throw new InvalidPaypalWebhookSignatureException();
        }
        return new PaypalWebhookProcessingResult(
                true, !eventProcessor.recordVerifiedEvent(event, originalPayload));
    }

    private JsonNode parseEvent(String originalPayload) {
        try {
            JsonNode event = objectMapper.readTree(originalPayload);
            if (event == null || !event.isObject()) {
                throw new InvalidPaypalWebhookRequestException("Invalid webhook event.");
            }
            return event;
        } catch (JacksonException exception) {
            throw new InvalidPaypalWebhookRequestException("Invalid webhook event.");
        }
    }
}
