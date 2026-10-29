package com.retryguard_ai.repository;

import com.retryguard_ai.entity.PaymentEvent;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface PaymentEventRepository extends JpaRepository<PaymentEvent, UUID> {

    List<PaymentEvent> findByPaymentAttempt_Id(UUID paymentAttemptId);

    List<PaymentEvent> findByPaymentAttempt_IdInOrderByReceivedAtAsc(List<UUID> paymentAttemptIds);

    Optional<PaymentEvent> findByProviderAndProviderEventId(String provider, String providerEventId);

    @Query(value = """
            SELECT pg_advisory_xact_lock(hashtextextended(
                CONCAT(:provider, ':', :providerEventId), 0)) IS NULL
            """, nativeQuery = true)
    boolean lockIdempotencyKey(
            @Param("provider") String provider,
            @Param("providerEventId") String providerEventId);
}
