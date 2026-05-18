package com.payment.paymentsystem.service;

import com.payment.paymentsystem.entity.Payment;
import com.payment.paymentsystem.entity.PaymentStatus;
import com.payment.paymentsystem.exception.PaymentNotFoundException;
import com.payment.paymentsystem.gateway.GatewayChargeResponse;
import com.payment.paymentsystem.repository.PaymentRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * Handles the "gateway returned SUCCESS but another payment for this order
 * already SUCCEEDED" edge case. Lives in a separate bean so it can run in
 * its own REQUIRES_NEW transaction — the calling transaction is already
 * poisoned by the constraint violation, so any work in it is rejected by
 * Postgres ("current transaction is aborted").
 *
 * The mechanism: by crossing a Spring bean boundary, Spring's proxy opens
 * a brand-new transaction. The poisoned original transaction is suspended,
 * we do our work in the new clean transaction, commit, and return. The
 * caller is then responsible for letting the original transaction roll
 * back cleanly.
 */

@Service
public class PaymentDuplicateHandler {
    private static final Logger log = LoggerFactory.getLogger(PaymentDuplicateHandler.class);
    private final PaymentRepository paymentRepository;
    public PaymentDuplicateHandler(PaymentRepository paymentRepository) {
        this.paymentRepository = paymentRepository;
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void markAsDuplicateFailure(UUID paymentId,
                                       GatewayChargeResponse.Success gatewaySuccess) {
        Payment payment = paymentRepository.findById(paymentId)
                .orElseThrow(() -> new PaymentNotFoundException(paymentId));

        payment.setStatus(PaymentStatus.FAILED);
        payment.setFailureReason("DUPLICATE_ORDER_SUCCESS");
        payment.setGatewayPaymentId(gatewaySuccess.gatewayTransactionId());
        payment.setGatewayResponse(
                "Gateway returned success (txn=" + gatewaySuccess.gatewayTransactionId() +
                        ") but order already has a SUCCESS payment. Manual reconciliation required.");
        payment.setProcessedAt(OffsetDateTime.now());

        paymentRepository.save(payment);

        log.warn("Payment {} marked FAILED with reason DUPLICATE_ORDER_SUCCESS. " +
                        "Gateway charge {} may require refund.",
                paymentId, gatewaySuccess.gatewayTransactionId());
    }

}
