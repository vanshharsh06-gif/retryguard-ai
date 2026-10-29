package com.retryguard_ai.service;

import com.retryguard_ai.exception.InvalidPaypalWebhookRequestException;
import com.retryguard_ai.exception.InvalidPaypalWebhookSignatureException;
import com.retryguard_ai.paypal.PaypalClient;
import com.retryguard_ai.paypal.PaypalWebhookTransmission;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class PaypalWebhookServiceTest {

    private static final String PAYLOAD = "{\"id\":\"WH-1\",\"event_type\":\"PAYMENT.CAPTURE.COMPLETED\"}";
    private static final PaypalWebhookTransmission TRANSMISSION =
            new PaypalWebhookTransmission("SHA256withRSA", "https://cert.example/cert",
                    "transmission-1", "signature", "2026-10-17T10:00:00Z");

    @Test
    void verifiesOriginalEventBeforeProcessingIt() {
        PaypalClient paypalClient = mock(PaypalClient.class);
        PaypalWebhookEventProcessor processor = mock(PaypalWebhookEventProcessor.class);
        ObjectMapper objectMapper = mock(ObjectMapper.class);
        JsonNode event = mock(JsonNode.class);
        when(objectMapper.readTree(PAYLOAD)).thenReturn(event);
        when(event.isObject()).thenReturn(true);
        when(paypalClient.verifyWebhookSignature(TRANSMISSION, event)).thenReturn(true);
        when(processor.recordVerifiedEvent(event, PAYLOAD)).thenReturn(true);

        new PaypalWebhookService(paypalClient, processor, objectMapper)
                .receive(TRANSMISSION, PAYLOAD);

        verify(paypalClient).verifyWebhookSignature(TRANSMISSION, event);
        verify(processor).recordVerifiedEvent(event, PAYLOAD);
    }

    @Test
    void rejectsSignatureWithoutPersistingEvent() {
        PaypalClient paypalClient = mock(PaypalClient.class);
        PaypalWebhookEventProcessor processor = mock(PaypalWebhookEventProcessor.class);
        ObjectMapper objectMapper = mock(ObjectMapper.class);
        JsonNode event = mock(JsonNode.class);
        when(objectMapper.readTree(PAYLOAD)).thenReturn(event);
        when(event.isObject()).thenReturn(true);
        when(paypalClient.verifyWebhookSignature(TRANSMISSION, event)).thenReturn(false);

        assertThrows(InvalidPaypalWebhookSignatureException.class,
                () -> new PaypalWebhookService(paypalClient, processor, objectMapper)
                        .receive(TRANSMISSION, PAYLOAD));
        verify(processor, never()).recordVerifiedEvent(event, PAYLOAD);
    }

    @Test
    void rejectsMissingHeadersAndMalformedPayloadBeforeProviderCall() {
        PaypalClient paypalClient = mock(PaypalClient.class);
        PaypalWebhookEventProcessor processor = mock(PaypalWebhookEventProcessor.class);
        ObjectMapper objectMapper = mock(ObjectMapper.class);
        PaypalWebhookService service = new PaypalWebhookService(
                paypalClient, processor, objectMapper);

        assertThrows(InvalidPaypalWebhookRequestException.class,
                () -> service.receive(new PaypalWebhookTransmission(
                        null, TRANSMISSION.certUrl(), TRANSMISSION.transmissionId(),
                        TRANSMISSION.transmissionSignature(), TRANSMISSION.transmissionTime()),
                        PAYLOAD));
        assertThrows(InvalidPaypalWebhookRequestException.class,
                () -> service.receive(new PaypalWebhookTransmission(
                        " ", TRANSMISSION.certUrl(), TRANSMISSION.transmissionId(),
                        TRANSMISSION.transmissionSignature(), TRANSMISSION.transmissionTime()),
                        PAYLOAD));
        assertThrows(InvalidPaypalWebhookRequestException.class,
                () -> service.receive(TRANSMISSION, "not-json"));
        verify(paypalClient, never()).verifyWebhookSignature(
                org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any());
    }
}
