package com.retryguard_ai.service;

import com.retryguard_ai.entity.MerchantOrder;
import com.retryguard_ai.entity.PaymentAttempt;
import com.retryguard_ai.paypal.PaypalClient;
import com.retryguard_ai.paypal.PaypalClient.PaypalOrderDiagnostic;
import com.retryguard_ai.repository.MerchantOrderRepository;
import com.retryguard_ai.repository.PaymentAttemptRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.Currency;
import java.util.UUID;

@Service
public class ReconciliationSafetyService {

    private static final String PAYPAL_SANDBOX = "PAYPAL_SANDBOX";
    private static final String COMPLETED_STATUS = "COMPLETED";

    private final MerchantOrderRepository orderRepository;
    private final PaymentAttemptRepository attemptRepository;
    private final PaypalClient paypalClient;

    public ReconciliationSafetyService(
            MerchantOrderRepository orderRepository,
            PaymentAttemptRepository attemptRepository,
            PaypalClient paypalClient) {
        this.orderRepository = orderRepository;
        this.attemptRepository = attemptRepository;
        this.paypalClient = paypalClient;
    }

    @Transactional
    public ReconciliationResult reconcileIfAuthorized(UUID orderId, UUID attemptId) {
        MerchantOrder order = orderRepository.findById(orderId).orElseGet(() -> null);

        if (order == null) {
            return ReconciliationResult.failure("ORDER_NOT_FOUND", "Order not found: " + orderId);
        }

        PaymentAttempt attempt = attemptRepository.findById(attemptId).orElseGet(() -> null);

        if (attempt == null) {
            return ReconciliationResult.failure("ATTEMPT_NOT_FOUND", "Attempt not found: " + attemptId);
        }

        if (!order.getId().equals(attempt.getOrder().getId())) {
            return ReconciliationResult.failure("ORDER_ATTEMPT_MISMATCH", "Attempt does not belong to the order");
        }

        if (order.getOrderStatus() == MerchantOrder.OrderStatus.PAID) {
            return ReconciliationResult.success("ALREADY_PAID", "Order is already PAID; no action needed");
        }

        if (!PAYPAL_SANDBOX.equals(attempt.getProvider())) {
            return ReconciliationResult.failure("INVALID_PROVIDER", "Attempt provider is not PAYPAL_SANDBOX");
        }

        String providerOrderId = attempt.getProviderPaymentReference();
        if (providerOrderId == null || providerOrderId.isBlank()) {
            return ReconciliationResult.failure("MISSING_PROVIDER_ORDER_ID", "No PayPal order ID on attempt");
        }

        PaypalOrderDiagnostic diagnostic = paypalClient.diagnoseOrder(providerOrderId);

        if (diagnostic.httpStatus() != 200) {
            return ReconciliationResult.failure("PROVIDER_UNAVAILABLE", "Could not verify PayPal status: HTTP " + diagnostic.httpStatus());
        }

        if (!COMPLETED_STATUS.equals(diagnostic.status())) {
            return ReconciliationResult.failure("CAPTURE_NOT_COMPLETED", "PayPal order status is not COMPLETED: " + diagnostic.status());
        }

        if (diagnostic.purchaseUnits() == null || diagnostic.purchaseUnits().isEmpty()) {
            return ReconciliationResult.failure("MISSING_PURCHASE_UNIT", "No purchase units in PayPal response");
        }

        var pu = diagnostic.purchaseUnits().get(0);
        String paypalCurrency = pu.currencyCode();
        String paypalAmountStr = pu.value();

        if (paypalCurrency == null || paypalAmountStr == null) {
            return ReconciliationResult.failure("MISSING_AMOUNT_CURRENCY", "PayPal response missing amount or currency");
        }

        BigDecimal paypalAmount;
        try {
            paypalAmount = new BigDecimal(paypalAmountStr);
        } catch (NumberFormatException e) {
            return ReconciliationResult.failure("INVALID_AMOUNT_FORMAT", "PayPal amount is not a valid decimal");
        }

        if (!normalizeCurrency(paypalCurrency).equals(normalizeCurrency(attempt.getCurrency()))) {
            return ReconciliationResult.failure("CURRENCY_MISMATCH", "Currency mismatch: local=" + attempt.getCurrency() + ", paypal=" + paypalCurrency);
        }

        if (attempt.getAmount().compareTo(paypalAmount) != 0) {
            return ReconciliationResult.failure("AMOUNT_MISMATCH", "Amount mismatch: local=" + attempt.getAmount() + ", paypal=" + paypalAmount);
        }

        // Additional verification: ensure the order intent is CAPTURE
        if (!"CAPTURE".equals(diagnostic.intent())) {
            return ReconciliationResult.failure("INVALID_ORDER_INTENT", "PayPal order intent is not CAPTURE: " + diagnostic.intent());
        }

        // Verify capture reference matches if available on both sides
        String attemptCaptureRef = attempt.getProviderCaptureReference();
        if (attemptCaptureRef != null && !attemptCaptureRef.isBlank()) {
            String captureRefFromPaypal = diagnostic.purchaseUnits().get(0).value();
            if (!attemptCaptureRef.equals(captureRefFromPaypal)) {
                return ReconciliationResult.failure("CAPTURE_REFERENCE_MISMATCH",
                    "Capture reference mismatch: attempt=" + attemptCaptureRef + ", paypal=" + captureRefFromPaypal);
            }
        }

        order.setOrderStatus(MerchantOrder.OrderStatus.PAID);
        orderRepository.save(order);

        if (attempt.getPaymentStatus() != PaymentAttempt.PaymentStatus.SUCCEEDED) {
            attempt.setPaymentStatus(PaymentAttempt.PaymentStatus.SUCCEEDED);
            attempt.setProviderStatus(COMPLETED_STATUS);
            attemptRepository.save(attempt);
        }

        return ReconciliationResult.success("RECONCILED", "Order reconciled to PAID based on verified PayPal COMPLETED capture");
    }

    private String normalizeCurrency(String currency) {
        if (currency == null) return null;
        try {
            return Currency.getInstance(currency.trim().toUpperCase()).getCurrencyCode();
        } catch (IllegalArgumentException e) {
            return currency.trim().toUpperCase();
        }
    }

    public record ReconciliationResult(
            boolean success,
            String code,
            String message
    ) {
        public static ReconciliationResult success(String code, String message) {
            return new ReconciliationResult(true, code, message);
        }

        public static ReconciliationResult failure(String code, String message) {
            return new ReconciliationResult(false, code, message);
        }
    }
}