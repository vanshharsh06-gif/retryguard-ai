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
import java.util.List;
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

        String merchantReference = order.getMerchantOrderReference();
        if (merchantReference == null || merchantReference.isBlank()) {
            return ReconciliationResult.failure("MISSING_PURCHASE_UNIT",
                    "Order has no merchant order reference; cannot match PayPal purchase unit");
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

        // Match expected purchase unit to merchant order reference when provider supplies reference_id
        var matchedUnit = findMatchingPurchaseUnit(diagnostic.purchaseUnits(), merchantReference);
        if (matchedUnit == null) {
            return ReconciliationResult.failure("MISSING_PURCHASE_UNIT",
                    "No purchase unit matches the merchant order reference: " + merchantReference);
        }

        String paypalCurrency = matchedUnit.currencyCode();
        String paypalAmountStr = matchedUnit.value();

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

        // Verify actual capture record
        String captureId = matchedUnit.captureId();
        String captureStatus = matchedUnit.captureStatus();
        String captureAmountStr = matchedUnit.captureAmount();
        String captureCurrency = matchedUnit.captureCurrency();

        if (captureId == null || captureStatus == null) {
            return ReconciliationResult.failure("MISSING_CAPTURE_RECORD",
                    "No capture record found in PayPal response");
        }

        if (!COMPLETED_STATUS.equals(captureStatus)) {
            return ReconciliationResult.failure("CAPTURE_NOT_COMPLETED",
                    "PayPal capture status is not COMPLETED: " + captureStatus);
        }

        if (captureAmountStr == null || captureCurrency == null) {
            return ReconciliationResult.failure("MISSING_CAPTURE_AMOUNT_CURRENCY",
                    "PayPal capture missing amount or currency");
        }

        BigDecimal captureAmount;
        try {
            captureAmount = new BigDecimal(captureAmountStr);
        } catch (NumberFormatException e) {
            return ReconciliationResult.failure("INVALID_CAPTURE_AMOUNT_FORMAT",
                    "PayPal capture amount is not a valid decimal");
        }

        // Use actual capture amount and currency for final verification
        if (attempt.getAmount().compareTo(captureAmount) != 0) {
            return ReconciliationResult.failure("CAPTURE_AMOUNT_MISMATCH",
                    "Capture amount mismatch: local=" + attempt.getAmount() + ", capture=" + captureAmount);
        }

        if (!normalizeCurrency(captureCurrency).equals(normalizeCurrency(attempt.getCurrency()))) {
            return ReconciliationResult.failure("CAPTURE_CURRENCY_MISMATCH",
                    "Capture currency mismatch: local=" + attempt.getCurrency() + ", capture=" + captureCurrency);
        }

        // Verify capture ID against locally stored capture reference when available
        String attemptCaptureRef = attempt.getProviderCaptureReference();
        if (attemptCaptureRef != null && !attemptCaptureRef.isBlank()) {
            if (!attemptCaptureRef.equals(captureId)) {
                return ReconciliationResult.failure("CAPTURE_REFERENCE_MISMATCH",
                    "Capture reference mismatch: attempt=" + attemptCaptureRef + ", capture=" + captureId);
            }
        }

        // Additional verification: ensure the order intent is CAPTURE
        if (!"CAPTURE".equals(diagnostic.intent())) {
            return ReconciliationResult.failure("INVALID_ORDER_INTENT", "PayPal order intent is not CAPTURE: " + diagnostic.intent());
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

    private PaypalClient.PaypalOrderUnitDiagnostic findMatchingPurchaseUnit(
            List<PaypalClient.PaypalOrderUnitDiagnostic> units, String merchantReference) {
        if (units == null || units.isEmpty()) {
            return null;
        }

        // A merchant reference is required to reliably associate a purchase unit with an order.
        if (merchantReference == null || merchantReference.isBlank()) {
            return null;
        }

        List<PaypalClient.PaypalOrderUnitDiagnostic> matches = units.stream()
                .filter(unit -> merchantReference.equals(unit.referenceId()))
                .toList();

        if (matches.isEmpty()) {
            // No purchase unit carries the expected merchant reference.
            return null;
        }
        if (matches.size() > 1) {
            // Multiple purchase units carry the same merchant reference: ambiguous.
            return null;
        }
        return matches.get(0);
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