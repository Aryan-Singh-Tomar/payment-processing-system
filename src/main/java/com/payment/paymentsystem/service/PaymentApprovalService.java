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
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;


@Service
public class PaymentApprovalService {

    private static final Logger log = LoggerFactory.getLogger(PaymentApprovalService.class);

    private final PaymentRepository paymentRepository;
    private final PaymentMapper paymentMapper;

    public PaymentApprovalService(PaymentRepository paymentRepository,
                                  PaymentMapper paymentMapper) {
        this.paymentRepository = paymentRepository;
        this.paymentMapper = paymentMapper;
    }

    /**
     * Promotes a PENDING payment to SUCCESS, but only if the order has no
     * other SUCCESS payment yet. Race-prone by design — see class javadoc.
     */
    @Transactional
    public PaymentResponse approve(UUID paymentId) {
        log.info("Approving paymentId={}", paymentId);

        // Step 1: read the payment
        Payment payment = paymentRepository.findById(paymentId)
                .orElseThrow(() -> new PaymentNotFoundException(paymentId));

        if (payment.getStatus() != PaymentStatus.PENDING) {
            throw new InvalidPaymentRequestException(
                    "Payment " + paymentId + " is not in PENDING (status=" + payment.getStatus() + ")");
        }

        // Step 2: simulate "thinking" so the race window is wide enough
        //         to actually trigger under our test load.
        //         Real-world equivalent: a slow gateway call, expensive
        //         validation, or any work between read and write.
        try {
            Thread.sleep(150);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }

        // Step 3: CHECK — is there already a SUCCESS payment for this order?
        long successCount = paymentRepository.countByOrderIdAndStatus(
                payment.getOrderId(), PaymentStatus.SUCCESS);

        log.info("paymentId={} successCount for orderId={} is {}",
                paymentId, payment.getOrderId(), successCount);

        if (successCount > 0) {
            throw new InvalidPaymentRequestException(
                    "Order " + payment.getOrderId() + " already has a SUCCESS payment");
        }

        // Step 4: ACT — promote to SUCCESS. RACE WINDOW IS BETWEEN STEP 3 AND STEP 4.
        // Two threads can both pass step 3 (because neither has committed yet),
        // and both will try to UPDATE here. The partial unique index will
        // catch one of them with a DataIntegrityViolationException → 500.
        payment.setStatus(PaymentStatus.SUCCESS);
        Payment saved = paymentRepository.save(payment);

        log.info("paymentId={} APPROVED successfully", saved.getId());
        return paymentMapper.toResponse(saved);
    }
}
