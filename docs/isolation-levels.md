# Transaction Isolation in This System

## Default
PostgreSQL default: Read Committed.
Spring's @Transactional(isolation = DEFAULT) maps to this.
Every transaction in this codebase runs at Read Committed unless
explicitly overridden.

## Anomalies Allowed at Read Committed
- Non-repeatable reads (re-reading the same row may give different values)
- Phantom reads (re-running a range query may give different rows)
- Lost updates / serialization anomalies

We accept these in the general case and handle specific contention points
with one of three patterns:
1. Database constraint (e.g., partial unique index for "one SUCCESS per order")
2. Pessimistic lock (SELECT FOR UPDATE) for short critical sections
3. Optimistic lock (@Version) for low-contention update flows

## When to Escalate
Move a specific @Transactional to Repeatable Read when:
- You read multiple rows that must be consistent as a snapshot
- You read-then-decide-then-act and the decision must be stable across reads

Move to Serializable only when:
- The contention is rare (you can afford serialization failures + retry)
- Money or strictly-bounded resources are involved

## What This Project Uses
- Read Committed everywhere (default)
- Unique constraint on payments.idempotency_key for idempotency
- Partial unique constraint on (order_id) WHERE status='SUCCESS'
- Pessimistic and optimistic locks coming in Days 14-15

## Reference
- PostgreSQL: https://www.postgresql.org/docs/current/transaction-iso.html
- Spring: org.springframework.transaction.annotation.Isolation