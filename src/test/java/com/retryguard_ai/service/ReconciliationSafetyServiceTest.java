package com.retryguard_ai.service;

import com.retryguard_ai.entity.MerchantOrder;
import com.retryguard_ai.entity.PaymentAttempt;
import com.retryguard_ai.paypal.PaypalClient;
import com.retryguard_ai.paypal.PaypalClient.PaypalOrderDiagnostic;
import com.retryguard_ai.paypal.PaypalClient.PaypalOrderUnitDiagnostic;
import com.retryguard_ai.paypal.PaypalClient.PaypalOrderApprovalLink;
import com.retryguard_ai.repository.MerchantOrderRepository;
import com.retryguard_ai.repository.PaymentAttemptRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class ReconciliationSafetyServiceTest {

    @Mock private MerchantOrderRepository orderRepository;
    @Mock private PaymentAttemptRepository attemptRepository;
    @Mock private PaypalClient paypalClient;

    private ReconciliationSafetyService service;
    private MerchantOrder order;
    private PaymentAttempt attempt;
    private UUID orderId;
    private UUID attemptId;

    @BeforeEach
    void setUp() {
        service = new ReconciliationSafetyService(orderRepository, attemptRepository, paypalClient);
        
        orderId = UUID.randomUUID();
        attemptId = UUID.randomUUID();
        
        order = new MerchantOrder();
        order.setId(orderId);
        order.setMerchantOrderReference("order-ref-123");
        order.setAmount(new BigDecimal("100.00"));
        order.setCurrency("USD");
        order.setOrderStatus(MerchantOrder.OrderStatus.CREATED);

        attempt = new PaymentAttempt();
        attempt.setId(attemptId);
        attempt.setOrder(order);
        attempt.setProvider("PAYPAL_SANDBOX");
        attempt.setProviderPaymentReference("PAYPAL-ORDER-123");
        attempt.setProviderCaptureReference("CAPTURE-456");
        attempt.setAmount(new BigDecimal("100.00"));
        attempt.setCurrency("USD");
        attempt.setPaymentStatus(PaymentAttempt.PaymentStatus.PENDING);
    }

    private PaypalOrderUnitDiagnostic completedCaptureUnit() {
        return new PaypalOrderUnitDiagnostic(
                "order-ref-123", "USD", "100.00",
                "CAPTURE-456", "COMPLETED", "100.00", "USD");
    }

    @Test
    void reconcilesSuccessfullyWhenAllConditionsMet() {
        PaypalOrderUnitDiagnostic pu = completedCaptureUnit();
        PaypalOrderDiagnostic diagnostic = new PaypalOrderDiagnostic(
                200, "COMPLETED", "CAPTURE", List.of(pu), null, null, null, null);
        
        when(orderRepository.findById(orderId)).thenReturn(Optional.of(order));
        when(attemptRepository.findById(attemptId)).thenReturn(Optional.of(attempt));
        when(paypalClient.diagnoseOrder("PAYPAL-ORDER-123")).thenReturn(diagnostic);
        when(orderRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(attemptRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        ReconciliationSafetyService.ReconciliationResult result = service.reconcileIfAuthorized(orderId, attemptId);

        assertThat(result.success()).isTrue();
        assertThat(result.code()).isEqualTo("RECONCILED");
        assertThat(order.getOrderStatus()).isEqualTo(MerchantOrder.OrderStatus.PAID);
        assertThat(attempt.getPaymentStatus()).isEqualTo(PaymentAttempt.PaymentStatus.SUCCEEDED);
        assertThat(attempt.getProviderStatus()).isEqualTo("COMPLETED");
        
        verify(orderRepository).save(order);
        verify(attemptRepository).save(attempt);
    }

    @Test
    void failsWhenOrderAlreadyPaid() {
        order.setOrderStatus(MerchantOrder.OrderStatus.PAID);
        when(orderRepository.findById(orderId)).thenReturn(Optional.of(order));
        when(attemptRepository.findById(attemptId)).thenReturn(Optional.of(attempt));

        ReconciliationSafetyService.ReconciliationResult result = service.reconcileIfAuthorized(orderId, attemptId);

        assertThat(result.success()).isTrue(); // ALREADY_PAID is success
        assertThat(result.code()).isEqualTo("ALREADY_PAID");
        verify(orderRepository, never()).save(any());
        verify(attemptRepository, never()).save(any());
    }

    @Test
    void failsWhenProviderNotPaypalSandbox() {
        attempt.setProvider("STRIPE");
        when(orderRepository.findById(orderId)).thenReturn(Optional.of(order));
        when(attemptRepository.findById(attemptId)).thenReturn(Optional.of(attempt));

        ReconciliationSafetyService.ReconciliationResult result = service.reconcileIfAuthorized(orderId, attemptId);

        assertThat(result.success()).isFalse();
        assertThat(result.code()).isEqualTo("INVALID_PROVIDER");
    }

    @Test
    void failsWhenMissingProviderOrderId() {
        attempt.setProviderPaymentReference(null);
        when(orderRepository.findById(orderId)).thenReturn(Optional.of(order));
        when(attemptRepository.findById(attemptId)).thenReturn(Optional.of(attempt));

        ReconciliationSafetyService.ReconciliationResult result = service.reconcileIfAuthorized(orderId, attemptId);

        assertThat(result.success()).isFalse();
        assertThat(result.code()).isEqualTo("MISSING_PROVIDER_ORDER_ID");
    }

    @Test
    void failsWhenPayPalHttpError() {
        when(orderRepository.findById(orderId)).thenReturn(Optional.of(order));
        when(attemptRepository.findById(attemptId)).thenReturn(Optional.of(attempt));
        
        PaypalOrderDiagnostic diagnostic = new PaypalOrderDiagnostic(
                404, "NOT_FOUND", "CAPTURE", List.of(), null, null, null, null);
        
        when(paypalClient.diagnoseOrder("PAYPAL-ORDER-123")).thenReturn(diagnostic);

        ReconciliationSafetyService.ReconciliationResult result = service.reconcileIfAuthorized(orderId, attemptId);

        assertThat(result.success()).isFalse();
        assertThat(result.code()).isEqualTo("PROVIDER_UNAVAILABLE");
    }

    @Test
    void failsWhenPayPalStatusNotCompleted() {
        when(orderRepository.findById(orderId)).thenReturn(Optional.of(order));
        when(attemptRepository.findById(attemptId)).thenReturn(Optional.of(attempt));
        
        PaypalOrderUnitDiagnostic pu = new PaypalOrderUnitDiagnostic("order-ref-123", "USD", "100.00", null, null, null, null);
        PaypalOrderDiagnostic diagnostic = new PaypalOrderDiagnostic(
                200, "APPROVED", "CAPTURE", List.of(pu), null, null, null, null);
        
        when(paypalClient.diagnoseOrder("PAYPAL-ORDER-123")).thenReturn(diagnostic);

        ReconciliationSafetyService.ReconciliationResult result = service.reconcileIfAuthorized(orderId, attemptId);

        assertThat(result.success()).isFalse();
        assertThat(result.code()).isEqualTo("CAPTURE_NOT_COMPLETED");
    }

    @Test
    void failsWhenMissingPurchaseUnits() {
        when(orderRepository.findById(orderId)).thenReturn(Optional.of(order));
        when(attemptRepository.findById(attemptId)).thenReturn(Optional.of(attempt));
        
        PaypalOrderDiagnostic diagnostic = new PaypalOrderDiagnostic(
                200, "COMPLETED", "CAPTURE", List.of(), null, null, null, null);
        
        when(paypalClient.diagnoseOrder("PAYPAL-ORDER-123")).thenReturn(diagnostic);

        ReconciliationSafetyService.ReconciliationResult result = service.reconcileIfAuthorized(orderId, attemptId);

        assertThat(result.success()).isFalse();
        assertThat(result.code()).isEqualTo("MISSING_PURCHASE_UNIT");
    }

    @Test
    void failsWhenMissingAmountOrCurrency() {
        when(orderRepository.findById(orderId)).thenReturn(Optional.of(order));
        when(attemptRepository.findById(attemptId)).thenReturn(Optional.of(attempt));
        
        PaypalOrderUnitDiagnostic pu = new PaypalOrderUnitDiagnostic("order-ref-123", null, "100.00", null, null, null, null);
        PaypalOrderDiagnostic diagnostic = new PaypalOrderDiagnostic(
                200, "COMPLETED", "CAPTURE", List.of(pu), null, null, null, null);
        
        when(paypalClient.diagnoseOrder("PAYPAL-ORDER-123")).thenReturn(diagnostic);

        ReconciliationSafetyService.ReconciliationResult result = service.reconcileIfAuthorized(orderId, attemptId);

        assertThat(result.success()).isFalse();
        assertThat(result.code()).isEqualTo("MISSING_AMOUNT_CURRENCY");
    }

    @Test
    void failsWhenCurrencyMismatch() {
        when(orderRepository.findById(orderId)).thenReturn(Optional.of(order));
        when(attemptRepository.findById(attemptId)).thenReturn(Optional.of(attempt));
        
        PaypalOrderUnitDiagnostic pu = new PaypalOrderUnitDiagnostic("order-ref-123", "EUR", "100.00", null, null, null, null);
        PaypalOrderDiagnostic diagnostic = new PaypalOrderDiagnostic(
                200, "COMPLETED", "CAPTURE", List.of(pu), null, null, null, null);
        
        when(paypalClient.diagnoseOrder("PAYPAL-ORDER-123")).thenReturn(diagnostic);

        ReconciliationSafetyService.ReconciliationResult result = service.reconcileIfAuthorized(orderId, attemptId);

        assertThat(result.success()).isFalse();
        assertThat(result.code()).isEqualTo("CURRENCY_MISMATCH");
    }

    @Test
    void failsWhenAmountMismatch() {
        when(orderRepository.findById(orderId)).thenReturn(Optional.of(order));
        when(attemptRepository.findById(attemptId)).thenReturn(Optional.of(attempt));
        
        PaypalOrderUnitDiagnostic pu = new PaypalOrderUnitDiagnostic("order-ref-123", "USD", "99.00", null, null, null, null);
        PaypalOrderDiagnostic diagnostic = new PaypalOrderDiagnostic(
                200, "COMPLETED", "CAPTURE", List.of(pu), null, null, null, null);
        
        when(paypalClient.diagnoseOrder("PAYPAL-ORDER-123")).thenReturn(diagnostic);

        ReconciliationSafetyService.ReconciliationResult result = service.reconcileIfAuthorized(orderId, attemptId);

        assertThat(result.success()).isFalse();
        assertThat(result.code()).isEqualTo("AMOUNT_MISMATCH");
    }

    @Test
    void failsWhenInvalidOrderIntent() {
        PaypalOrderUnitDiagnostic pu = completedCaptureUnit();
        PaypalOrderDiagnostic diagnostic = new PaypalOrderDiagnostic(
                200, "COMPLETED", "AUTHORIZE", List.of(pu), null, null, null, null);
        
        when(orderRepository.findById(orderId)).thenReturn(Optional.of(order));
        when(attemptRepository.findById(attemptId)).thenReturn(Optional.of(attempt));
        when(paypalClient.diagnoseOrder("PAYPAL-ORDER-123")).thenReturn(diagnostic);

        ReconciliationSafetyService.ReconciliationResult result = service.reconcileIfAuthorized(orderId, attemptId);

        assertThat(result.success()).isFalse();
        assertThat(result.code()).isEqualTo("INVALID_ORDER_INTENT");
    }

    @Test
    void failsWhenOrderNotFound() {
        when(orderRepository.findById(orderId)).thenReturn(Optional.empty());

        ReconciliationSafetyService.ReconciliationResult result = service.reconcileIfAuthorized(orderId, attemptId);

        assertThat(result.success()).isFalse();
        assertThat(result.code()).isEqualTo("ORDER_NOT_FOUND");
    }

    @Test
    void failsWhenAttemptNotFound() {
        when(orderRepository.findById(orderId)).thenReturn(Optional.of(order));
        when(attemptRepository.findById(attemptId)).thenReturn(Optional.empty());

        ReconciliationSafetyService.ReconciliationResult result = service.reconcileIfAuthorized(orderId, attemptId);

        assertThat(result.success()).isFalse();
        assertThat(result.code()).isEqualTo("ATTEMPT_NOT_FOUND");
    }

    @Test
    void failsWhenOrderAttemptMismatch() {
        UUID otherOrderId = UUID.randomUUID();
        MerchantOrder otherOrder = new MerchantOrder();
        otherOrder.setId(otherOrderId);
        
        attempt.setOrder(otherOrder);
        
        when(orderRepository.findById(orderId)).thenReturn(Optional.of(order));
        when(attemptRepository.findById(attemptId)).thenReturn(Optional.of(attempt));

        ReconciliationSafetyService.ReconciliationResult result = service.reconcileIfAuthorized(orderId, attemptId);

        assertThat(result.success()).isFalse();
        assertThat(result.code()).isEqualTo("ORDER_ATTEMPT_MISMATCH");
    }

    @Test
    void normalizesCurrencyCaseInsensitive() {
        order.setCurrency("usd");
        attempt.setCurrency("USD");
        attempt.setProviderCaptureReference("CAPTURE-456");
        
        when(orderRepository.findById(orderId)).thenReturn(Optional.of(order));
        when(attemptRepository.findById(attemptId)).thenReturn(Optional.of(attempt));
        
        PaypalOrderUnitDiagnostic pu = new PaypalOrderUnitDiagnostic("order-ref-123", "UsD", "100.00",
                "CAPTURE-456", "COMPLETED", "100.00", "USD");
        PaypalOrderDiagnostic diagnostic = new PaypalOrderDiagnostic(
                200, "COMPLETED", "CAPTURE", List.of(pu), null, null, null, null);
        
        when(paypalClient.diagnoseOrder("PAYPAL-ORDER-123")).thenReturn(diagnostic);
        when(orderRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(attemptRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        ReconciliationSafetyService.ReconciliationResult result = service.reconcileIfAuthorized(orderId, attemptId);

        assertThat(result.success()).isTrue();
        assertThat(result.code()).isEqualTo("RECONCILED");
    }

    @Test
    void failsWhenCaptureStatusNotCompleted() {
        PaypalOrderUnitDiagnostic pu = new PaypalOrderUnitDiagnostic(
                "order-ref-123", "USD", "100.00", "CAPTURE-456", "PENDING", "100.00", "USD");
        PaypalOrderDiagnostic diagnostic = new PaypalOrderDiagnostic(
                200, "COMPLETED", "CAPTURE", List.of(pu), null, null, null, null);
        
        when(orderRepository.findById(orderId)).thenReturn(Optional.of(order));
        when(attemptRepository.findById(attemptId)).thenReturn(Optional.of(attempt));
        when(paypalClient.diagnoseOrder("PAYPAL-ORDER-123")).thenReturn(diagnostic);

        ReconciliationSafetyService.ReconciliationResult result = service.reconcileIfAuthorized(orderId, attemptId);

        assertThat(result.success()).isFalse();
        assertThat(result.code()).isEqualTo("CAPTURE_NOT_COMPLETED");
    }

    @Test
    void failsWhenCaptureMissing() {
        PaypalOrderUnitDiagnostic pu = new PaypalOrderUnitDiagnostic(
                "order-ref-123", "USD", "100.00", null, null, null, null);
        PaypalOrderDiagnostic diagnostic = new PaypalOrderDiagnostic(
                200, "COMPLETED", "CAPTURE", List.of(pu), null, null, null, null);
        
        when(orderRepository.findById(orderId)).thenReturn(Optional.of(order));
        when(attemptRepository.findById(attemptId)).thenReturn(Optional.of(attempt));
        when(paypalClient.diagnoseOrder("PAYPAL-ORDER-123")).thenReturn(diagnostic);

        ReconciliationSafetyService.ReconciliationResult result = service.reconcileIfAuthorized(orderId, attemptId);

        assertThat(result.success()).isFalse();
        assertThat(result.code()).isEqualTo("MISSING_CAPTURE_RECORD");
    }

    @Test
    void failsWhenCaptureAmountMismatch() {
        PaypalOrderUnitDiagnostic pu = new PaypalOrderUnitDiagnostic(
                "order-ref-123", "USD", "100.00", "CAPTURE-456", "COMPLETED", "99.00", "USD");
        PaypalOrderDiagnostic diagnostic = new PaypalOrderDiagnostic(
                200, "COMPLETED", "CAPTURE", List.of(pu), null, null, null, null);
        
        when(orderRepository.findById(orderId)).thenReturn(Optional.of(order));
        when(attemptRepository.findById(attemptId)).thenReturn(Optional.of(attempt));
        when(paypalClient.diagnoseOrder("PAYPAL-ORDER-123")).thenReturn(diagnostic);

        ReconciliationSafetyService.ReconciliationResult result = service.reconcileIfAuthorized(orderId, attemptId);

        assertThat(result.success()).isFalse();
        assertThat(result.code()).isEqualTo("CAPTURE_AMOUNT_MISMATCH");
    }

    @Test
    void failsWhenCaptureCurrencyMismatch() {
        PaypalOrderUnitDiagnostic pu = new PaypalOrderUnitDiagnostic(
                "order-ref-123", "USD", "100.00", "CAPTURE-456", "COMPLETED", "100.00", "EUR");
        PaypalOrderDiagnostic diagnostic = new PaypalOrderDiagnostic(
                200, "COMPLETED", "CAPTURE", List.of(pu), null, null, null, null);
        
        when(orderRepository.findById(orderId)).thenReturn(Optional.of(order));
        when(attemptRepository.findById(attemptId)).thenReturn(Optional.of(attempt));
        when(paypalClient.diagnoseOrder("PAYPAL-ORDER-123")).thenReturn(diagnostic);

        ReconciliationSafetyService.ReconciliationResult result = service.reconcileIfAuthorized(orderId, attemptId);

        assertThat(result.success()).isFalse();
        assertThat(result.code()).isEqualTo("CAPTURE_CURRENCY_MISMATCH");
    }

    @Test
    void failsWhenCaptureIdMismatch() {
        attempt.setProviderCaptureReference("CAPTURE-999");
        PaypalOrderUnitDiagnostic pu = completedCaptureUnit();
        PaypalOrderDiagnostic diagnostic = new PaypalOrderDiagnostic(
                200, "COMPLETED", "CAPTURE", List.of(pu), null, null, null, null);
        
        when(orderRepository.findById(orderId)).thenReturn(Optional.of(order));
        when(attemptRepository.findById(attemptId)).thenReturn(Optional.of(attempt));
        when(paypalClient.diagnoseOrder("PAYPAL-ORDER-123")).thenReturn(diagnostic);

        ReconciliationSafetyService.ReconciliationResult result = service.reconcileIfAuthorized(orderId, attemptId);

        assertThat(result.success()).isFalse();
        assertThat(result.code()).isEqualTo("CAPTURE_REFERENCE_MISMATCH");
    }

    @Test
    void failsWhenReferenceMissingOnAttempt() {
        order.setMerchantOrderReference(null);
        when(orderRepository.findById(orderId)).thenReturn(Optional.of(order));
        when(attemptRepository.findById(attemptId)).thenReturn(Optional.of(attempt));

        ReconciliationSafetyService.ReconciliationResult result = service.reconcileIfAuthorized(orderId, attemptId);

        assertThat(result.success()).isFalse();
        assertThat(result.code()).isEqualTo("MISSING_PURCHASE_UNIT");
    }

    @Test
    void rejectsWhenPurchaseUnitReferenceDoesNotMatch() {
        PaypalOrderUnitDiagnostic pu = new PaypalOrderUnitDiagnostic(
                "other-ref-123", "USD", "100.00",
                "CAPTURE-456", "COMPLETED", "100.00", "USD");
        PaypalOrderDiagnostic diagnostic = new PaypalOrderDiagnostic(
                200, "COMPLETED", "CAPTURE", List.of(pu), null, null, null, null);

        when(orderRepository.findById(orderId)).thenReturn(Optional.of(order));
        when(attemptRepository.findById(attemptId)).thenReturn(Optional.of(attempt));
        when(paypalClient.diagnoseOrder("PAYPAL-ORDER-123")).thenReturn(diagnostic);

        ReconciliationSafetyService.ReconciliationResult result = service.reconcileIfAuthorized(orderId, attemptId);

        assertThat(result.success()).isFalse();
        assertThat(result.code()).isEqualTo("MISSING_PURCHASE_UNIT");
    }

    @Test
    void matchesCorrectUnitWhenMultiplePurchaseUnitsExist() {
        PaypalOrderUnitDiagnostic nonMatching = new PaypalOrderUnitDiagnostic(
                "other-ref-123", "USD", "50.00", null, null, null, null);
        PaypalOrderUnitDiagnostic matching = completedCaptureUnit();
        PaypalOrderDiagnostic diagnostic = new PaypalOrderDiagnostic(
                200, "COMPLETED", "CAPTURE", List.of(nonMatching, matching), null, null, null, null);

        when(orderRepository.findById(orderId)).thenReturn(Optional.of(order));
        when(attemptRepository.findById(attemptId)).thenReturn(Optional.of(attempt));
        when(paypalClient.diagnoseOrder("PAYPAL-ORDER-123")).thenReturn(diagnostic);
        when(orderRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(attemptRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        ReconciliationSafetyService.ReconciliationResult result = service.reconcileIfAuthorized(orderId, attemptId);

        assertThat(result.success()).isTrue();
        assertThat(result.code()).isEqualTo("RECONCILED");
    }

    @Test
    void rejectsWhenMultipleUnitsHaveSameMatchingReference() {
        PaypalOrderUnitDiagnostic firstMatching = completedCaptureUnit();
        PaypalOrderUnitDiagnostic secondMatching = new PaypalOrderUnitDiagnostic(
                "order-ref-123", "USD", "100.00", "CAPTURE-789", "COMPLETED", "100.00", "USD");
        PaypalOrderDiagnostic diagnostic = new PaypalOrderDiagnostic(
                200, "COMPLETED", "CAPTURE", List.of(firstMatching, secondMatching), null, null, null, null);

        when(orderRepository.findById(orderId)).thenReturn(Optional.of(order));
        when(attemptRepository.findById(attemptId)).thenReturn(Optional.of(attempt));
        when(paypalClient.diagnoseOrder("PAYPAL-ORDER-123")).thenReturn(diagnostic);

        ReconciliationSafetyService.ReconciliationResult result = service.reconcileIfAuthorized(orderId, attemptId);

        assertThat(result.success()).isFalse();
        assertThat(result.code()).isEqualTo("MISSING_PURCHASE_UNIT");
    }

    @Test
    void rejectsWhenNoPurchaseUnitMatchesReference() {
        PaypalOrderUnitDiagnostic nonMatching = new PaypalOrderUnitDiagnostic(
                "other-ref-123", "USD", "100.00",
                "CAPTURE-456", "COMPLETED", "100.00", "USD");
        PaypalOrderDiagnostic diagnostic = new PaypalOrderDiagnostic(
                200, "COMPLETED", "CAPTURE", List.of(nonMatching), null, null, null, null);

        when(orderRepository.findById(orderId)).thenReturn(Optional.of(order));
        when(attemptRepository.findById(attemptId)).thenReturn(Optional.of(attempt));
        when(paypalClient.diagnoseOrder("PAYPAL-ORDER-123")).thenReturn(diagnostic);

        ReconciliationSafetyService.ReconciliationResult result = service.reconcileIfAuthorized(orderId, attemptId);

        assertThat(result.success()).isFalse();
        assertThat(result.code()).isEqualTo("MISSING_PURCHASE_UNIT");
    }
}
