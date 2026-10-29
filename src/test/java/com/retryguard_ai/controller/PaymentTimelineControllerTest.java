package com.retryguard_ai.controller;

import com.retryguard_ai.dto.OrderTimelineEntry;
import com.retryguard_ai.dto.OrderTimelineResponse;
import com.retryguard_ai.exception.GlobalExceptionHandler;
import com.retryguard_ai.exception.MerchantOrderNotFoundException;
import com.retryguard_ai.service.PaymentTimelineService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@ExtendWith(MockitoExtension.class)
class PaymentTimelineControllerTest {

    @Mock
    private PaymentTimelineService paymentTimelineService;

    @InjectMocks
    private PaymentTimelineController paymentTimelineController;

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.standaloneSetup(paymentTimelineController)
                .setControllerAdvice(new GlobalExceptionHandler())
                .build();
    }

    @Test
    void getTimelineReturnsChronologicalEntriesWithoutEventPayload() throws Exception {
        UUID orderId = UUID.randomUUID();
        OffsetDateTime receivedAt = OffsetDateTime.parse("2026-10-17T10:02:00Z");
        OrderTimelineEntry event = new OrderTimelineEntry(
                UUID.randomUUID(),
                "PAYMENT_EVENT_RECEIVED",
                receivedAt,
                "PAYPAL_SANDBOX",
                null,
                "PAYMENT.PENDING",
                "SIMULATED",
                "NOT_APPLICABLE",
                "RECEIVED",
                "event-1",
                null,
                null);
        when(paymentTimelineService.getOrderTimeline(orderId)).thenReturn(
                new OrderTimelineResponse(orderId, "order-ref", "CREATED", List.of(event)));

        mockMvc.perform(get("/api/orders/{orderId}/timeline", orderId))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.orderId").value(orderId.toString()))
                .andExpect(jsonPath("$.currentOrderStatus").value("CREATED"))
                .andExpect(jsonPath("$.entries[0].sourceType").value("SIMULATED"))
                .andExpect(jsonPath("$.entries[0].verificationStatus").value("NOT_APPLICABLE"))
                .andExpect(jsonPath("$.entries[0].processingStatus").value("RECEIVED"))
                .andExpect(jsonPath("$.entries[0].payload").doesNotExist());
    }

    @Test
    void getTimelineReturnsNotFoundForUnknownOrder() throws Exception {
        UUID orderId = UUID.randomUUID();
        when(paymentTimelineService.getOrderTimeline(orderId))
                .thenThrow(new MerchantOrderNotFoundException(orderId));

        mockMvc.perform(get("/api/orders/{orderId}/timeline", orderId))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.title").value("Merchant order not found"))
                .andExpect(jsonPath("$.status").value(404));
    }
}
