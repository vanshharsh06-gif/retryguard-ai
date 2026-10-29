package com.retryguard_ai.service;

import com.retryguard_ai.dto.CreateOrderRequest;
import com.retryguard_ai.entity.MerchantOrder;
import com.retryguard_ai.exception.MerchantOrderAlreadyExistsException;
import com.retryguard_ai.repository.MerchantOrderRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class MerchantOrderServiceTest {

    @Mock
    private MerchantOrderRepository merchantOrderRepository;

    @InjectMocks
    private MerchantOrderService merchantOrderService;

    @Test
    void createOrderPersistsOrderWithCreatedStatus() {
        CreateOrderRequest request = new CreateOrderRequest(
                "merchant-ref-1", new BigDecimal("19.95"), "USD");
        when(merchantOrderRepository.findByMerchantOrderReference("merchant-ref-1"))
                .thenReturn(Optional.empty());
        when(merchantOrderRepository.save(any(MerchantOrder.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));

        MerchantOrder result = merchantOrderService.createOrder(request);

        assertEquals("merchant-ref-1", result.getMerchantOrderReference());
        assertEquals(new BigDecimal("19.95"), result.getAmount());
        assertEquals("USD", result.getCurrency());
        assertEquals(MerchantOrder.OrderStatus.CREATED, result.getOrderStatus());
        verify(merchantOrderRepository).save(result);
    }

    @Test
    void createOrderRejectsDuplicateMerchantReference() {
        CreateOrderRequest request = new CreateOrderRequest(
                "merchant-ref-1", new BigDecimal("19.95"), "USD");
        when(merchantOrderRepository.findByMerchantOrderReference("merchant-ref-1"))
                .thenReturn(Optional.of(new MerchantOrder()));

        assertThrows(MerchantOrderAlreadyExistsException.class,
                () -> merchantOrderService.createOrder(request));
        verify(merchantOrderRepository, never()).save(any(MerchantOrder.class));
    }
}
