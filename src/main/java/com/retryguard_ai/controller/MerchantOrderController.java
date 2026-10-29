package com.retryguard_ai.controller;

import com.retryguard_ai.dto.CreateOrderRequest;
import com.retryguard_ai.dto.CreateOrderResponse;
import com.retryguard_ai.entity.MerchantOrder;
import com.retryguard_ai.service.MerchantOrderService;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/orders")
public class MerchantOrderController {

    private final MerchantOrderService merchantOrderService;

    public MerchantOrderController(MerchantOrderService merchantOrderService) {
        this.merchantOrderService = merchantOrderService;
    }

    @GetMapping
    public ResponseEntity<List<CreateOrderResponse>> getRecentOrders() {
        List<CreateOrderResponse> orders = merchantOrderService.getRecentOrders().stream()
                .map(order -> new CreateOrderResponse(
                        order.getId(),
                        order.getMerchantOrderReference(),
                        order.getAmount(),
                        order.getCurrency(),
                        order.getOrderStatus().name(),
                        order.getCreatedAt()))
                .toList();
        return ResponseEntity.ok(orders);
    }

    @PostMapping
    public ResponseEntity<CreateOrderResponse> createOrder(
            @Valid @RequestBody CreateOrderRequest request) {
        MerchantOrder order = merchantOrderService.createOrder(request);
        CreateOrderResponse response = new CreateOrderResponse(
                order.getId(),
                order.getMerchantOrderReference(),
                order.getAmount(),
                order.getCurrency(),
                order.getOrderStatus().name(),
                order.getCreatedAt());

        return ResponseEntity.status(HttpStatus.CREATED).body(response);
    }
}
