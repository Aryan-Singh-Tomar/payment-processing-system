package com.payment.paymentsystem.service;

import com.payment.paymentsystem.dto.CreatePaymentRequest;
import com.payment.paymentsystem.dto.PaymentResponse;
import com.payment.paymentsystem.entity.Order;
import com.payment.paymentsystem.entity.OrderStatus;
import com.payment.paymentsystem.entity.Payment;
import com.payment.paymentsystem.event.PaymentRequestedEvent;
import com.payment.paymentsystem.exception.InvalidPaymentRequestException;
import com.payment.paymentsystem.exception.OrderNotFoundException;
import com.payment.paymentsystem.exception.PaymentNotFoundException;
import com.payment.paymentsystem.kafka.PaymentEventProducer;
import com.payment.paymentsystem.mapper.PaymentMapper;
import com.payment.paymentsystem.repository.OrderRepository;
import com.payment.paymentsystem.repository.PaymentRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Service
public class PaymentService {

    private static final Logger log = LoggerFactory.getLogger(PaymentService.class);
    private final PaymentRepository paymentRepository;
    private final OrderRepository orderRepository;
    private final PaymentMapper paymentMapper;
    private final PaymentPersistenceService persistenceService;
    private final IdempotencyCacheService cacheService;
    private final ApplicationEventPublisher applicationEventPublisher;
    private final PaymentEventProducer paymentEventProducer;
    private final PaymentStateMachine stateMachine;



    public PaymentService(PaymentRepository paymentRepository, OrderRepository orderRepository,
                          PaymentMapper paymentMapper, PaymentPersistenceService persistenceService,
                          IdempotencyCacheService cacheService, ApplicationEventPublisher applicationEventPublisher,
                          PaymentEventProducer paymentEventProducer, PaymentStateMachine stateMachine) {
        this.paymentRepository = paymentRepository;
        this.orderRepository = orderRepository;
        this.paymentMapper = paymentMapper;
        this.persistenceService = persistenceService;
        this.cacheService = cacheService;
        this.applicationEventPublisher = applicationEventPublisher;
        this.paymentEventProducer = paymentEventProducer;
        this.stateMachine = stateMachine;
    }

    @Transactional
    public PaymentResponse createPayment(CreatePaymentRequest request){
        log.info("Creating payment for orderId={}, idempotencyKey={}",
                request.getOrderId(), request.getIdempotencyKey());

        // Step 1: Idempotency cache check
        Optional<PaymentResponse> cached = cacheService.get(request.getIdempotencyKey());
        if(cached.isPresent()){
            verifyCachedIntentMatches(cached.get(), request);
            log.info("Idempotent replay (cache) for key={}: returning paymentId={}",
                    request.getIdempotencyKey(), cached.get().getId());
            return cached.get();
        }

        // Step 2: Idempotency DB check
        Optional<Payment> existing = paymentRepository.findByIdempotencyKey(request.getIdempotencyKey());

        if(existing.isPresent()){
            PaymentResponse response = handleReplay(existing.get(), request);
            cacheService.put(request.getIdempotencyKey(), response);   // ← repopulate
            return response;
        }

        // Step 3: Order existence + payable check
        Order order = orderRepository.findById(request.getOrderId())
                .orElseThrow(() -> new OrderNotFoundException(request.getOrderId()));

        validateOrderIsPayable(order);
        validatePaymentMatchesOrder(order, request);

        // Step 4 (NEW Day 24): State machine check.
        // Block creation if the order already has a non-terminal or successful payment.
        List<Payment> existingForOrder = paymentRepository.findByOrderId(request.getOrderId());
        stateMachine.canCreateNewPayment(existingForOrder).ifPresent((reason) -> {
            log.warn("Rejecting payment creation for orderId={} — {}",
                    request.getOrderId(), reason);
            throw new InvalidPaymentRequestException(reason);
        });

        // Step 5: Create the payment.
        Payment payment = paymentMapper.toEntity(request);
        PaymentResponse response = saveOrReturnRaceWinner(payment, request.getIdempotencyKey());

        cacheService.put(request.getIdempotencyKey(), response);

        // Publish event AFTER transaction commits.
        // We register the event with Spring's ApplicationEventPublisher;
        // the @TransactionalEventListener below will fire it to Kafka
        // ONLY if this @Transactional method commits successfully.
        PaymentRequestedEvent event = PaymentRequestedEvent.of(
                response.getId(),
                response.getOrderId(),
                response.getAmount(),
                response.getCurrency()
        );

        applicationEventPublisher.publishEvent(event);

        return  response;
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void handlePaymentRequestAfterCommit(PaymentRequestedEvent event){
        paymentEventProducer.publishPaymentRequested(event);
    }

    @Transactional(readOnly = true)
    public PaymentResponse getPayment(UUID paymentId){
        Payment payment = paymentRepository.findById(paymentId)
                .orElseThrow(() -> new PaymentNotFoundException(paymentId));

        return paymentMapper.toResponse(payment);
    }

    private void verifyCachedIntentMatches(PaymentResponse cached, CreatePaymentRequest request){
        if (!cached.getOrderId().equals(request.getOrderId()) ||
                cached.getAmount().compareTo(request.getAmount()) != 0 ||
                !cached.getCurrency().equals(request.getCurrency())) {

            log.warn("Idempotency key {} reused with mismatched intent (cache hit). " +
                            "Cached: orderId={}, amount={}, currency={}. " +
                            "New: orderId={}, amount={}, currency={}",
                    request.getIdempotencyKey(),
                    cached.getOrderId(), cached.getAmount(), cached.getCurrency(),
                    request.getOrderId(), request.getAmount(), request.getCurrency());

            throw new InvalidPaymentRequestException(
                    "Idempotency key reused with different request payload");
        }
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
