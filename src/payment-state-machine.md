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