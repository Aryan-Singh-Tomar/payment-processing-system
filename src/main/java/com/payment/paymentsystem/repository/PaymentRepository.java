package com.payment.paymentsystem.repository;

import com.payment.paymentsystem.entity.Payment;
import com.payment.paymentsystem.entity.PaymentStatus;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface PaymentRepository extends JpaRepository<Payment, UUID> {
    Optional<Payment> findByIdempotencyKey(String idempotencyKey);
    List<Payment> findByOrderId(UUID orderId);
    List<Payment> findAllByOrderId(UUID orderId);
    List<Payment> findByStatus(PaymentStatus status);

    // NEW — used by Day 13's race-prone approval flow
    long countByOrderIdAndStatus(UUID orderId, PaymentStatus status);

    @Query("""
            SELECT p FROM Payment p
            WHERE p.status IN :statuses
              AND p.createdAt < :threshold
            ORDER BY p.createdAt ASC
            """)
    List<Payment> findStuckPayments(
            @Param("statuses") List<PaymentStatus> statuses,
            @Param("threshold") OffsetDateTime threshold,
            Pageable pageable
    );
}
