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
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Optional;
import java.util.UUID;

@Service
public class PaymentService {

    private static final Logger log = LoggerFactory.getLogger(PaymentService.class);
    private final PaymentRepository paymentRepository;
    private final OrderRepository orderRepository;
    private final PaymentMapper paymentMapper;
    private final PaymentPersistenceService persistenceService;
    public PaymentService(PaymentRepository paymentRepository, OrderRepository orderRepository,
                          PaymentMapper paymentMapper, PaymentPersistenceService persistenceService) {
        this.paymentRepository = paymentRepository;
        this.orderRepository = orderRepository;
        this.paymentMapper = paymentMapper;
        this.persistenceService = persistenceService;
    }

    @Transactional
    public PaymentResponse createPayment(CreatePaymentRequest request){
        log.info("Creating payment for orderId={}, idempotencyKey={}",
                request.getOrderId(), request.getIdempotencyKey());

        Optional<Payment> existing = paymentRepository.findByIdempotencyKey(request.getIdempotencyKey());

        if(existing.isPresent()){
            return handleReplay(existing.get(), request);
        }

        Order order = orderRepository.findById(request.getOrderId())
                .orElseThrow(() -> new OrderNotFoundException(request.getOrderId()));

        validateOrderIsPayable(order);
        validatePaymentMatchesOrder(order, request);


        Payment payment = paymentMapper.toEntity(request);
        return saveOrReturnRaceWinner(payment, request.getIdempotencyKey());
    }

    @Transactional(readOnly = true)
    public PaymentResponse getPayment(UUID paymentId){
        Payment payment = paymentRepository.findById(paymentId)
                .orElseThrow(() -> new PaymentNotFoundException(paymentId));

        return paymentMapper.toResponse(payment);
    }


    private PaymentResponse handleReplay(Payment existing, CreatePaymentRequest request){
        if (!existing.getOrderId().equals(request.getOrderId()) ||
                existing.getAmount().compareTo(request.getAmount()) != 0 ||
                !existing.getCurrency().equals(request.getCurrency())) {

            log.warn("Idempotency key {} reused with mismatched intent. " +
                            "Original: orderId={}, amount={}, currency={}. " +
                            "New: orderId={}, amount={}, currency={}",
                    request.getIdempotencyKey(),
                    existing.getOrderId(), existing.getAmount(), existing.getCurrency(),
                    request.getOrderId(), request.getAmount(), request.getCurrency());

            throw new InvalidPaymentRequestException(
                    "Idempotency key reused with different request payload");
        }
        log.info("Idempotent replay for key={}: returning existing paymentId={}",
                request.getIdempotencyKey(), existing.getId());
        return paymentMapper.toResponse(existing);
    }

    private void validateOrderIsPayable(Order order) {
        if (order.getStatus() != OrderStatus.CREATED) {
            throw new InvalidPaymentRequestException(
                    "Order " + order.getId() + " is not payable (status=" + order.getStatus() + ")");
        }
    }

    private void validatePaymentMatchesOrder(Order order, CreatePaymentRequest request) {
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
    }

    private PaymentResponse saveOrReturnRaceWinner(Payment payment, String idempotencyKey){
        try {
            Payment saved = persistenceService.insert(payment);
            log.info("Payment created: id={}, status={}", saved.getId(), saved.getStatus());
            return paymentMapper.toResponse(saved);
        } catch (DataIntegrityViolationException ex) {
            log.info("Concurrent duplicate detected for key={}; refetching race winner",
                    idempotencyKey);

            Payment winner = persistenceService.findByIdempotencyKey(idempotencyKey);
            return paymentMapper.toResponse(winner);
        }
    }

}
