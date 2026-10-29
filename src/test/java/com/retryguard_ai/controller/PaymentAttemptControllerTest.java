package com.retryguard_ai.controller;

import com.retryguard_ai.dto.CreatePaymentAttemptRequest;
import com.retryguard_ai.entity.MerchantOrder;
import com.retryguard_ai.entity.PaymentAttempt;
import com.retryguard_ai.exception.GlobalExceptionHandler;
import com.retryguard_ai.exception.MerchantOrderNotFoundException;
import com.retryguard_ai.service.PaymentAttemptService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@ExtendWith(MockitoExtension.class)
class PaymentAttemptControllerTest {

    @Mock
    private PaymentAttemptService paymentAttemptService;

    @InjectMocks
    private PaymentAttemptController paymentAttemptController;

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.standaloneSetup(paymentAttemptController)
                .setControllerAdvice(new GlobalExceptionHandler())
                .build();
    }

    @Test
    void createPaymentAttemptReturnsCreatedResponse() throws Exception {
        UUID orderId = UUID.fromString("3e8a3af8-4239-40bf-94d8-77b638f5f18a");
        PaymentAttempt paymentAttempt = new PaymentAttempt();
        paymentAttempt.setId(UUID.fromString("a9e24da1-f40a-48c5-aa77-19539036a93e"));
        MerchantOrder order = new MerchantOrder();
        order.setId(orderId);
        paymentAttempt.setOrder(order);
        paymentAttempt.setProvider("PAYPAL_SANDBOX");
        paymentAttempt.setAmount(new BigDecimal("42.75"));
        paymentAttempt.setCurrency("USD");
        paymentAttempt.setPaymentStatus(PaymentAttempt.PaymentStatus.CREATED);
        paymentAttempt.setCreatedAt(OffsetDateTime.parse("2026-10-16T10:00:00Z"));
        when(paymentAttemptService.createPaymentAttempt(
                org.mockito.ArgumentMatchers.eq(orderId), any(CreatePaymentAttemptRequest.class)))
                .thenReturn(paymentAttempt);

        mockMvc.perform(post("/api/orders/{orderId}/payment-attempts", orderId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "provider": "PAYPAL_SANDBOX"
                                }
                                """))
                .andExpect(status().isCreated())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.id").value(paymentAttempt.getId().toString()))
                .andExpect(jsonPath("$.orderId").value(orderId.toString()))
                .andExpect(jsonPath("$.provider").value("PAYPAL_SANDBOX"))
                .andExpect(jsonPath("$.amount").value(42.75))
                .andExpect(jsonPath("$.currency").value("USD"))
                .andExpect(jsonPath("$.paymentStatus").value("CREATED"))
                .andExpect(jsonPath("$.providerPaymentReference").doesNotExist())
                .andExpect(jsonPath("$.createdAt").isNotEmpty());

        ArgumentCaptor<CreatePaymentAttemptRequest> requestCaptor =
                ArgumentCaptor.forClass(CreatePaymentAttemptRequest.class);
        verify(paymentAttemptService).createPaymentAttempt(
                org.mockito.ArgumentMatchers.eq(orderId), requestCaptor.capture());
        assertEquals("PAYPAL_SANDBOX", requestCaptor.getValue().getProvider());
    }

    @Test
    void createPaymentAttemptRejectsBlankProviderBeforeCallingService() throws Exception {
        UUID orderId = UUID.randomUUID();
        mockMvc.perform(post("/api/orders/{orderId}/payment-attempts", orderId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "provider": " "
                                }
                                """))
                .andExpect(status().isBadRequest());

        verifyNoInteractions(paymentAttemptService);
    }

    @Test
    void createPaymentAttemptRejectsUnsupportedProviderBeforeCallingService() throws Exception {
        UUID orderId = UUID.randomUUID();
        mockMvc.perform(post("/api/orders/{orderId}/payment-attempts", orderId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "provider": "INVALID_PROVIDER"
                                }
                                """))
                .andExpect(status().isBadRequest());

        verifyNoInteractions(paymentAttemptService);
    }

    @Test
    void createPaymentAttemptReturnsNotFoundForMissingOrder() throws Exception {
        UUID orderId = UUID.randomUUID();
        when(paymentAttemptService.createPaymentAttempt(
                org.mockito.ArgumentMatchers.eq(orderId), any(CreatePaymentAttemptRequest.class)))
                .thenThrow(new MerchantOrderNotFoundException(orderId));

        mockMvc.perform(post("/api/orders/{orderId}/payment-attempts", orderId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "provider": "PAYPAL_SANDBOX"
                                }
                                """))
                .andExpect(status().isNotFound())
                .andExpect(content().contentTypeCompatibleWith("application/problem+json"))
                .andExpect(jsonPath("$.title").value("Merchant order not found"))
                .andExpect(jsonPath("$.status").value(404));
    }
}
