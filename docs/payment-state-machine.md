# Payment State Machine

## States

- PENDING — Payment row created via API. Not yet picked up for processing.
- PROCESSING — Kafka consumer is calling the payment gateway.
- SUCCESS — Gateway confirmed the charge succeeded. Terminal.
- FAILED — Gateway confirmed the charge failed. Terminal.
- UNKNOWN — Gateway call timed out or response was ambiguous. Not terminal. Webhook or reconciliation will resolve it later.

## Allowed Transitions

| From | To | Trigger |
|---|---|---|
| PENDING | PROCESSING | Kafka consumer begins processing |
| PROCESSING | SUCCESS | Gateway returns success |
| PROCESSING | FAILED | Gateway returns explicit failure |
| PROCESSING | UNKNOWN | Gateway timeout or network error |
| UNKNOWN | SUCCESS | Webhook or reconciliation confirms success |
| UNKNOWN | FAILED | Webhook or reconciliation confirms failure |

## Forbidden Transitions

- SUCCESS → anything: A confirmed success is irreversible.
- FAILED → anything: A confirmed failure is irreversible.
- PENDING → SUCCESS/FAILED/UNKNOWN directly: Must go through PROCESSING.
- PROCESSING → PENDING: Invalid backward transition.

## Invariants

1. An order can have at most one payment in SUCCESS state.
2. Idempotency keys are globally unique.
3. UNKNOWN is the safe status when the system is unsure.
4. Never guess SUCCESS or FAILED if the gateway response is unclear.

## Why UNKNOWN Exists

A payment gateway call can timeout after the customer was already charged.

If we mark it FAILED, the customer may be charged but the order remains unpaid.

If we mark it SUCCESS without confirmation, we may fulfill an order without confirmed payment.

UNKNOWN means the system is honest: we do not know yet, and webhook or reconciliation must resolve it.

## Transitions

          ┌─────────┐
          │ PENDING │  ◄─── (entry point — created by POST /api/payments)
          └────┬────┘
               │ markProcessing (consumer claims event)
               ▼
        ┌────────────┐
        │ PROCESSING │
        └─────┬──────┘
              │ recordResult (gateway returned)
    ┌─────────┼─────────┐
    ▼         ▼         ▼
┌───────┐ ┌───────┐ ┌───────┐
│SUCCESS│ │FAILED │ │UNKNOWN│
└───────┘ └───────┘ └───────┘
(terminal — no further transitions)

No transitions out of terminal states. Once a payment is in SUCCESS, FAILED, or
UNKNOWN, no business logic can change it.

## Order-Level Rule For New Payments

When a `POST /api/payments` arrives, we check the order's existing payments:

- If any payment is in PENDING or PROCESSING → reject (one in flight).
- If any payment is in SUCCESS → reject (order already paid).
- If all existing payments are in FAILED or UNKNOWN → allow (legitimate retry
  after failure).
- If no payments exist → allow (first attempt).

## Why UNKNOWN Is Treated As Retry-Safe

UNKNOWN means "we don't know if the gateway succeeded or not." There are two
options:

1. Block retries until UNKNOWN is reconciled (safer, slower).
2. Allow retries; rely on `uniq_payments_order_success` partial unique index
   to prevent double-success (faster, but might produce a duplicate charge
   that gets recovered).

We choose option 2. The constraint at the schema level guarantees at most one
SUCCESS per order. If the original UNKNOWN charge eventually clears at the
gateway and our retry also clears, exactly one of them will become SUCCESS in
our database; the other gets recovered by `PaymentDuplicateHandler` with reason
DUPLICATE_ORDER_SUCCESS, and refund tracking proceeds normally.

This is the same belt-and-braces philosophy applied throughout the system:
fast-path application logic + immovable schema invariant.

## Defenses By Layer (Recap)

| Layer | What | Where |
|-------|------|-------|
| 1. State machine validator | "Order already has a payment" check | PaymentService.createPayment (Day 24) |
| 2. Idempotency unique constraint | Same idempotency key can't create two rows | payments.idempotency_key UNIQUE |
| 3. Partial unique index | At most one SUCCESS per order | uniq_payments_order_success |
| 4. Pessimistic / optimistic lock | Concurrent updates serialized | PaymentApprovalService / OptimisticPaymentApprovalService |
| 5. Duplicate-success recovery | Race-loser handled cleanly | PaymentDuplicateHandler |
| 6. Consumer-side event claim | Duplicate Kafka deliveries skipped | ProcessedEventService |

Each layer protects a different failure mode. The system stays correct even
when individual layers have bugs, because the next layer catches the violation.