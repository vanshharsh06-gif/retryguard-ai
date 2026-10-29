package com.retryguard_ai.repository;

import com.retryguard_ai.entity.PaymentAttempt;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import jakarta.persistence.LockModeType;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface PaymentAttemptRepository extends JpaRepository<PaymentAttempt, UUID> {

    List<PaymentAttempt> findByOrder_Id(UUID orderId);

    List<PaymentAttempt> findByOrder_IdOrderByCreatedAtAsc(UUID orderId);

    Optional<PaymentAttempt> findByProviderPaymentReference(String providerPaymentReference);

    List<PaymentAttempt> findByProviderAndProviderPaymentReference(
            String provider, String providerPaymentReference);

    List<PaymentAttempt> findByProviderAndProviderCaptureReference(
            String provider, String providerCaptureReference);

    List<PaymentAttempt> findByOrder_IdAndProvider(UUID orderId, String provider);

    @EntityGraph(attributePaths = "order")
    Optional<PaymentAttempt> findWithOrderById(UUID id);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select attempt from PaymentAttempt attempt where attempt.id = :id")
    Optional<PaymentAttempt> findByIdForUpdate(@Param("id") UUID id);
}