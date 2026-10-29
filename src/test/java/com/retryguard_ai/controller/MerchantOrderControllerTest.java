package com.retryguard_ai.controller;

import com.retryguard_ai.dto.CreateOrderRequest;
import com.retryguard_ai.entity.MerchantOrder;
import com.retryguard_ai.exception.GlobalExceptionHandler;
import com.retryguard_ai.exception.MerchantOrderAlreadyExistsException;
import com.retryguard_ai.service.MerchantOrderService;
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
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@ExtendWith(MockitoExtension.class)
class MerchantOrderControllerTest {

    @Mock
    private MerchantOrderService merchantOrderService;

    @InjectMocks
    private MerchantOrderController merchantOrderController;

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.standaloneSetup(merchantOrderController)
                .setControllerAdvice(new GlobalExceptionHandler())
                .build();
    }

    @Test
    void createOrderReturnsCreatedResponse() throws Exception {
        UUID orderId = UUID.fromString("b8654333-10bc-40ec-86e9-0b816fe990a1");
        OffsetDateTime createdAt = OffsetDateTime.parse("2026-10-16T10:00:00Z");
        MerchantOrder order = new MerchantOrder();
        order.setId(orderId);
        order.setMerchantOrderReference("merchant-ref-1");
        order.setAmount(new BigDecimal("19.95"));
        order.setCurrency("USD");
        order.setOrderStatus(MerchantOrder.OrderStatus.CREATED);
        order.setCreatedAt(createdAt);
        when(merchantOrderService.createOrder(any(CreateOrderRequest.class))).thenReturn(order);

        mockMvc.perform(post("/api/orders")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "merchantOrderReference": "merchant-ref-1",
                                  "amount": 19.95,
                                  "currency": "USD"
                                }
                                """))
                .andExpect(status().isCreated())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.id").value(orderId.toString()))
                .andExpect(jsonPath("$.merchantOrderReference").value("merchant-ref-1"))
                .andExpect(jsonPath("$.amount").value(19.95))
                .andExpect(jsonPath("$.currency").value("USD"))
                .andExpect(jsonPath("$.orderStatus").value("CREATED"))
                .andExpect(jsonPath("$.createdAt").isNotEmpty());

        ArgumentCaptor<CreateOrderRequest> requestCaptor =
                ArgumentCaptor.forClass(CreateOrderRequest.class);
        verify(merchantOrderService).createOrder(requestCaptor.capture());
        assertEquals("merchant-ref-1", requestCaptor.getValue().getMerchantOrderReference());
        assertEquals(new BigDecimal("19.95"), requestCaptor.getValue().getAmount());
        assertEquals("USD", requestCaptor.getValue().getCurrency());
    }

    @Test
    void createOrderRejectsInvalidRequestBeforeCallingService() throws Exception {
        mockMvc.perform(post("/api/orders")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "merchantOrderReference": "merchant-ref-1",
                                  "amount": 0,
                                  "currency": "usd"
                                }
                                """))
                .andExpect(status().isBadRequest());

        verifyNoInteractions(merchantOrderService);
    }

    @Test
    void createOrderReturnsConflictForDuplicateReference() throws Exception {
        when(merchantOrderService.createOrder(any(CreateOrderRequest.class)))
                .thenThrow(new MerchantOrderAlreadyExistsException("merchant-ref-1"));

        mockMvc.perform(post("/api/orders")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "merchantOrderReference": "merchant-ref-1",
                                  "amount": 19.95,
                                  "currency": "USD"
                                }
                                """))
                .andExpect(status().isConflict())
                .andExpect(content().contentTypeCompatibleWith("application/problem+json"))
                .andExpect(jsonPath("$.title").value("Duplicate merchant order reference"))
                .andExpect(jsonPath("$.status").value(409));

        verify(merchantOrderService).createOrder(any(CreateOrderRequest.class));
    }

    @Test
    void listsRecentOrdersAsStableDtos() throws Exception {
        MerchantOrder order = new MerchantOrder();
        order.setId(UUID.randomUUID());
        order.setMerchantOrderReference("recent-order");
        order.setAmount(new BigDecimal("19.95"));
        order.setCurrency("USD");
        order.setOrderStatus(MerchantOrder.OrderStatus.CREATED);
        order.setCreatedAt(OffsetDateTime.parse("2026-10-16T10:00:00Z"));
        when(merchantOrderService.getRecentOrders()).thenReturn(java.util.List.of(order));

        mockMvc.perform(get("/api/orders"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].merchantOrderReference").value("recent-order"))
                .andExpect(jsonPath("$[0].orderStatus").value("CREATED"));
    }
}
