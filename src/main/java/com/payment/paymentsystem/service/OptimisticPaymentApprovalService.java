package com.payment.paymentsystem.service;

import com.payment.paymentsystem.dto.PaymentResponse;
import com.payment.paymentsystem.exception.InvalidPaymentRequestException;
import com.payment.paymentsystem.mapper.PaymentMapper;
import com.payment.paymentsystem.repository.PaymentRepository;
import jakarta.persistence.OptimisticLockException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.stereotype.Service;

import java.util.UUID;

@Service
public class OptimisticPaymentApprovalService {
    private static final Logger log = LoggerFactory.getLogger(OptimisticPaymentApprovalService.class);
    private static final int MAX_ATTEMPTS = 3;
    private final PaymentRepository paymentRepository;
    private final PaymentMapper paymentMapper;
    private final PaymentApprovalTransaction approvalTransaction;

    public OptimisticPaymentApprovalService(PaymentRepository paymentRepository,
                                            PaymentMapper paymentMapper,
                                            PaymentApprovalTransaction approvalTransaction) {
        this.paymentRepository = paymentRepository;
        this.paymentMapper = paymentMapper;
        this.approvalTransaction = approvalTransaction;
    }

    /**
     * Outer method handles retries. NOT @Transactional — each attempt
     * runs in its own transaction via approvalTransaction.attempt().
     */
    public PaymentResponse approve(UUID paymentId){
        log.info("Optimistic approve request for paymentId={}", paymentId);

        for(int attempt = 1; attempt <= MAX_ATTEMPTS; attempt++){
            try{
                return approvalTransaction.attempt(paymentId);
            }catch (ObjectOptimisticLockingFailureException | OptimisticLockException ex){
                log.warn("Optimistic lock conflict on attempt {}/{} for paymentId={}, retrying",
                        attempt, MAX_ATTEMPTS, paymentId);

                if(attempt == MAX_ATTEMPTS){
                    throw new InvalidPaymentRequestException(
                            "Could not approve payment after " + MAX_ATTEMPTS +
                                    " attempts due to concurrent contention");
                }
                // Brief backoff so the retry doesn't race the original retry
                sleepBriefly(attempt);
            }catch (DataIntegrityViolationException ex) {
                // The partial unique index fired — possible if multiple retries
                // somehow still raced. Treat as the same business condition.
                log.warn("Data integrity violation during optimistic approve for paymentId={}", paymentId);
                throw new InvalidPaymentRequestException(
                        "Order already has a SUCCESS payment");
            }
        }
        // Unreachable
        throw new IllegalStateException("Retry loop exited without returning or throwing");
    }

    private void sleepBriefly(int attempt) {
        try {
            // Tiny jittered backoff: 10ms, 20ms, 30ms
            Thread.sleep(10L * attempt);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
