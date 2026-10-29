package com.retryguard_ai.controller;

import com.retryguard_ai.dto.PaypalPaymentResponse;
import com.retryguard_ai.service.PaypalPaymentService;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

@RestController
@RequestMapping("/api/payment-attempts/{paymentAttemptId}/paypal")
public class PaypalPaymentController {

    private final PaypalPaymentService paypalPaymentService;

    public PaypalPaymentController(PaypalPaymentService paypalPaymentService) {
        this.paypalPaymentService = paypalPaymentService;
    }

    @PostMapping("/order")
    public ResponseEntity<PaypalPaymentResponse> createOrder(
            @PathVariable UUID paymentAttemptId) {
        return ResponseEntity.ok(map(paypalPaymentService.createOrder(paymentAttemptId)));
    }

    @PostMapping("/capture")
    public ResponseEntity<PaypalPaymentResponse> captureOrder(
            @PathVariable UUID paymentAttemptId) {
        return ResponseEntity.ok(map(paypalPaymentService.captureOrder(paymentAttemptId)));
    }

    @GetMapping(value = "/return", produces = MediaType.TEXT_HTML_VALUE)
    public ResponseEntity<String> returnFromPaypal(
            @PathVariable UUID paymentAttemptId,
            @RequestParam String token) {
        paypalPaymentService.validateReturn(paymentAttemptId, token);
        String capturePath = "/api/payment-attempts/" + paymentAttemptId + "/paypal/capture";
        String page = "<!doctype html><html><body>"
                + "<h1>PayPal approval received</h1>"
                + "<p>Approval does not confirm payment. Continue to capture the order.</p>"
                + "<form method=\"post\" action=\"" + capturePath + "\">"
                + "<button type=\"submit\">Capture payment</button></form>"
                + "</body></html>";
        return ResponseEntity.ok().contentType(MediaType.TEXT_HTML).body(page);
    }

    @GetMapping(value = "/cancel", produces = MediaType.TEXT_HTML_VALUE)
    public ResponseEntity<String> cancelPaypalCheckout(
            @PathVariable UUID paymentAttemptId,
            @RequestParam String token) {
        paypalPaymentService.validateCancellation(paymentAttemptId, token);
        String page = "<!doctype html><html><body>"
                + "<h1>PayPal checkout canceled</h1>"
                + "<p>Payment status was not changed. Check the payment status before retrying.</p>"
                + "</body></html>";
        return ResponseEntity.ok().contentType(MediaType.TEXT_HTML).body(page);
    }

    private PaypalPaymentResponse map(PaypalPaymentService.PaypalPaymentResult result) {
        return new PaypalPaymentResponse(
                result.paymentAttemptId(),
                result.amount(),
                result.currency(),
                result.paymentStatus(),
                result.providerStatus(),
                result.providerOrderId(),
                result.providerCaptureId(),
                result.approvalUrl());
    }
}
