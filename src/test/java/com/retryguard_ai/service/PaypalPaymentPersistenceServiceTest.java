package com.retryguard_ai.service;

import com.retryguard_ai.entity.PaymentAttempt;
import com.retryguard_ai.entity.MerchantOrder;
import com.retryguard_ai.paypal.PaypalCaptureResult;
import com.retryguard_ai.paypal.PaypalOrderResult;
import com.retryguard_ai.repository.PaymentAttemptRepository;
import com.retryguard_ai.repository.PaymentEventRepository;
import com.retryguard_ai.exception.PaypalProviderException;
import com.retryguard_ai.entity.PaymentEvent;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class PaypalPaymentPersistenceServiceTest {

    @Mock
    private PaymentAttemptRepository paymentAttemptRepository;
    @Mock
    private PaymentEventRepository paymentEventRepository;

    @Test
    void persistsOrderIdAndPendingStatuses() {
        UUID id = UUID.randomUUID();
        PaymentAttempt attempt = new PaymentAttempt();
        attempt.setId(id);
        when(paymentAttemptRepository.findById(id)).thenReturn(Optional.of(attempt));
        when(paymentAttemptRepository.save(any(PaymentAttempt.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));

        PaymentAttempt result = service()
                .recordOrder(id, new PaypalOrderResult("ORDER-123", "CREATED", null));

        assertEquals("ORDER-123", result.getProviderPaymentReference());
        assertEquals("CREATED", result.getProviderStatus());
        assertEquals(PaymentAttempt.PaymentStatus.PENDING, result.getPaymentStatus());
        verify(paymentAttemptRepository).save(attempt);
    }

    @Test
    void mapsCaptureStatusesAndPersistsCaptureReference() {
        UUID id = UUID.randomUUID();
        PaymentAttempt attempt = new PaymentAttempt();
        attempt.setId(id);
        attachOrder(attempt);
        attempt.setProviderPaymentReference("ORDER-123");
        when(paymentAttemptRepository.findByIdForUpdate(id)).thenReturn(Optional.of(attempt));
        when(paymentAttemptRepository.save(any(PaymentAttempt.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));

        PaymentAttempt result = service()
                .recordCapture(id, new PaypalCaptureResult(
                        "ORDER-123", "CAPTURE-456", "COMPLETED", "42.75", "USD"));

        assertEquals("CAPTURE-456", result.getProviderCaptureReference());
        assertEquals("COMPLETED", result.getProviderStatus());
        assertEquals(PaymentAttempt.PaymentStatus.SUCCEEDED, result.getPaymentStatus());
        assertEquals(MerchantOrder.OrderStatus.PAID, result.getOrder().getOrderStatus());
        org.mockito.ArgumentCaptor<PaymentEvent> eventCaptor =
                org.mockito.ArgumentCaptor.forClass(PaymentEvent.class);
        verify(paymentEventRepository).save(eventCaptor.capture());
        assertEquals(PaymentEvent.SourceType.PROVIDER,
                eventCaptor.getValue().getSourceType());
        assertEquals("PAYMENT.CAPTURE.RESPONSE", eventCaptor.getValue().getEventType());
        assertEquals(PaymentEvent.VerificationStatus.NOT_APPLICABLE,
                eventCaptor.getValue().getVerificationStatus());
        assertEquals(PaymentEvent.ProcessingStatus.PROCESSED,
                eventCaptor.getValue().getProcessingStatus());
        org.junit.jupiter.api.Assertions.assertTrue(
                eventCaptor.getValue().getPayload().contains("CAPTURE-456"));
    }

    @Test
    void unknownProviderStatusDoesNotBecomeSuccess() {
        UUID id = UUID.randomUUID();
        PaymentAttempt attempt = new PaymentAttempt();
        attempt.setId(id);
        attachOrder(attempt);
        attempt.setProviderPaymentReference("ORDER-123");
        when(paymentAttemptRepository.findByIdForUpdate(id)).thenReturn(Optional.of(attempt));
        when(paymentAttemptRepository.save(any(PaymentAttempt.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));

        PaymentAttempt result = service()
                .recordCapture(id, new PaypalCaptureResult(
                        "ORDER-123", "CAPTURE-456", "IN_REVIEW", "42.75", "USD"));

        assertEquals(PaymentAttempt.PaymentStatus.UNKNOWN, result.getPaymentStatus());
    }

    @Test
    void rejectsCaptureWithMismatchedAmountCurrencyOrOrder() {
        assertRejectedCapture(new PaypalCaptureResult(
                "ORDER-123", "CAPTURE-456", "COMPLETED", "42.74", "USD"));
        assertRejectedCapture(new PaypalCaptureResult(
                "ORDER-123", "CAPTURE-456", "COMPLETED", "42.75", "EUR"));
        assertRejectedCapture(new PaypalCaptureResult(
                "OTHER-ORDER", "CAPTURE-456", "COMPLETED", "42.75", "USD"));
    }

    private void assertRejectedCapture(PaypalCaptureResult captureResult) {
        UUID id = UUID.randomUUID();
        PaymentAttempt attempt = new PaymentAttempt();
        attempt.setId(id);
        attachOrder(attempt);
        attempt.setProviderPaymentReference("ORDER-123");
        when(paymentAttemptRepository.findByIdForUpdate(id)).thenReturn(Optional.of(attempt));

        org.junit.jupiter.api.Assertions.assertThrows(PaypalProviderException.class,
                () -> service()
                        .recordCapture(id, captureResult));

        assertEquals(PaymentAttempt.PaymentStatus.PENDING, attempt.getPaymentStatus());
        verify(paymentAttemptRepository, org.mockito.Mockito.never())
                .save(any(PaymentAttempt.class));
    }

    private void attachOrder(PaymentAttempt attempt) {
        MerchantOrder order = new MerchantOrder();
        order.setAmount(new java.math.BigDecimal("42.75"));
        order.setCurrency("USD");
        order.setOrderStatus(MerchantOrder.OrderStatus.CREATED);
        attempt.setOrder(order);
        attempt.setAmount(order.getAmount());
        attempt.setCurrency(order.getCurrency());
        attempt.setPaymentStatus(PaymentAttempt.PaymentStatus.PENDING);
    }

    private PaypalPaymentPersistenceService service() {
        return new PaypalPaymentPersistenceService(
                paymentAttemptRepository, paymentEventRepository,
                new tools.jackson.databind.ObjectMapper());
    }
}
