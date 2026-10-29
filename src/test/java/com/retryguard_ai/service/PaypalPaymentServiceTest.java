package com.retryguard_ai.service;

import com.retryguard_ai.entity.MerchantOrder;
import com.retryguard_ai.entity.PaymentAttempt;
import com.retryguard_ai.exception.PaypalOperationNotAllowedException;
import com.retryguard_ai.paypal.PaypalCaptureResult;
import com.retryguard_ai.paypal.PaypalClient;
import com.retryguard_ai.paypal.PaypalOrderResult;
import com.retryguard_ai.repository.PaymentAttemptRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class PaypalPaymentServiceTest {

    @Mock
    private PaymentAttemptRepository paymentAttemptRepository;
    @Mock
    private PaypalClient paypalClient;
    @Mock
    private PaypalPaymentPersistenceService persistenceService;

    @Test
    void createsOrderAndReturnsPersistedProviderReferenceAndApprovalLink() {
        UUID attemptId = UUID.randomUUID();
        PaymentAttempt attempt = attempt(attemptId, "PAYPAL_SANDBOX");
        when(paymentAttemptRepository.findWithOrderById(attemptId))
                .thenReturn(Optional.of(attempt));
        when(paypalClient.createOrder(attempt))
                .thenReturn(new PaypalOrderResult(
                        "ORDER-123", "CREATED", "https://www.sandbox.paypal.com/checkout"));
        PaymentAttempt persisted = attempt(attemptId, "PAYPAL_SANDBOX");
        persisted.setProviderPaymentReference("ORDER-123");
        persisted.setProviderStatus("CREATED");
        persisted.setPaymentStatus(PaymentAttempt.PaymentStatus.PENDING);
        when(persistenceService.recordOrder(attemptId,
                new PaypalOrderResult("ORDER-123", "CREATED",
                        "https://www.sandbox.paypal.com/checkout"))).thenReturn(persisted);

        PaypalPaymentService.PaypalPaymentResult result =
                service().createOrder(attemptId);

        assertEquals("ORDER-123", result.providerOrderId());
        assertEquals("PENDING", result.paymentStatus());
        assertEquals("42.75", result.amount().toPlainString());
        assertEquals("https://www.sandbox.paypal.com/checkout", result.approvalUrl());
        verify(paypalClient).createOrder(attempt);
    }

    @Test
    void captureRequiresAnExistingPaypalOrderAndDoesNotCallProviderOtherwise() {
        UUID attemptId = UUID.randomUUID();
        PaymentAttempt attempt = attempt(attemptId, "PAYPAL_SANDBOX");
        when(paymentAttemptRepository.findWithOrderById(attemptId))
                .thenReturn(Optional.of(attempt));

        assertThrows(PaypalOperationNotAllowedException.class,
                () -> service().captureOrder(attemptId));
        verify(paypalClient, never()).captureOrder(attemptId, null);
    }

    @Test
    void rejectsAttemptsForOtherProvidersBeforeCallingPaypal() {
        UUID attemptId = UUID.randomUUID();
        PaymentAttempt attempt = attempt(attemptId, "OTHER_PROVIDER");
        when(paymentAttemptRepository.findWithOrderById(attemptId))
                .thenReturn(Optional.of(attempt));

        assertThrows(PaypalOperationNotAllowedException.class,
                () -> service().createOrder(attemptId));
        verify(paypalClient, never()).createOrder(attempt);
    }

    @Test
    void captureUsesThePersistedPaypalOrderId() {
        UUID attemptId = UUID.randomUUID();
        PaymentAttempt attempt = attempt(attemptId, "PAYPAL_SANDBOX");
        attempt.setProviderPaymentReference("ORDER-123");
        attempt.setPaymentStatus(PaymentAttempt.PaymentStatus.PENDING);
        when(paymentAttemptRepository.findWithOrderById(attemptId))
                .thenReturn(Optional.of(attempt));
        when(paypalClient.captureOrder(attemptId, "ORDER-123"))
                .thenReturn(new PaypalCaptureResult(
                        "ORDER-123", "CAPTURE-456", "COMPLETED", "42.75", "USD"));
        PaymentAttempt persisted = attempt(attemptId, "PAYPAL_SANDBOX");
        persisted.setProviderPaymentReference("ORDER-123");
        persisted.setProviderCaptureReference("CAPTURE-456");
        persisted.setProviderStatus("COMPLETED");
        persisted.setPaymentStatus(PaymentAttempt.PaymentStatus.SUCCEEDED);
        when(persistenceService.recordCapture(attemptId,
                new PaypalCaptureResult(
                        "ORDER-123", "CAPTURE-456", "COMPLETED", "42.75", "USD")))
                .thenReturn(persisted);

        PaypalPaymentService.PaypalPaymentResult result =
                service().captureOrder(attemptId);

        assertEquals("CAPTURE-456", result.providerCaptureId());
        assertEquals("SUCCEEDED", result.paymentStatus());
    }

    @Test
    void returnRedirectRequiresTokenToMatchTheStoredPaypalOrder() {
        UUID attemptId = UUID.randomUUID();
        PaymentAttempt attempt = attempt(attemptId, "PAYPAL_SANDBOX");
        attempt.setProviderPaymentReference("ORDER-123");
        when(paymentAttemptRepository.findWithOrderById(attemptId))
                .thenReturn(Optional.of(attempt));

        assertThrows(PaypalOperationNotAllowedException.class,
                () -> service().validateReturn(attemptId, "OTHER-ORDER"));
        verifyNoInteractions(persistenceService);
        verify(paypalClient, never()).captureOrder(attemptId, "ORDER-123");
    }

    @Test
    void cancellationRedirectValidatesOrderButDoesNotChangePaymentState() {
        UUID attemptId = UUID.randomUUID();
        PaymentAttempt attempt = attempt(attemptId, "PAYPAL_SANDBOX");
        attempt.setProviderPaymentReference("ORDER-123");
        attempt.setPaymentStatus(PaymentAttempt.PaymentStatus.PENDING);
        attempt.setProviderStatus("CREATED");
        when(paymentAttemptRepository.findWithOrderById(attemptId))
                .thenReturn(Optional.of(attempt));

        service().validateCancellation(attemptId, "ORDER-123");

        assertEquals(PaymentAttempt.PaymentStatus.PENDING, attempt.getPaymentStatus());
        assertEquals("CREATED", attempt.getProviderStatus());
        verifyNoInteractions(persistenceService);
        verifyNoInteractions(paypalClient);
    }

    private PaypalPaymentService service() {
        return new PaypalPaymentService(
                paymentAttemptRepository, paypalClient, persistenceService);
    }

    private PaymentAttempt attempt(UUID id, String provider) {
        MerchantOrder order = new MerchantOrder();
        order.setId(UUID.randomUUID());
        order.setMerchantOrderReference("merchant-ref");
        order.setAmount(new BigDecimal("42.75"));
        order.setCurrency("USD");

        PaymentAttempt attempt = new PaymentAttempt();
        attempt.setId(id);
        attempt.setOrder(order);
        attempt.setProvider(provider);
        attempt.setAmount(order.getAmount());
        attempt.setCurrency(order.getCurrency());
        attempt.setPaymentStatus(PaymentAttempt.PaymentStatus.CREATED);
        return attempt;
    }
}
