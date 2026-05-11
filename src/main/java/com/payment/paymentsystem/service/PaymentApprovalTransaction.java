package com.payment.paymentsystem.service;

import com.payment.paymentsystem.dto.PaymentResponse;
import com.payment.paymentsystem.entity.Payment;
import com.payment.paymentsystem.entity.PaymentStatus;
import com.payment.paymentsystem.exception.InvalidPaymentRequestException;
import com.payment.paymentsystem.exception.PaymentNotFoundException;
import com.payment.paymentsystem.mapper.PaymentMapper;
import com.payment.paymentsystem.repository.PaymentRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

@Service
public class PaymentApprovalTransaction {

    private static final Logger log = LoggerFactory.getLogger(PaymentApprovalTransaction.class);

    private final PaymentRepository paymentRepository;
    private final PaymentMapper paymentMapper;

    public PaymentApprovalTransaction(PaymentRepository paymentRepository,
                                      PaymentMapper paymentMapper) {
        this.paymentRepository = paymentRepository;
        this.paymentMapper = paymentMapper;
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public PaymentResponse attempt(UUID paymentId) {
        log.info("Attempt: approving paymentId={}", paymentId);

        Payment payment = paymentRepository.findById(paymentId)
                .orElseThrow(() -> new PaymentNotFoundException(paymentId));

        if (payment.getStatus() != PaymentStatus.PENDING) {
            throw new InvalidPaymentRequestException(
                    "Payment " + paymentId + " is not in PENDING (status=" + payment.getStatus() + ")");
        }

        // Same check as before. NOT a coordination mechanism — just an
        // optimization to fail fast when the conflict is already committed.
        long successCount = paymentRepository.countByOrderIdAndStatus(
                payment.getOrderId(), PaymentStatus.SUCCESS);

        log.info("paymentId={} successCount for orderId={} is {}",
                paymentId, payment.getOrderId(), successCount);

        if (successCount > 0) {
            throw new InvalidPaymentRequestException(
                    "Order " + payment.getOrderId() + " already has a SUCCESS payment");
        }

        // The actual coordination point: the UPDATE.
        // Hibernate adds WHERE version=? to this UPDATE. If another transaction
        // already changed this row, the UPDATE affects 0 rows and Hibernate
        // throws ObjectOptimisticLockingFailureException at flush/commit time.
        payment.setStatus(PaymentStatus.SUCCESS);
        Payment saved = paymentRepository.save(payment);

        log.info("paymentId={} APPROVED optimistically (version was {})",
                saved.getId(), saved.getVersion());
        return paymentMapper.toResponse(saved);
    }

}
