# Reconciliation Design

## Purpose

The reconciliation job is the safety net that catches payments stuck in
non-terminal states. Without it, a single consumer crash, lost Kafka event,
or gateway timeout can leave a payment indefinitely in PENDING or PROCESSING.
With it, a periodic sweeper detects stuck payments and remediates them.

This document captures the design decisions, trade-offs, and known
limitations of the current implementation.

## The Stuck-Payment Problem

A "stuck" payment is one that has been in PENDING or PROCESSING for longer
than expected. The causes vary:

- Consumer crashed after committing the Kafka offset but before processing.
- Kafka event was lost between producer and broker.
- Consumer crashed during processing (between `markProcessing` and
  `recordResult`).
- Gateway timed out and the resulting UNKNOWN state was never resolved by
  an inbound webhook.

Whatever the cause, the symptom is the same: a payment that should have
reached a terminal state, didn't.

## The Sweeper Pattern

Reconciliation is an instance of a broader pattern called **sweepers** or
**janitors**: periodic processes that scan for inconsistent state and
remediate. Almost every distributed payment, messaging, or workflow system
has one.

Our sweeper runs every 60 seconds. For each scan:

1. Query payments in PENDING or PROCESSING with `created_at` older than
   the stuck threshold (default 5 minutes).
2. For each matching payment, remediate.

The threshold and interval are configurable via `app.reconciliation`
in `application.yaml`.

## Remediation Strategy: Re-emit With Dedup-Unmark

The current implementation:

1. Calls `processedEventService.unmark(paymentId, "PaymentRequested")` to
   delete the existing `processed_events` row (and Redis cache entry).
2. Re-emits the `PaymentRequestedEvent` to the Kafka topic.
3. The consumer picks it up as a fresh event (because the dedup record is
   gone) and processes it normally.

### Why Re-emit Instead Of Direct Processing?

Three reasons:

- **Reuses the existing code path.** The consumer's processing logic is the
  same in normal flow and reconciliation flow. There's no parallel
  implementation that can drift.
- **Leverages Day 22's dedup naturally.** Without the `unmark` step,
  re-emission would be blocked by dedup. With it, re-emission is treated
  as a legitimate retry.
- **Simple to test.** The reconciliation behavior is observable end-to-end:
  emit event, consumer processes, payment reaches terminal state.

### The Day 22 / Day 29 Coordination Problem

These two designs initially conflict:

- Day 22's `processed_events` table says: "once an event is processed,
  future duplicates are dropped."
- Day 29's reconciliation says: "this payment is stuck; re-emit its event
  so the consumer can try again."

Without coordination, the dedup permanently blocks reconciliation
re-emissions. Reconciliation finds the stuck payment, re-emits the event,
consumer sees the duplicate dedup row, skips processing, payment stays
stuck. Sweep again at the next interval. Same loop.

The resolution is `ProcessedEventService.unmark`: before re-emitting, we
explicitly clear the dedup record. This signals "this is a legitimate
retry, not a true duplicate delivery."

## Known Limitations And Trade-offs

This section is the most important part of this document. The current
implementation is acceptable for a learning project but has properties
that make it unsafe for a production payment system.

### Limitation 1: Double-Execution Risk For PROCESSING Payments

This is the biggest issue. Consider this scenario:

1. Consumer picks up event, marks payment PROCESSING.
2. Consumer calls the gateway. Gateway succeeds. Customer is charged.
3. Consumer crashes before `recordResult` runs.
4. Payment is stuck in PROCESSING with the gateway charge already made.
5. Reconciliation sweep runs. `unmark` deletes the dedup record. Event
   is re-emitted.
6. Consumer picks up the re-emitted event. `markProcessing` returns true
   (PROCESSING → PROCESSING is allowed by the Day 23 fix). Gateway is
   called again.
7. Customer is charged a second time.

Why doesn't the state machine save us? Day 24's `canCreateNewPayment`
runs on `POST /api/payments`, not inside the consumer. There's no check
inside the consumer's gateway-call path that asks "did I already charge
this customer?"

**Why this is acceptable for our project:**

- The gateway is fake (`FakePaymentGatewayClient`) and has no real-world
  side effect. Double-execution produces two log lines and zero real
  money movement.
- We don't simulate consumer crashes between gateway success and
  `recordResult`. The scenario above is theoretically possible but
  practically rare in our test environment.

**How a production system handles this:**

- **Gateway-level idempotency keys.** Real payment gateways (Stripe,
  Razorpay, Adyen) accept an idempotency key on each charge request.
  If the same key is used twice, the gateway returns the original result
  rather than charging again. This eliminates double-charge risk by
  construction.
- **Reconcile by query, not by re-emit.** Instead of blindly re-emitting,
  the reconciliation job queries the gateway directly: "what's the status
  of payment X?" The gateway returns the authoritative state (`SUCCEEDED`,
  `FAILED`, `NOT_FOUND`). Reconciliation transitions our DB to match. No
  retry, no duplicate gateway call, no risk.

The reconcile-by-query pattern is the gold standard for stuck-payment
resolution. It's the pull-mode counterpart to Day 26's gateway webhook
(the push mode). A production-grade implementation would use both.

### Limitation 2: Audit Trail Loss

Deleting the `processed_events` row destroys the record of the original
processing attempt. After a reconciliation cycle, the data looks identical
to a payment that was processed once normally. This makes it impossible to
distinguish, after the fact, between:

- A payment that was processed cleanly the first time.
- A payment that was processed once, reset, then reprocessed via
  reconciliation.

For a learning project, this is acceptable. For production:

- **Regulatory and compliance requirements** for payment systems often
  mandate retention of processing history.
- **Debugging** weird customer complaints requires the audit trail to
  reconstruct what happened.
- **Operational metrics** like "% of payments resolved via reconciliation"
  cannot be computed.

A production design would either:

- Use a separate event type (`PaymentReconciliationRetry`) so each
  attempt has its own row in `processed_events`.
- Add an `attempt_count` column to the payment row, incremented each time
  reconciliation re-emits.
- Write to a separate `payment_attempts` audit table.

### Limitation 3: No Multi-Instance Coordination

If two replicas of the service are running, both will execute the
scheduled sweep simultaneously. Both will find the same stuck payments,
both will re-emit events, both will unmark dedup rows. The downstream
consumer (with its dedup) will catch the duplicate work, but:

- Kafka receives twice the events it should.
- The producer cluster is double-loaded.
- Log volume doubles.

This is wasteful but not incorrect. Day 32's Redis distributed lock will
solve this by serializing sweeper instances: only the instance that holds
the lock runs the sweep.

### Limitation 4: No Bounded Retry

A payment that's stuck for a *persistent* reason (e.g., a buggy event
handler, corrupted data, gateway connectivity issue) will be re-emitted
on every sweep, forever. The system never gives up.

Production systems track an `attempts` counter per payment. After N
attempts (often 3-5), the payment is marked `FAILED` with reason
`MAX_RECONCILIATION_ATTEMPTS_EXCEEDED` and an alert is sent to on-call.

This is a small change but not implemented today.

## Production Design: Reconcile By Query

For reference, here's what a production-grade `remediate` would look like:

```java
private void remediate(Payment payment) {
    // 1. Increment attempt counter; escalate if exhausted.
    int attempts = payment.getReconciliationAttempts() + 1;
    payment.setReconciliationAttempts(attempts);
    payment.setLastReconciliationAt(OffsetDateTime.now());

    if (attempts > MAX_RECONCILIATION_ATTEMPTS) {
        payment.setStatus(PaymentStatus.FAILED);
        payment.setFailureReason("MAX_RECONCILIATION_ATTEMPTS_EXCEEDED");
        paymentRepository.save(payment);
        alertingService.notifyOnCall(
            "Payment %s exceeded max reconciliation attempts",
            payment.getId());
        return;
    }

    paymentRepository.save(payment);

    // 2. Query the gateway for the authoritative state.
    GatewayStatusResponse status = gatewayClient.queryStatus(payment.getId());

    switch (status) {
        case ALREADY_SUCCEEDED -> {
            // Gateway confirms the original attempt succeeded.
            // Sync our DB to match.
            payment.setStatus(PaymentStatus.SUCCESS);
            payment.setGatewayPaymentId(status.gatewayPaymentId());
            payment.setProcessedAt(OffsetDateTime.now());
            paymentRepository.save(payment);
            webhookDeliveryService.notify(payment);
        }
        case ALREADY_FAILED -> {
            payment.setStatus(PaymentStatus.FAILED);
            payment.setFailureReason(status.failureReason());
            payment.setProcessedAt(OffsetDateTime.now());
            paymentRepository.save(payment);
            webhookDeliveryService.notify(payment);
        }
        case NOT_FOUND -> {
            // Gateway never saw this payment. Safe to retry from scratch.
            // (This is the only case where re-emit is appropriate.)
            processedEventService.unmark(...);
            paymentEventProducer.publishPaymentRequested(...);
        }
    }
}
```

This design:

- Eliminates double-charge risk (we never blindly re-call the gateway).
- Preserves the audit trail (no deletion of `processed_events` rows).
- Bounds retry attempts.
- Distinguishes between "lost event" (re-emit makes sense) and
  "stuck for unclear reason" (query the gateway first).

## Decision: Keep Current Implementation, Document Trade-off

For this learning project, the current "re-emit with unmark" approach is
retained because:

1. The gateway is fake; double-execution has no real cost.
2. The simplicity demonstrates the Day 22 / Day 29 coordination concept
   clearly.
3. Implementing reconcile-by-query would require either a real gateway
   integration or a fake gateway query API, which is beyond today's scope.

When discussing this project, the honest answer to "is your reconciliation
production-ready?" is: "No, but I know exactly why, and here's what
would change." That answer demonstrates senior thinking better than
either silently shipping the simple version or over-engineering for a
hypothetical production deployment.

## Future Work

If this system were to be productionized, the priority order would be:

1. **Add gateway idempotency keys.** Send `paymentId` as the idempotency
   key on every gateway call. This is the single most important change
   for safety.
2. **Implement reconcile-by-query.** Replace the re-emit-and-unmark
   approach with direct gateway state queries. The current `unmark`
   call would only fire in the `NOT_FOUND` case.
3. **Add Redis distributed lock.** Day 32 of the roadmap. Prevents
   multi-instance duplicate work.
4. **Add bounded attempt counting and escalation.** Mark payments
   `FAILED` with `MAX_RECONCILIATION_ATTEMPTS_EXCEEDED` and alert
   on-call after N retries.
5. **Preserve audit trail.** Either separate event type for retries,
   `attempt_count` column on payment, or a `payment_attempts` audit
   table. Compliance requirement for real payment systems.

## References

- Day 22 design: see `docs/idempotency.md` for the original
  `processed_events` rationale.
- Day 23 design: the `markProcessing` PROCESSING → PROCESSING fix that
  makes the consumer resumable. This is what allows re-emitted events
  to be picked up.
- Day 24 design: see `docs/payment-state-machine.md`. The state machine
  blocks `POST /api/payments` for orders with existing payments but
  does NOT run inside the consumer.
- Day 26 design: the inbound gateway webhook is the push-mode
  counterpart to reconcile-by-query. Both serve to resolve UNKNOWN
  states.