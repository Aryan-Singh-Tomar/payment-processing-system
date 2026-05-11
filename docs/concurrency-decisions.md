# Concurrency Decisions: Payment Approval Flow

## The Problem

When a payment is created via `POST /api/payments`, multiple payments may exist
for the same order (legitimate retries, failed cards, etc.). The business rule
is: **at most one payment per order may reach `SUCCESS` status.**

The naive approval flow has a check-then-act race: under concurrent calls, two
transactions can both pass the "is there already a SUCCESS payment?" check, both
proceed to UPDATE, and a partial unique index on `(order_id) WHERE status='SUCCESS'`
catches the second one — surfacing as a `DataIntegrityViolationException` → 409.
The data is preserved, but the user experience is broken (the loser sees a vague
"conflict" message rather than a clean business rejection).

Week 3 implemented three approaches to fixing this race. Each is a real, working
endpoint in the codebase. This document explains what was built, the trade-offs
each carries, and which is recommended for this specific race vs other patterns.

## The Three Approaches Built

### 1. Pessimistic DB Lock — `POST /api/payments/{id}/approve`

Implementation: [`PaymentApprovalService`](../src/main/java/com/payment/paymentsystem/service/PaymentApprovalService.java)

Acquires `SELECT ... FOR UPDATE` on the order row at the start of the transaction.
Concurrent approvals for the same order serialize on this lock — the second
transaction blocks until the first commits or rolls back, then re-reads state
that includes the first commit and returns a clean 422.

The lock is on the **order row**, not the payment row, because the protected
invariant ("at most one SUCCESS per order") is the order's property. Locking
individual payments doesn't coordinate across distinct payments belonging to
the same order.

Released automatically when the transaction commits or rolls back.

### 2. Optimistic Locking with @Version — `POST /api/payments/{id}/approve-optimistic`

Implementation: [`OptimisticPaymentApprovalService`](../src/main/java/com/payment/paymentsystem/service/OptimisticPaymentApprovalService.java)

Relies on the `Payment.@Version` column. Every UPDATE includes
`WHERE version = ?`; if the version doesn't match (another transaction beat us),
Hibernate throws `OptimisticLockException`. The service catches it, sleeps briefly,
and retries — up to 3 attempts — each attempt running in a fresh transaction
via `REQUIRES_NEW` on a separate bean (`PaymentApprovalTransaction`).

**Honest finding for this specific race:** `@Version` never actually fires here.
Both threads modify different payment rows, so neither has a version conflict on
its own row. The conflict is on the order-level invariant, which `@Version`
doesn't protect. The partial unique index is what actually catches the conflict;
we translate `DataIntegrityViolationException` to a clean 422.

So for this race, the optimistic implementation falls back on constraint-based
detection — it's not worse than pessimistic, but the `@Version` mechanism isn't
doing the work it would in a same-row conflict scenario.

### 3. Database Constraint Only (the baseline)

The partial unique index `uniq_payments_order_success ON (order_id) WHERE status='SUCCESS'`
is permanent and enforced by Postgres. Without any application-level coordination,
it catches duplicate-success attempts at write time. The losing transaction's
UPDATE fails with a unique-violation error, which Spring wraps as
`DataIntegrityViolationException`, which the global handler converts to 409.

This is what existed before Day 14. Data integrity was preserved, but the user
experience (409 with a vague message) was poor. We kept the constraint as
defense in depth even after adding application-level locking.

## Decision Matrix

| Dimension                  | Pessimistic DB Lock        | Optimistic `@Version`         | Constraint Only          |
|----------------------------|----------------------------|--------------------------------|--------------------------|
| **Mechanism**              | `SELECT ... FOR UPDATE`    | `WHERE version=?` + retry      | `UNIQUE INDEX`           |
| **Blocks on contention?**  | Yes (second waits)         | No (fails, retries)            | No (second fails fast)   |
| **Auto-released?**         | Yes (on transaction end)   | N/A (no acquired resource)     | N/A                      |
| **What it protects**       | Anything inside the txn    | Single-row UPDATE conflicts    | Schema-level invariant   |
| **Cost on success path**   | 1 extra row read           | 1 extra column in UPDATE       | Zero                     |
| **Cost on contention**     | Second tx waits then proceeds | Retry work (DB calls × N)   | Second tx 500/409 + roll back |
| **User-facing response**   | Clean 422 on loser         | Clean 422 on loser             | Constraint-derived 409   |
| **Application coupling**   | High (explicit lock)       | Medium (entity needs version)  | Low (just the constraint) |

## What We Chose For THIS Race

**Pessimistic DB lock on the order row** is the recommended approach for the
payment approval flow.

Reasoning:

- The conflict is on the order, and the order lives in the DB. The DB has perfect
  knowledge of who holds what lock on which row. There's no impedance mismatch.
- The lock is released automatically on transaction end. No try/finally needed,
  no TTL to tune, no failure mode where a hung process holds the lock indefinitely
  beyond the transaction.
- The 422 message is clean and meaningful: "Order already has a SUCCESS payment."
  The loser knows exactly what happened and can communicate it to a user.
- Throughput cost is negligible for this workload. Same-order contention is
  vanishingly rare in real payment systems — orders are independent units, and
  two simultaneous approvals on one order is an edge case, not the load profile.

Optimistic `@Version` retry is kept as a working alternative, but it doesn't shine
here. The constraint catches the actual conflict, and the retry loop adds code
without adding protection. The implementation exists in the codebase because the
mechanism is useful for *other* flows (see "Where each approach shines" below).

The partial unique index stays in the schema as the guardrail of last resort.
Even with the pessimistic lock in place, the constraint guarantees data integrity
under any failure mode (e.g., the lock failing to acquire due to a bug). The
constraint is rarely exercised under normal operation because the application
coordinates explicitly — but it's there when needed.

## Where Each Approach Shines, Generally

### Pessimistic locking is the right tool when:

- Contention is low to moderate (most rows rarely contended at the same time).
- The locked row is small and the critical section is short.
- You want a clean, predictable serialization model with automatic cleanup.
- The conflict is on a single resource your DB can natively represent.

Examples: incrementing a counter, transferring money between accounts,
approving a state transition on a tracked entity.

Avoid pessimistic locking when:

- Contention on a single row is extreme (hot rows in high-throughput systems).
  Serialization throughput is limited to ~1 / (lock duration), which can become
  a bottleneck.
- The critical section involves slow external work (network calls). Holding a
  DB lock during a 3-second gateway call blocks every other transaction on that
  row for 3 seconds.

### Optimistic locking with @Version is the right tool when:

- Conflicts are rare (read-heavy workloads with occasional updates).
- The conflict is on a single row's state (vs cross-row invariants).
- Clients can tolerate occasional retry latency.
- You want maximum concurrency in the happy path.

Examples: editing document content, updating a user profile, refreshing a
cached value's status field.

Avoid optimistic locking when:

- Contention is high — retry rates cascade into thundering herd patterns.
- Conflicts span multiple rows (this race is the example — `@Version` on a
  payment row doesn't help with the order-level invariant).
- The work being retried is expensive (each retry pays full read/check cost).

### Constraint-only is the right approach when:

- The constraint is fundamental to data integrity and must always be enforced.
- Application code can be trusted to coordinate normally; the constraint is
  insurance.
- The 409/business-error response is acceptable for the rare collision case.

Examples: unique usernames at signup, unique payment idempotency keys, foreign
key relationships.

**The constraint is always present**, regardless of which application-level
mechanism you also use. Constraints are belt; locks/versions are braces.

## A Note on Redis Distributed Locks

We did not implement a Redis distributed lock for this race. The reason: the
conflict is on a database row, and database row locks coordinate transactions
on that row perfectly. Adding Redis to the coordination path would have been
a regression — more moving parts, weaker guarantees, more failure modes —
without solving the problem any better than `SELECT FOR UPDATE`.

Redis distributed locks belong in a different category of problems: coordinating
work that doesn't live in the database, or coordinating across multiple service
instances when the resource being coordinated isn't a DB row. The canonical
example: a scheduled job that should run on exactly one service instance at a
time. There's no row in the DB representing "the act of running the scheduler" —
but every instance needs to agree on who runs it.

We will implement Redis distributed locks in Week 6 (Day 32) for exactly this
use case: the reconciliation scheduler. The decision to defer was deliberate.
Building the tool before the problem makes the tool feel arbitrary; building
the problem first makes the tool feel necessary.

## The Mental Framework

The one-line takeaway from Week 3, worth committing to memory:

> **The lock target is the resource whose invariant you're protecting,
> not the row you're writing.**

When facing a new concurrency question, ask three things:

1. **What is the conflict on?** A row's state (single-row), a cross-row business
   rule (the invariant lives on a parent or aggregate), an external resource
   (no DB representation)?

2. **What guarantees does each tool give for that scope?**
   Pessimistic locks coordinate transactions on the locked row.
   Optimistic versioning detects same-row conflicts on commit.
   Constraints detect at write time at the schema level.
   Redis distributed locks coordinate any clients sharing the same Redis instance.

3. **What's the cost of each tool's failure mode?**
   Blocked transactions (pessimistic), retry storms (optimistic),
   ugly 409 errors (constraint-only), TTL-expired locks (Redis).

Match scope to tool. There is no universal "best" mechanism.

## What We Built That We're Proud Of

Two technical details worth remembering:

**The lock target reasoning.** Locking the order, not the payment, was the
critical design decision. Most engineers would lock "the thing being modified"
(the payment); the right answer is "the resource the invariant lives on" (the
order). This is the senior insight from Week 3.

**The `REQUIRES_NEW` retry pattern.** Optimistic locking's retry logic must live
in a non-transactional outer method, calling into a `@Transactional(REQUIRES_NEW)`
inner bean. Without this split, retries run inside a poisoned transaction and
fail with `UnexpectedRollbackException`. This is the same pattern from Day 10's
idempotency work — Spring's proxy-based transaction management requires
crossing bean boundaries to open fresh transactions.

Both patterns generalize beyond payments. They'll come back in Week 6
(reconciliation scheduler retries) and any future flow that needs coordinated
recovery.

## References

- [`PaymentApprovalService`](../src/main/java/com/payment/paymentsystem/service/PaymentApprovalService.java) — pessimistic DB lock implementation
- [`OptimisticPaymentApprovalService`](../src/main/java/com/payment/paymentsystem/service/OptimisticPaymentApprovalService.java) — optimistic `@Version` retry implementation
- [`OrderRepository.findByIdForUpdate`](../src/main/java/com/payment/paymentsystem/repository/OrderRepository.java) — the lock-acquiring repository method
- `V2__create_payments_table.sql` — defines the partial unique index that serves as the schema-level guardrail
- Day 12 isolation-levels theory: `docs/isolation-levels.md`