package com.payment.paymentsystem.service;


import com.payment.paymentsystem.dto.PaymentResponse;
import com.payment.paymentsystem.entity.Order;
import com.payment.paymentsystem.entity.Payment;
import com.payment.paymentsystem.entity.PaymentStatus;
import com.payment.paymentsystem.exception.InvalidPaymentRequestException;
import com.payment.paymentsystem.exception.OrderNotFoundException;
import com.payment.paymentsystem.exception.PaymentNotFoundException;
import com.payment.paymentsystem.mapper.PaymentMapper;
import com.payment.paymentsystem.repository.OrderRepository;
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
    private final OrderRepository orderRepository;
    private final PaymentMapper paymentMapper;

    public PaymentApprovalService(PaymentRepository paymentRepository, OrderRepository orderRepository,
                                  PaymentMapper paymentMapper) {
        this.paymentRepository = paymentRepository;
        this.orderRepository = orderRepository;
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

        // Step 2: acquire pessimistic write lock on the order.
        // This serializes concurrent approvals for payments under the same order.
        // If another transaction already holds this lock, we block here until it
        // commits or rolls back.      validation, or any work between read and write.
        Order order = orderRepository.findByIdForUpdate(payment.getOrderId())
                .orElseThrow(() -> new OrderNotFoundException(payment.getOrderId()));

        log.info("Acquired order lock for orderId={} while approving paymentId={}",
                order.getId(), paymentId);

        // Step 3: now that we hold the lock, any state read is guaranteed stable
        //         until we commit. The count below reflects the committed reality;
        //         no other transaction can sneak in a SUCCESS for this order.
        long successCount = paymentRepository.countByOrderIdAndStatus(
                payment.getOrderId(), PaymentStatus.SUCCESS);

        log.info("paymentId={} successCount for orderId={} is {}",
                paymentId, payment.getOrderId(), successCount);

        if (successCount > 0) {
            throw new InvalidPaymentRequestException(
                    "Order " + payment.getOrderId() + " already has a SUCCESS payment");
        }

        // Step 4: promote to SUCCESS. The lock guarantees no other transaction
        //         could have inserted a SUCCESS while we were deciding.
        payment.setStatus(PaymentStatus.SUCCESS);
        Payment saved = paymentRepository.save(payment);

        log.info("paymentId={} APPROVED successfully", saved.getId());
        return paymentMapper.toResponse(saved);
        // Transaction commits here, releasing the order lock.

    }
}
