package com.retryguard_ai.controller;

import com.retryguard_ai.dto.PaypalWebhookResponse;
import com.retryguard_ai.paypal.PaypalWebhookTransmission;
import com.retryguard_ai.service.PaypalWebhookProcessingResult;
import com.retryguard_ai.service.PaypalWebhookService;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/webhooks/paypal")
public class PaypalWebhookController {

    private final PaypalWebhookService paypalWebhookService;

    public PaypalWebhookController(PaypalWebhookService paypalWebhookService) {
        this.paypalWebhookService = paypalWebhookService;
    }

    @PostMapping
    public ResponseEntity<PaypalWebhookResponse> receive(
            @RequestHeader(value = "PAYPAL-AUTH-ALGO", required = false) String authAlgo,
            @RequestHeader(value = "PAYPAL-CERT-URL", required = false) String certUrl,
            @RequestHeader(value = "PAYPAL-TRANSMISSION-ID", required = false) String transmissionId,
            @RequestHeader(value = "PAYPAL-TRANSMISSION-SIG", required = false) String transmissionSignature,
            @RequestHeader(value = "PAYPAL-TRANSMISSION-TIME", required = false) String transmissionTime,
            @RequestBody(required = false) String originalPayload) {
        PaypalWebhookProcessingResult result = paypalWebhookService.receive(
                new PaypalWebhookTransmission(
                        authAlgo, certUrl, transmissionId, transmissionSignature, transmissionTime),
                originalPayload);
        String state = result.duplicate() ? "DUPLICATE" : "ACCEPTED";
        return ResponseEntity.status(HttpStatus.OK).body(new PaypalWebhookResponse(state));
    }
}
