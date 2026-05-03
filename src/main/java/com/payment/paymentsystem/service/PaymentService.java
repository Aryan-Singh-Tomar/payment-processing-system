package com.payment.paymentsystem.service;

import com.payment.paymentsystem.dto.CreatePaymentRequest;
import com.payment.paymentsystem.dto.PaymentResponse;
import com.payment.paymentsystem.entity.Order;
import com.payment.paymentsystem.entity.OrderStatus;
import com.payment.paymentsystem.entity.Payment;
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
public class PaymentService {

    private static final Logger log = LoggerFactory.getLogger(PaymentService.class);
    private final PaymentRepository paymentRepository;
    private final OrderRepository orderRepository;
    private final PaymentMapper paymentMapper;

    public PaymentService(PaymentRepository paymentRepository, OrderRepository orderRepository, PaymentMapper paymentMapper) {
        this.paymentRepository = paymentRepository;
        this.orderRepository = orderRepository;
        this.paymentMapper = paymentMapper;
    }

    @Transactional
    public PaymentResponse createPayment(CreatePaymentRequest request){
        log.info("Creating payment for orderId={}, idempotencyKey={}",
                request.getOrderId(), request.getIdempotencyKey());

        Order order = orderRepository.findById(request.getOrderId())
                .orElseThrow(() -> new OrderNotFoundException(request.getOrderId()));

        if(order.getStatus() != OrderStatus.CREATED){
            throw new InvalidPaymentRequestException(
                    "Order " + order.getId() + "is not payable (status =  " + order.getStatus() + ")"
            );
        }

        if(order.getAmount().compareTo(request.getAmount()) != 0){
            throw new InvalidPaymentRequestException(
                    "Payment amount " + request.getAmount() +
                            " does not match order amount " + order.getAmount());
        }

        if(!order.getCurrency().equals(request.getCurrency())){
            throw new InvalidPaymentRequestException(
                    "Payment currency " + request.getCurrency() +
                            " does not match order currency " + order.getCurrency());
        }

        Payment payment = paymentMapper.toEntity(request);
        Payment saved = paymentRepository.save(payment);

        log.info("Payment created: id={}, status={}", saved.getId(), saved.getStatus());
        return paymentMapper.toResponse(saved);
    }

    @Transactional(readOnly = true)
    public PaymentResponse getPayment(UUID paymentId){
        Payment payment = paymentRepository.findById(paymentId)
                .orElseThrow(() -> new PaymentNotFoundException(paymentId));

        return paymentMapper.toResponse(payment);
    }


}
