package com.payment.paymentsystem.mapper;

import com.payment.paymentsystem.dto.CreatePaymentRequest;
import com.payment.paymentsystem.dto.PaymentResponse;
import com.payment.paymentsystem.entity.Payment;
import com.payment.paymentsystem.entity.PaymentStatus;
import org.springframework.stereotype.Component;

@Component
public class PaymentMapper {
    /**
     * Convert an inbound API request into a new Payment entity.
     * The entity's id, status, createdAt, updatedAt, and version are
     * left to @PrePersist / @Version to manage. We do not pre-set them.
     */

    public Payment toEntity(CreatePaymentRequest request){
        Payment payment = new Payment();
        payment.setOrderId(request.getOrderId());
        payment.setIdempotencyKey(request.getIdempotencyKey());
        payment.setAmount(request.getAmount());
        payment.setCurrency(request.getCurrency());
        payment.setStatus(PaymentStatus.PENDING);
        payment.setWebhookUrl(request.getWebhookUrl());

        return payment;
    }

    /**
     * Convert a persisted Payment entity into the response DTO sent to clients.
     * Only fields that are part of the public API contract are copied.
     */
    public PaymentResponse toResponse(Payment payment) {
        return new PaymentResponse(
                payment.getId(),
                payment.getOrderId(),
                payment.getAmount(),
                payment.getCurrency(),
                payment.getStatus(),
                payment.getCreatedAt()
        );
    }

}
