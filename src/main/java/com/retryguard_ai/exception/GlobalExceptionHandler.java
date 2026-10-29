package com.retryguard_ai.exception;

import com.retryguard_ai.exception.PaypalOperationNotAllowedException;
import com.retryguard_ai.exception.PaypalProviderException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice
public class GlobalExceptionHandler {

    @ExceptionHandler(InvalidPaypalWebhookRequestException.class)
    public ResponseEntity<ProblemDetail> handleInvalidPaypalWebhookRequest(
            InvalidPaypalWebhookRequestException exception) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(
                HttpStatus.BAD_REQUEST, exception.getMessage());
        problem.setTitle("Invalid PayPal webhook request");
        return ResponseEntity.badRequest().body(problem);
    }

    @ExceptionHandler(InvalidPaypalWebhookSignatureException.class)
    public ResponseEntity<ProblemDetail> handleInvalidPaypalWebhookSignature() {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(
                HttpStatus.UNAUTHORIZED, "PayPal webhook signature was not accepted.");
        problem.setTitle("Webhook verification failed");
        return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(problem);
    }

    @ExceptionHandler(ProviderEventIdConflictException.class)
    public ResponseEntity<ProblemDetail> handleProviderEventIdConflict() {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(
                HttpStatus.CONFLICT, "Provider event identifier conflicts with an existing record.");
        problem.setTitle("Provider event conflict");
        return ResponseEntity.status(HttpStatus.CONFLICT).body(problem);
    }

    @ExceptionHandler(MerchantOrderAlreadyExistsException.class)
    public ResponseEntity<ProblemDetail> handleDuplicateMerchantOrder(
            MerchantOrderAlreadyExistsException exception) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(
                HttpStatus.CONFLICT, exception.getMessage());
        problem.setTitle("Duplicate merchant order reference");
        return ResponseEntity.status(HttpStatus.CONFLICT).body(problem);
    }

    @ExceptionHandler(MerchantOrderNotFoundException.class)
    public ResponseEntity<ProblemDetail> handleMerchantOrderNotFound(
            MerchantOrderNotFoundException exception) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(
                HttpStatus.NOT_FOUND, exception.getMessage());
        problem.setTitle("Merchant order not found");
        return ResponseEntity.status(HttpStatus.NOT_FOUND).body(problem);
    }

    @ExceptionHandler(InvestigationNotFoundException.class)
    public ResponseEntity<ProblemDetail> handleInvestigationNotFound(
            InvestigationNotFoundException exception) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(
                HttpStatus.NOT_FOUND, exception.getMessage());
        problem.setTitle("Investigation not found");
        return ResponseEntity.status(HttpStatus.NOT_FOUND).body(problem);
    }

    @ExceptionHandler(PaymentAttemptNotFoundException.class)
    public ResponseEntity<ProblemDetail> handlePaymentAttemptNotFound(
            PaymentAttemptNotFoundException exception) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(
                HttpStatus.NOT_FOUND, exception.getMessage());
        problem.setTitle("Payment attempt not found");
        return ResponseEntity.status(HttpStatus.NOT_FOUND).body(problem);
    }

    @ExceptionHandler(InvalidPaymentEventPayloadException.class)
    public ResponseEntity<ProblemDetail> handleInvalidPaymentEventPayload(
            InvalidPaymentEventPayloadException exception) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(
                HttpStatus.BAD_REQUEST, exception.getMessage());
        problem.setTitle("Invalid request");
        return ResponseEntity.badRequest().body(problem);
    }

    @ExceptionHandler(PaypalOperationNotAllowedException.class)
    public ResponseEntity<ProblemDetail> handlePaypalOperationNotAllowed(
            PaypalOperationNotAllowedException exception) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(
                HttpStatus.CONFLICT, exception.getMessage());
        problem.setTitle("PayPal operation not allowed");
        return ResponseEntity.status(HttpStatus.CONFLICT).body(problem);
    }

    @ExceptionHandler(PaypalProviderException.class)
    public ResponseEntity<ProblemDetail> handlePaypalProviderFailure(
            PaypalProviderException exception) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(
                exception.getStatus(), exception.getMessage());
        problem.setTitle("PayPal Sandbox request failed");
        return ResponseEntity.status(exception.getStatus()).body(problem);
    }
}
