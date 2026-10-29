package com.retryguard_ai.service;

import com.retryguard_ai.ai.AgentProperties;
import com.retryguard_ai.entity.MerchantOrder;
import com.retryguard_ai.entity.PaymentAttempt;
import com.retryguard_ai.repository.AgentWorkflowRepository;
import com.retryguard_ai.repository.MerchantOrderRepository;
import com.retryguard_ai.repository.PaymentAttemptRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class StuckOrderDetectionServiceTest {

    @Mock private AgentProperties properties;
    @Mock private MerchantOrderRepository orderRepository;
    @Mock private PaymentAttemptRepository attemptRepository;
    @Mock private AgentWorkflowService workflowService;
    @Mock private AgentWorkflowRepository workflowRepository;

    private StuckOrderDetectionService service;
    private MerchantOrder stuckOrder;
    private UUID orderId;

    @BeforeEach
    void setUp() {
        service = new StuckOrderDetectionService(properties, orderRepository, attemptRepository, workflowRepository, workflowService);
        
        orderId = UUID.randomUUID();
        stuckOrder = new MerchantOrder();
        stuckOrder.setId(orderId);
        stuckOrder.setMerchantOrderReference("stuck-order-123");
        stuckOrder.setAmount(new java.math.BigDecimal("50.00"));
        stuckOrder.setCurrency("USD");
        stuckOrder.setOrderStatus(MerchantOrder.OrderStatus.CREATED);
        stuckOrder.setCreatedAt(OffsetDateTime.now().minusMinutes(60));
    }

    @Test
    void scanDoesNothingWhenAgentDisabled() {
        when(properties.enabled()).thenReturn(false);
        when(properties.schedulerEnabled()).thenReturn(true);

        service.scanForStuckOrders();

        verify(orderRepository, never()).findByOrderStatusInAndCreatedAtBefore(any(), any());
    }

    @Test
    void scanDoesNothingWhenSchedulerDisabled() {
        when(properties.enabled()).thenReturn(true);
        when(properties.schedulerEnabled()).thenReturn(false);

        service.scanForStuckOrders();

        verify(orderRepository, never()).findByOrderStatusInAndCreatedAtBefore(any(), any());
    }

    @Test
    void scanFindsStuckOrdersAndRunsWorkflow() {
        when(properties.enabled()).thenReturn(true);
        when(properties.schedulerEnabled()).thenReturn(true);
        when(properties.stuckOrderMinutes()).thenReturn(30);
        when(properties.maxRunsPerScan()).thenReturn(10);
        
        when(orderRepository.findByOrderStatusInAndCreatedAtBefore(any(), any()))
                .thenReturn(List.of(stuckOrder));
        
        PaymentAttempt paypalAttempt = new PaymentAttempt();
        paypalAttempt.setProvider("PAYPAL_SANDBOX");
        when(attemptRepository.findByOrder_IdAndProvider(orderId, "PAYPAL_SANDBOX"))
                .thenReturn(List.of(paypalAttempt));

        service.scanForStuckOrders();

        verify(orderRepository).findByOrderStatusInAndCreatedAtBefore(any(), any());
        verify(workflowService).runWorkflow(orderId);
    }

    @Test
    void scanSkipsOrdersWithoutPaypalAttempts() {
        when(properties.enabled()).thenReturn(true);
        when(properties.schedulerEnabled()).thenReturn(true);
        when(properties.stuckOrderMinutes()).thenReturn(30);
        when(properties.maxRunsPerScan()).thenReturn(10);
        
        when(orderRepository.findByOrderStatusInAndCreatedAtBefore(any(), any()))
                .thenReturn(List.of(stuckOrder));
        
        when(attemptRepository.findByOrder_IdAndProvider(orderId, "PAYPAL_SANDBOX"))
                .thenReturn(List.of());

        service.scanForStuckOrders();

        verify(workflowService, never()).runWorkflow(any());
    }

    @Test
    void scanRespectsMaxRunsPerScan() {
        when(properties.enabled()).thenReturn(true);
        when(properties.schedulerEnabled()).thenReturn(true);
        when(properties.stuckOrderMinutes()).thenReturn(30);
        when(properties.maxRunsPerScan()).thenReturn(2);
        
        MerchantOrder order2 = new MerchantOrder();
        order2.setId(UUID.randomUUID());
        order2.setOrderStatus(MerchantOrder.OrderStatus.CREATED);
        order2.setCreatedAt(OffsetDateTime.now().minusMinutes(60));
        
        MerchantOrder order3 = new MerchantOrder();
        order3.setId(UUID.randomUUID());
        order3.setOrderStatus(MerchantOrder.OrderStatus.CREATED);
        order3.setCreatedAt(OffsetDateTime.now().minusMinutes(60));
        
        when(orderRepository.findByOrderStatusInAndCreatedAtBefore(any(), any()))
                .thenReturn(List.of(stuckOrder, order2, order3));
        
        PaymentAttempt paypalAttempt = new PaymentAttempt();
        paypalAttempt.setProvider("PAYPAL_SANDBOX");
        when(attemptRepository.findByOrder_IdAndProvider(any(), eq("PAYPAL_SANDBOX")))
                .thenReturn(List.of(paypalAttempt));

        service.scanForStuckOrders();

        verify(workflowService, times(2)).runWorkflow(any());
    }

    @Test
    void scanContinuesOnWorkflowException() {
        when(properties.enabled()).thenReturn(true);
        when(properties.schedulerEnabled()).thenReturn(true);
        when(properties.stuckOrderMinutes()).thenReturn(30);
        when(properties.maxRunsPerScan()).thenReturn(10);
        
        when(orderRepository.findByOrderStatusInAndCreatedAtBefore(any(), any()))
                .thenReturn(List.of(stuckOrder));
        
        PaymentAttempt paypalAttempt = new PaymentAttempt();
        paypalAttempt.setProvider("PAYPAL_SANDBOX");
        when(attemptRepository.findByOrder_IdAndProvider(orderId, "PAYPAL_SANDBOX"))
                .thenReturn(List.of(paypalAttempt));
        
        doThrow(new RuntimeException("Workflow failed")).when(workflowService).runWorkflow(orderId);

        service.scanForStuckOrders();

        verify(workflowService).runWorkflow(orderId);
    }

    @Test
    void manualScanThrowsWhenAgentDisabled() {
        when(properties.enabled()).thenReturn(false);

        assertThatThrownBy(() -> service.runManualScan())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("disabled");
    }

    @Test
    void manualScanProcessesStuckOrders() {
        when(properties.enabled()).thenReturn(true);
        when(properties.stuckOrderMinutes()).thenReturn(30);
        when(properties.maxRunsPerScan()).thenReturn(10);
        
        when(orderRepository.findByOrderStatusInAndCreatedAtBefore(any(), any()))
                .thenReturn(List.of(stuckOrder));
        
        PaymentAttempt paypalAttempt = new PaymentAttempt();
        paypalAttempt.setProvider("PAYPAL_SANDBOX");
        when(attemptRepository.findByOrder_IdAndProvider(orderId, "PAYPAL_SANDBOX"))
                .thenReturn(List.of(paypalAttempt));

        int processed = service.runManualScan();

        assertThat(processed).isEqualTo(1);
        verify(workflowService).runWorkflow(orderId);
    }
}