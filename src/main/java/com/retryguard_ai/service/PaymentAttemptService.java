package com.retryguard_ai.service;

import com.retryguard_ai.dto.CreatePaymentAttemptRequest;
import com.retryguard_ai.entity.MerchantOrder;
import com.retryguard_ai.entity.PaymentAttempt;
import com.retryguard_ai.exception.MerchantOrderNotFoundException;
import com.retryguard_ai.repository.MerchantOrderRepository;
import com.retryguard_ai.repository.PaymentAttemptRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

@Service
public class PaymentAttemptService {

    private final MerchantOrderRepository merchantOrderRepository;
    private final PaymentAttemptRepository paymentAttemptRepository;

    public PaymentAttemptService(
            MerchantOrderRepository merchantOrderRepository,
            PaymentAttemptRepository paymentAttemptRepository) {
        this.merchantOrderRepository = merchantOrderRepository;
        this.paymentAttemptRepository = paymentAttemptRepository;
    }

    @Transactional
    public PaymentAttempt createPaymentAttempt(
            UUID orderId, CreatePaymentAttemptRequest request) {
        MerchantOrder order = merchantOrderRepository.findById(orderId)
                .orElseThrow(() -> new MerchantOrderNotFoundException(orderId));

        PaymentAttempt paymentAttempt = new PaymentAttempt();
        paymentAttempt.setOrder(order);
        paymentAttempt.setProvider(request.getProvider());
        paymentAttempt.setAmount(order.getAmount());
        paymentAttempt.setCurrency(order.getCurrency());
        paymentAttempt.setPaymentStatus(PaymentAttempt.PaymentStatus.CREATED);

        return paymentAttemptRepository.save(paymentAttempt);
    }
}
