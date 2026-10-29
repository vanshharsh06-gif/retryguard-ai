package com.retryguard_ai.service;

import com.retryguard_ai.dto.CreateOrderRequest;
import com.retryguard_ai.entity.MerchantOrder;
import com.retryguard_ai.exception.MerchantOrderAlreadyExistsException;
import com.retryguard_ai.repository.MerchantOrderRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Service
public class MerchantOrderService {

    private final MerchantOrderRepository merchantOrderRepository;

    public MerchantOrderService(MerchantOrderRepository merchantOrderRepository) {
        this.merchantOrderRepository = merchantOrderRepository;
    }

    @Transactional
    public MerchantOrder createOrder(CreateOrderRequest request) {
        String merchantOrderReference = request.getMerchantOrderReference();
        if (merchantOrderRepository.findByMerchantOrderReference(merchantOrderReference).isPresent()) {
            throw new MerchantOrderAlreadyExistsException(merchantOrderReference);
        }

        MerchantOrder merchantOrder = new MerchantOrder();
        merchantOrder.setMerchantOrderReference(merchantOrderReference);
        merchantOrder.setAmount(request.getAmount());
        merchantOrder.setCurrency(request.getCurrency());
        merchantOrder.setOrderStatus(MerchantOrder.OrderStatus.CREATED);

        return merchantOrderRepository.save(merchantOrder);
    }

    @Transactional(readOnly = true)
    public List<MerchantOrder> getRecentOrders() {
        return merchantOrderRepository.findTop50ByOrderByCreatedAtDesc();
    }
}
