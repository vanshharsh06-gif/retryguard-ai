package com.retryguard_ai.service;

import com.retryguard_ai.dto.CreatePaymentAttemptRequest;
import com.retryguard_ai.entity.MerchantOrder;
import com.retryguard_ai.entity.PaymentAttempt;
import com.retryguard_ai.exception.MerchantOrderNotFoundException;
import com.retryguard_ai.repository.MerchantOrderRepository;
import com.retryguard_ai.repository.PaymentAttemptRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class PaymentAttemptServiceTest {

    @Mock
    private MerchantOrderRepository merchantOrderRepository;

    @Mock
    private PaymentAttemptRepository paymentAttemptRepository;

    @InjectMocks
    private PaymentAttemptService paymentAttemptService;

    @Test
    void createPaymentAttemptCopiesOrderDetailsAndStartsCreated() {
        UUID orderId = UUID.randomUUID();
        MerchantOrder order = new MerchantOrder();
        order.setId(orderId);
        order.setAmount(new BigDecimal("42.75"));
        order.setCurrency("USD");
        when(merchantOrderRepository.findById(orderId)).thenReturn(Optional.of(order));
        when(paymentAttemptRepository.save(any(PaymentAttempt.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));

        PaymentAttempt result = paymentAttemptService.createPaymentAttempt(
                orderId, new CreatePaymentAttemptRequest("PAYPAL_SANDBOX"));

        assertSame(order, result.getOrder());
        assertEquals("PAYPAL_SANDBOX", result.getProvider());
        assertEquals(new BigDecimal("42.75"), result.getAmount());
        assertEquals("USD", result.getCurrency());
        assertEquals(PaymentAttempt.PaymentStatus.CREATED, result.getPaymentStatus());
        assertNull(result.getProviderPaymentReference());
        verify(paymentAttemptRepository).save(result);
    }

    @Test
    void createPaymentAttemptRejectsMissingOrder() {
        UUID orderId = UUID.randomUUID();
        when(merchantOrderRepository.findById(orderId)).thenReturn(Optional.empty());

        assertThrows(MerchantOrderNotFoundException.class,
                () -> paymentAttemptService.createPaymentAttempt(
                        orderId, new CreatePaymentAttemptRequest("PAYPAL_SANDBOX")));
        verify(paymentAttemptRepository, never()).save(any(PaymentAttempt.class));
    }

    @Test
    void createPaymentAttemptAllowsMultipleHistoryRowsForSameOrder() {
        UUID orderId = UUID.randomUUID();
        MerchantOrder order = new MerchantOrder();
        order.setId(orderId);
        order.setAmount(new BigDecimal("42.75"));
        order.setCurrency("USD");
        when(merchantOrderRepository.findById(orderId)).thenReturn(Optional.of(order));
        when(paymentAttemptRepository.save(any(PaymentAttempt.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));

        CreatePaymentAttemptRequest request =
                new CreatePaymentAttemptRequest("PAYPAL_SANDBOX");
        paymentAttemptService.createPaymentAttempt(orderId, request);
        paymentAttemptService.createPaymentAttempt(orderId, request);

        verify(paymentAttemptRepository, times(2)).save(any(PaymentAttempt.class));
    }
}
