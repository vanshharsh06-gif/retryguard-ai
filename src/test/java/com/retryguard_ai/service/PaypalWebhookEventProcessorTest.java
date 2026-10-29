package com.retryguard_ai.service;

import com.retryguard_ai.entity.PaymentAttempt;
import com.retryguard_ai.entity.PaymentEvent;
import com.retryguard_ai.entity.MerchantOrder;
import com.retryguard_ai.exception.ProviderEventIdConflictException;
import com.retryguard_ai.repository.PaymentAttemptRepository;
import com.retryguard_ai.repository.PaymentEventRepository;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;

import java.util.List;
import java.math.BigDecimal;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class PaypalWebhookEventProcessorTest {

    @Test
    void recordsVerifiedDuplicateOnlyOnce() {
        PaymentAttemptRepository attempts = mock(PaymentAttemptRepository.class);
        PaymentEventRepository events = mock(PaymentEventRepository.class);
        PaymentEvent existing = new PaymentEvent();
        existing.setSourceType(PaymentEvent.SourceType.PROVIDER);
        existing.setVerificationStatus(PaymentEvent.VerificationStatus.VERIFIED);
        when(events.findByProviderAndProviderEventId("PAYPAL_SANDBOX", "WH-1"))
                .thenReturn(Optional.of(existing));

        boolean created = processor(attempts, events).recordVerifiedEvent(
                event("WH-1", "PAYMENT.CAPTURE.COMPLETED", resource("CAP-1", "ORDER-1")),
                "{\"id\":\"WH-1\"}");

        assertFalse(created);
        verify(events).lockIdempotencyKey("PAYPAL_SANDBOX", "WH-1");
        verify(events, never()).save(any(PaymentEvent.class));
        verify(attempts, never()).save(any(PaymentAttempt.class));
    }

    @Test
    void simulatedRowWithSameIdCannotBeTreatedAsVerifiedDuplicate() {
        PaymentAttemptRepository attempts = mock(PaymentAttemptRepository.class);
        PaymentEventRepository events = mock(PaymentEventRepository.class);
        PaymentEvent simulated = new PaymentEvent();
        simulated.setSourceType(PaymentEvent.SourceType.SIMULATED);
        simulated.setVerificationStatus(PaymentEvent.VerificationStatus.NOT_APPLICABLE);
        when(events.findByProviderAndProviderEventId("PAYPAL_SANDBOX", "WH-1"))
                .thenReturn(Optional.of(simulated));

        assertThrows(ProviderEventIdConflictException.class,
                () -> processor(attempts, events).recordVerifiedEvent(
                        event("WH-1", "PAYMENT.CAPTURE.COMPLETED",
                                resource("CAP-1", "ORDER-1")),
                        "{\"id\":\"WH-1\"}"));
        verify(events, never()).save(any(PaymentEvent.class));
        verify(attempts, never()).save(any(PaymentAttempt.class));
    }

    @Test
    void unmatchedVerifiedEventIsStoredWithoutChangingAnyAttempt() {
        PaymentAttemptRepository attempts = mock(PaymentAttemptRepository.class);
        PaymentEventRepository events = mock(PaymentEventRepository.class);
        when(events.findByProviderAndProviderEventId("PAYPAL_SANDBOX", "WH-2"))
                .thenReturn(Optional.empty());
        when(attempts.findByProviderAndProviderCaptureReference("PAYPAL_SANDBOX", "CAP-2"))
                .thenReturn(List.of());
        when(attempts.findByProviderAndProviderPaymentReference("PAYPAL_SANDBOX", "ORDER-2"))
                .thenReturn(List.of());

        boolean created = processor(attempts, events).recordVerifiedEvent(
                event("WH-2", "PAYMENT.CAPTURE.COMPLETED", resource("CAP-2", "ORDER-2")),
                "{\"id\":\"WH-2\"}");

        assertTrue(created);
        org.mockito.ArgumentCaptor<PaymentEvent> captor =
                org.mockito.ArgumentCaptor.forClass(PaymentEvent.class);
        verify(events).save(captor.capture());
        assertNull(captor.getValue().getPaymentAttempt());
        assertEquals(PaymentEvent.VerificationStatus.VERIFIED,
                captor.getValue().getVerificationStatus());
        assertEquals(PaymentEvent.ProcessingStatus.RECEIVED,
                captor.getValue().getProcessingStatus());
        verify(attempts, never()).save(any(PaymentAttempt.class));
    }

    @Test
    void updatesStatusOnlyForVerifiedKnownEventAndPersistedReference() {
        PaymentAttemptRepository attempts = mock(PaymentAttemptRepository.class);
        PaymentEventRepository events = mock(PaymentEventRepository.class);
        PaymentAttempt attempt = new PaymentAttempt();
        attempt.setId(UUID.randomUUID());
        attempt.setPaymentStatus(PaymentAttempt.PaymentStatus.PENDING);
        when(events.findByProviderAndProviderEventId("PAYPAL_SANDBOX", "WH-3"))
                .thenReturn(Optional.empty());
        when(attempts.findByProviderAndProviderCaptureReference("PAYPAL_SANDBOX", "CAP-3"))
                .thenReturn(List.of(attempt));
        when(attempts.findByProviderAndProviderPaymentReference("PAYPAL_SANDBOX", "ORDER-3"))
                .thenReturn(List.of(attempt));
        when(attempts.findByIdForUpdate(attempt.getId())).thenReturn(Optional.of(attempt));
        attachOrderAndReferences(attempt, "ORDER-3", "CAP-3");

        processor(attempts, events).recordVerifiedEvent(
                event("WH-3", "PAYMENT.CAPTURE.COMPLETED", resource("CAP-3", "ORDER-3")),
                "{\"id\":\"WH-3\"}");

        assertEquals(PaymentAttempt.PaymentStatus.SUCCEEDED, attempt.getPaymentStatus());
        assertEquals("COMPLETED", attempt.getProviderStatus());
        assertEquals(MerchantOrder.OrderStatus.PAID, attempt.getOrder().getOrderStatus());
        verify(attempts).save(attempt);
        org.mockito.ArgumentCaptor<PaymentEvent> captor =
                org.mockito.ArgumentCaptor.forClass(PaymentEvent.class);
        verify(events).save(captor.capture());
        assertSameAttempt(attempt, captor.getValue().getPaymentAttempt());
        assertEquals(PaymentEvent.VerificationStatus.VERIFIED,
                captor.getValue().getVerificationStatus());
        assertEquals(PaymentEvent.ProcessingStatus.PROCESSED,
                captor.getValue().getProcessingStatus());
    }

    @Test
    void ignoresUnknownEventTypeForPaymentState() {
        PaymentAttemptRepository attempts = mock(PaymentAttemptRepository.class);
        PaymentEventRepository events = mock(PaymentEventRepository.class);
        PaymentAttempt attempt = new PaymentAttempt();
        attempt.setId(UUID.randomUUID());
        attempt.setPaymentStatus(PaymentAttempt.PaymentStatus.PENDING);
        when(events.findByProviderAndProviderEventId("PAYPAL_SANDBOX", "WH-4"))
                .thenReturn(Optional.empty());
        when(attempts.findByProviderAndProviderCaptureReference("PAYPAL_SANDBOX", "CAP-4"))
                .thenReturn(List.of(attempt));
        when(attempts.findByProviderAndProviderPaymentReference("PAYPAL_SANDBOX", "ORDER-4"))
                .thenReturn(List.of());

        attachOrderAndReferences(attempt, "ORDER-4", "CAP-4");
        when(attempts.findByIdForUpdate(attempt.getId())).thenReturn(Optional.of(attempt));
        processor(attempts, events).recordVerifiedEvent(
                event("WH-4", "PAYMENT.CAPTURE.REVERSED", resource("CAP-4", "ORDER-4")),
                "{\"id\":\"WH-4\"}");

        assertEquals(PaymentAttempt.PaymentStatus.PENDING, attempt.getPaymentStatus());
        verify(attempts, never()).save(any(PaymentAttempt.class));
    }

    @Test
    void mismatchedCaptureAndOrderAreStoredButCannotUpdateAttempt() {
        PaymentAttempt orderAttempt = attempt("ORDER-A", "CAP-A", PaymentAttempt.PaymentStatus.PENDING);
        PaymentAttempt captureAttempt = attempt("ORDER-B", "CAP-B", PaymentAttempt.PaymentStatus.PENDING);
        PaymentAttemptRepository attempts = mock(PaymentAttemptRepository.class);
        PaymentEventRepository events = mock(PaymentEventRepository.class);
        when(events.findByProviderAndProviderEventId("PAYPAL_SANDBOX", "WH-MISMATCH"))
                .thenReturn(Optional.empty());
        when(attempts.findByProviderAndProviderPaymentReference("PAYPAL_SANDBOX", "ORDER-A"))
                .thenReturn(List.of(orderAttempt));
        when(attempts.findByProviderAndProviderCaptureReference("PAYPAL_SANDBOX", "CAP-B"))
                .thenReturn(List.of(captureAttempt));
        when(attempts.findByIdForUpdate(orderAttempt.getId()))
                .thenReturn(Optional.of(orderAttempt));

        processor(attempts, events).recordVerifiedEvent(
                event("WH-MISMATCH", "PAYMENT.CAPTURE.COMPLETED",
                        resource("CAP-B", "ORDER-A", "42.75", "USD")),
                "{\"id\":\"WH-MISMATCH\"}");

        assertEquals(PaymentAttempt.PaymentStatus.PENDING, orderAttempt.getPaymentStatus());
        verify(attempts, never()).save(any(PaymentAttempt.class));
        org.mockito.ArgumentCaptor<PaymentEvent> captor =
                org.mockito.ArgumentCaptor.forClass(PaymentEvent.class);
        verify(events).save(captor.capture());
        assertEquals(PaymentEvent.ProcessingStatus.FAILED,
                captor.getValue().getProcessingStatus());
    }

    @Test
    void amountOrCurrencyMismatchCannotMarkAttemptSuccessful() {
        for (String[] money : List.<String[]>of(
                new String[] {"42.74", "USD"},
                new String[] {"42.75", "EUR"})) {
            PaymentAttempt attempt = attempt("ORDER-MONEY", "CAP-MONEY",
                    PaymentAttempt.PaymentStatus.PENDING);
            PaymentAttemptRepository attempts = mock(PaymentAttemptRepository.class);
            PaymentEventRepository events = mock(PaymentEventRepository.class);
            when(events.findByProviderAndProviderEventId("PAYPAL_SANDBOX", "WH-MONEY"))
                    .thenReturn(Optional.empty());
            when(attempts.findByProviderAndProviderPaymentReference(
                    "PAYPAL_SANDBOX", "ORDER-MONEY")).thenReturn(List.of(attempt));
            when(attempts.findByProviderAndProviderCaptureReference(
                    "PAYPAL_SANDBOX", "CAP-MONEY")).thenReturn(List.of(attempt));
            when(attempts.findByIdForUpdate(attempt.getId())).thenReturn(Optional.of(attempt));

            processor(attempts, events).recordVerifiedEvent(
                    event("WH-MONEY", "PAYMENT.CAPTURE.COMPLETED",
                            resource("CAP-MONEY", "ORDER-MONEY", money[0], money[1])),
                    "{\"id\":\"WH-MONEY\"}");

            assertEquals(PaymentAttempt.PaymentStatus.PENDING, attempt.getPaymentStatus());
            verify(attempts, never()).save(any(PaymentAttempt.class));
            org.mockito.ArgumentCaptor<PaymentEvent> captor =
                    org.mockito.ArgumentCaptor.forClass(PaymentEvent.class);
            verify(events).save(captor.capture());
            assertEquals(PaymentEvent.ProcessingStatus.FAILED,
                    captor.getValue().getProcessingStatus());
        }
    }

    @Test
    void outOfOrderPendingEventCannotDowngradeSuccessfulAttempt() {
        PaymentAttempt attempt = attempt("ORDER-5", "CAP-5", PaymentAttempt.PaymentStatus.SUCCEEDED);
        PaymentAttemptRepository attempts = mock(PaymentAttemptRepository.class);
        PaymentEventRepository events = mock(PaymentEventRepository.class);
        when(events.findByProviderAndProviderEventId("PAYPAL_SANDBOX", "WH-5"))
                .thenReturn(Optional.empty());
        when(attempts.findByProviderAndProviderPaymentReference("PAYPAL_SANDBOX", "ORDER-5"))
                .thenReturn(List.of(attempt));
        when(attempts.findByProviderAndProviderCaptureReference("PAYPAL_SANDBOX", "CAP-5"))
                .thenReturn(List.of(attempt));
        when(attempts.findByIdForUpdate(attempt.getId())).thenReturn(Optional.of(attempt));

        processor(attempts, events).recordVerifiedEvent(
                event("WH-5", "PAYMENT.CAPTURE.PENDING",
                        resource("CAP-5", "ORDER-5", "42.75", "USD")),
                "{\"id\":\"WH-5\"}");

        assertEquals(PaymentAttempt.PaymentStatus.SUCCEEDED, attempt.getPaymentStatus());
        org.mockito.ArgumentCaptor<PaymentEvent> captor =
                org.mockito.ArgumentCaptor.forClass(PaymentEvent.class);
        verify(events).save(captor.capture());
        assertEquals(PaymentEvent.ProcessingStatus.PROCESSED,
                captor.getValue().getProcessingStatus());
        verify(attempts).save(attempt);
    }

    @Test
    void eventPersistenceFailurePropagates() {
        PaymentAttempt attempt = attempt("ORDER-6", "CAP-6", PaymentAttempt.PaymentStatus.PENDING);
        PaymentAttemptRepository attempts = mock(PaymentAttemptRepository.class);
        PaymentEventRepository events = mock(PaymentEventRepository.class);
        when(events.findByProviderAndProviderEventId("PAYPAL_SANDBOX", "WH-6"))
                .thenReturn(Optional.empty());
        when(attempts.findByProviderAndProviderPaymentReference("PAYPAL_SANDBOX", "ORDER-6"))
                .thenReturn(List.of(attempt));
        when(attempts.findByProviderAndProviderCaptureReference("PAYPAL_SANDBOX", "CAP-6"))
                .thenReturn(List.of(attempt));
        when(attempts.findByIdForUpdate(attempt.getId())).thenReturn(Optional.of(attempt));
        when(events.save(any(PaymentEvent.class))).thenThrow(new IllegalStateException("database"));

        org.junit.jupiter.api.Assertions.assertThrows(IllegalStateException.class,
                () -> processor(attempts, events).recordVerifiedEvent(
                        event("WH-6", "PAYMENT.CAPTURE.COMPLETED",
                                resource("CAP-6", "ORDER-6", "42.75", "USD")),
                        "{\"id\":\"WH-6\"}"));
    }

    private PaypalWebhookEventProcessor processor(
            PaymentAttemptRepository attempts, PaymentEventRepository events) {
        return new PaypalWebhookEventProcessor(attempts, events);
    }

    private JsonNode event(String id, String type, JsonNode resource) {
        return objectNode(java.util.Map.of(
                "id", stringNode(id),
                "event_type", stringNode(type),
                "resource", resource));
    }

    private JsonNode resource(String captureId, String orderId) {
        return resource(captureId, orderId, "42.75", "USD");
    }

    private JsonNode resource(
            String captureId, String orderId, String amount, String currency) {
        return objectNode(java.util.Map.of(
                "id", stringNode(captureId),
                "amount", objectNode(java.util.Map.of(
                        "value", stringNode(amount),
                        "currency_code", stringNode(currency))),
                "supplementary_data", objectNode(java.util.Map.of(
                        "related_ids", objectNode(java.util.Map.of(
                                "order_id", stringNode(orderId)))))));
    }

    private PaymentAttempt attempt(
            String orderId, String captureId, PaymentAttempt.PaymentStatus status) {
        PaymentAttempt attempt = new PaymentAttempt();
        attempt.setId(UUID.randomUUID());
        attempt.setProvider("PAYPAL_SANDBOX");
        attempt.setPaymentStatus(status);
        attempt.setAmount(new BigDecimal("42.75"));
        attempt.setCurrency("USD");
        attachOrderAndReferences(attempt, orderId, captureId);
        return attempt;
    }

    private void attachOrderAndReferences(
            PaymentAttempt attempt, String orderId, String captureId) {
        MerchantOrder order = new MerchantOrder();
        order.setAmount(new BigDecimal("42.75"));
        order.setCurrency("USD");
        order.setOrderStatus(MerchantOrder.OrderStatus.CREATED);
        attempt.setOrder(order);
        attempt.setProvider("PAYPAL_SANDBOX");
        attempt.setAmount(new BigDecimal("42.75"));
        attempt.setCurrency("USD");
        attempt.setProviderPaymentReference(orderId);
        attempt.setProviderCaptureReference(captureId);
    }

    private JsonNode objectNode(java.util.Map<String, JsonNode> fields) {
        JsonNode node = mock(JsonNode.class);
        when(node.isObject()).thenReturn(true);
        when(node.get(org.mockito.ArgumentMatchers.anyString()))
                .thenAnswer(invocation -> fields.get(invocation.getArgument(0)));
        return node;
    }

    private JsonNode stringNode(String value) {
        JsonNode node = mock(JsonNode.class);
        when(node.isString()).thenReturn(true);
        when(node.asString()).thenReturn(value);
        return node;
    }

    private void assertSameAttempt(PaymentAttempt expected, PaymentAttempt actual) {
        assertEquals(expected.getId(), actual.getId());
    }
}
