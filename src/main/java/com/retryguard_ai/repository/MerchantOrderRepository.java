package com.retryguard_ai.repository;

import com.retryguard_ai.entity.MerchantOrder;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface MerchantOrderRepository extends JpaRepository<MerchantOrder,UUID> {


	Optional<MerchantOrder> findByMerchantOrderReference(String merchantOrderReference );

	List<MerchantOrder> findTop50ByOrderByCreatedAtDesc();

	List<MerchantOrder> findByOrderStatusInAndCreatedAtBefore(List<MerchantOrder.OrderStatus> statuses, OffsetDateTime before);
}