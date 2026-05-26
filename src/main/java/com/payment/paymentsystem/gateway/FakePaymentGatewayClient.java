package com.payment.paymentsystem.gateway;

import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.stereotype.Component;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

/**
 * In-memory simulation of an external payment gateway.
 *
 * Behavior, governed by app.gateway.* properties:
 *   - <successRate>% returns Success with a fake gatewayTransactionId
 *   - <failureRate>% returns Failure with a randomly-selected failure code
 *   - <timeoutRate>% sleeps for <timeoutDurationMs> then returns Timeout
 *
 * Realistic processing latency on success/failure is sampled uniformly
 * from [minLatencyMs, maxLatencyMs]. This lets us tune for fast tests
 * (set both to 0) or realistic flows (200-600ms).
 *
 * Why this exists separately from the consumer:
 *   - Building the gateway before the consumer (Day 20) lets us test
 *     and inspect gateway behavior in isolation.
 *   - The gateway encapsulates the "outside world" — randomness,
 *     latency, failure modes — so the consumer code stays clean.
 *   - Easy to swap for a real HTTP-based gateway client later.
 */

@Component
@EnableConfigurationProperties(FakeGatewayProperties.class)
public class FakePaymentGatewayClient {

    private static final Logger log = LoggerFactory.getLogger(FakePaymentGatewayClient.class);

    private static final List<FailureScenario> FAILURE_SCENARIOS = List.of(
            new FailureScenario("CARD_DECLINED", "Card was declined by the issuing bank"),
            new FailureScenario("INSUFFICIENT_FUNDS", "Insufficient funds in account"),
            new FailureScenario("CARD_EXPIRED", "Card has expired"),
            new FailureScenario("INVALID_CVV", "CVV verification failed"),
            new FailureScenario("FRAUD_SUSPECTED", "Transaction flagged by fraud rules")
    );

    private final FakeGatewayProperties props;

    public FakePaymentGatewayClient(FakeGatewayProperties props) {
        this.props = props;
    }

    @PostConstruct
    void validateRatesSum() {
        double sum = props.successRate() + props.failureRate() + props.timeoutRate();
        // Allow tiny float drift; flag anything beyond that.
        if (Math.abs(sum - 1.0) > 0.001) {
            throw new IllegalStateException(
                    "Gateway rates must sum to 1.0 but sum to " + sum +
                            " (success=" + props.successRate() +
                            ", failure=" + props.failureRate() +
                            ", timeout=" + props.timeoutRate() + ")");
        }
        log.info("FakePaymentGatewayClient configured: success={}, failure={}, timeout={}",
                props.successRate(), props.failureRate(), props.timeoutRate());
    }


    /**
     * Synchronous call to the simulated gateway.
     * Blocks for some latency (or several seconds on timeout).
     *
     * Real production code would never block a thread like this for an
     * external HTTP call — you'd use reactive WebClient or async clients.
     * For our learning project, blocking is simpler and the consumer's
     * thread model is the same as production (one thread per Kafka message).
     */
    public GatewayChargeResponse charge(GatewayChargeRequest request) {
        log.info("Gateway charge initiated: paymentId={}, amount={} {}",
                request.paymentId(), request.amount(), request.currency());

        if (true) throw new RuntimeException("simulated transient failure for retry testing");


        double roll = ThreadLocalRandom.current().nextDouble();
        GatewayOutcome outcome = pickOutcome(roll);

        try {
            switch (outcome) {
                case SUCCESS -> {
                    sleepRandomLatency();
                    String txnId = "gw_" + UUID.randomUUID().toString().replace("-", "").substring(0, 16);
                    log.info("Gateway charge SUCCESS: paymentId={}, gatewayTxnId={}",
                            request.paymentId(), txnId);
                    return new GatewayChargeResponse.Success(
                            request.paymentId(),
                            txnId,
                            OffsetDateTime.now());
                }
                case FAILURE -> {
                    sleepRandomLatency();
                    FailureScenario scenario = FAILURE_SCENARIOS.get(
                            ThreadLocalRandom.current().nextInt(FAILURE_SCENARIOS.size()));
                    log.info("Gateway charge FAILURE: paymentId={}, code={}",
                            request.paymentId(), scenario.code());
                    return new GatewayChargeResponse.Failure(
                            request.paymentId(),
                            scenario.code(),
                            scenario.message(),
                            OffsetDateTime.now());
                }
                case TIMEOUT -> {
                    log.info("Gateway charge will TIMEOUT: paymentId={}", request.paymentId());
                    Thread.sleep(props.timeoutDurationMs());
                    log.warn("Gateway charge TIMED OUT: paymentId={}, durationMs={}",
                            request.paymentId(), props.timeoutDurationMs());
                    return new GatewayChargeResponse.Timeout(
                            request.paymentId(),
                            OffsetDateTime.now());
                }
                default -> throw new IllegalStateException("Unreachable: outcome=" + outcome);
            }
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            log.warn("Gateway charge interrupted: paymentId={}", request.paymentId());
            return new GatewayChargeResponse.Timeout(
                    request.paymentId(),
                    OffsetDateTime.now());
        }
    }

    private GatewayOutcome pickOutcome(double roll) {
        if (roll < props.successRate()) {
            return GatewayOutcome.SUCCESS;
        }
        if (roll < props.successRate() + props.failureRate()) {
            return GatewayOutcome.FAILURE;
        }
        return GatewayOutcome.TIMEOUT;
    }

    private void sleepRandomLatency() throws InterruptedException {
        int latency = ThreadLocalRandom.current().nextInt(
                props.minLatencyMs(),
                props.maxLatencyMs() + 1);
        Thread.sleep(latency);
    }

    private enum GatewayOutcome { SUCCESS, FAILURE, TIMEOUT }

    private record FailureScenario(String code, String message) {
    }

}
