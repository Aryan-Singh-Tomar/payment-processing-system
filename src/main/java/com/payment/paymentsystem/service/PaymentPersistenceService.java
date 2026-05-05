package com.payment.paymentsystem.service;


import com.payment.paymentsystem.entity.Payment;
import com.payment.paymentsystem.repository.PaymentRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;


@Service
public class PaymentPersistenceService {

    private final PaymentRepository paymentRepository;

    public PaymentPersistenceService(PaymentRepository paymentRepository) {
        this.paymentRepository = paymentRepository;
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public Payment insert(Payment payment) {
        return paymentRepository.saveAndFlush(payment);
    }


    @Transactional(propagation = Propagation.REQUIRES_NEW, readOnly = true)
    public Payment findByIdempotencyKey(String idempotencyKey) {
        return paymentRepository.findByIdempotencyKey(idempotencyKey)
                .orElseThrow(() -> new IllegalStateException(
                        "Unique violation occurred but no payment found for key " +
                                idempotencyKey + " — this should be impossible"));
    }
}