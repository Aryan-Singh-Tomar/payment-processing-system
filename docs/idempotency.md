# Idempotency in This System

## What
Every `POST /api/payments` request must include an `idempotencyKey`.
The server guarantees that the same key produces the same payment,
regardless of how many times the request is retried.

## Why
The network is unreliable; clients retry. Without idempotency, retries
become duplicate charges — a customer-trust disaster in payments.

## How (the two-layer pattern)
1. **Postgres (source of truth):** unique constraint on
   `payments.idempotency_key`. Atomic with the payment row itself.
2. **Redis (speed layer):** cached `key → response` with 24h TTL.
   Serves the read path; never authoritative.

## Failure Modes Handled
- **Sequential retry:** look up key, return original result.
- **Concurrent duplicates:** unique constraint forces one winner;
  the loser refetches and returns the winning row.
- **Partial failure:** retry sees in-flight payment in PENDING.
  We return the in-flight state; reconciliation closes the loop.

## Replay Status Code
We return `201 Created` on replays for byte-identical responses,
matching Stripe's convention. A future `X-Idempotent-Replay: true`
header may be added for observability.

## Idempotency Window
The `payments.idempotency_key` row is permanent (the table never
expires rows). The Redis cache has a 24h TTL — typical for
synchronous payment APIs.

## Why Not Exactly-Once?
True exactly-once delivery is impossible across unreliable networks.
We use at-least-once delivery + idempotent processing, which
produces "effectively-once" outcomes — the standard term in
distributed systems literature.