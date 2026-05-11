A production-style payment processing backend built to deeply explore the
hardest problems in real payment systems: idempotency, race conditions,
transaction isolation, distributed coordination, async event processing,
webhooks, and reconciliation.

Built in deliberate daily increments with documented design decisions,
failure-mode analysis, and trade-off discussions at each step. The repo's
git history is part of the artifact — every commit produces something
demoable, every week ends with something shippable.

**Status:** Week 3 of 8 complete (Days 1–17). Synchronous core API,
idempotency, and concurrency control are production-grade. Week 4
introduces Kafka and asynchronous processing.

---

## Tech Stack

- **Java 17, Spring Boot 3.3.x, Maven**
- **PostgreSQL 16** — source of truth, with Flyway migrations
- **Redis 7** — read-side cache for idempotency, future home for distributed locks
- **Kafka 3 (KRaft mode)** — message broker (wiring begins in Week 4)
- **Docker Compose** — entire infrastructure reproducible with one command
- **springdoc-openapi** — live Swagger UI at `/swagger-ui.html`

---

## Architecture (Target)
Client → API → Redis (cache) → PostgreSQL → Kafka → Consumer → Gateway → DB → Redis → Webhook → Reconciliation

PostgreSQL is the source of truth. Redis is a speed layer and coordination
primitive — never authoritative. Kafka decouples API latency from gateway
processing. Webhooks correct state asynchronously. A reconciliation scheduler
catches anything that fell through the cracks.

**Currently implemented:** Client → API → Redis → PostgreSQL (synchronous).
The async event pipeline (Kafka → Consumer → Gateway → Webhook → Reconciliation)
is the focus of Weeks 4–6.

---

## Quick Start

```bash
git clone <repo>
cd payment-system
docker compose up -d
mvn spring-boot:run
```

Then:
- **Swagger UI:** http://localhost:8080/swagger-ui.html
- **Health check:** http://localhost:8080/api/health
- **OpenAPI spec:** http://localhost:8080/v3/api-docs

---

## What's Built So Far

### Week 1 — Foundation (Days 1–7) · [`v0.1.0`](#)

Bootstrap a production-shaped REST API on PostgreSQL with proper validation,
exception handling, and live documentation.

- Docker Compose with Postgres, Redis, Kafka (KRaft mode), healthchecks, persistent volumes
- Flyway migrations as the single source of schema truth (`ddl-auto=validate`)
- Money-safe schema design: `NUMERIC(19,4)`, `TIMESTAMPTZ`, partial unique indexes
- `Payment` and `Order` entities with `@Version` (for upcoming optimistic locking)
- DTOs separated from JPA entities; Bean Validation at the boundary; business validation in the service
- Constructor injection throughout, no `@Autowired` field injection
- Global `@RestControllerAdvice` translating exceptions to clean `ErrorResponse` JSON
  with correct status codes (400, 404, 405, 409, 422, 500)
- springdoc-openapi auto-generated spec and Swagger UI from controllers and DTOs

### Week 2 — Idempotency (Days 8–11) · [`v0.2-idempotency`](#)

Make the payment endpoint safe to retry. The contract: same idempotency key,
same response, regardless of network failures or concurrent duplicates.

- Two-layer pattern: PostgreSQL unique constraint (correctness) + Redis cache (speed)
- Sequential retries: cache hit on the fast path; Postgres fallback on cache miss
- Concurrent duplicates: handled via `DataIntegrityViolationException` catch + refetch
- Intent-match check on replays: reused key with different payload is rejected as 422
- Graceful degradation: Redis failures log a warning and fall through to Postgres;
  the customer never sees a Redis-related error
- Cache populate ordering: PostgreSQL commits first, then Redis populates
- Documented in [`docs/idempotency.md`](docs/idempotency.md)

**Notable bug found and fixed:** `UnexpectedRollbackException` after catching
a constraint violation. Root cause: Spring marks the outer transaction
rollback-only when any inner JPA write fails, even after the exception is
caught. Fix: isolate the failing INSERT in its own `REQUIRES_NEW` transaction
via a separate Spring bean. This pattern recurs in Week 3.

### Week 3 — Concurrency (Days 12–17) · [`v0.3-concurrency`](#)

Deliberately build a race condition, then fix it three different ways and
document when to pick each.

- **Day 12** — Transaction isolation levels theory ([`docs/isolation-levels.md`](docs/isolation-levels.md))
- **Day 13** — Built a race condition into `PaymentApprovalService` and watched
  it produce 200 + 409 under concurrent load. Two transactions both pass the
  "is there a SUCCESS for this order?" check, both UPDATE, partial unique
  index catches one. Constraint preserves data integrity; UX is broken.
- **Day 14** — Fix with pessimistic locking (`SELECT ... FOR UPDATE` on the
  order row). The lock target reasoning: lock the resource whose invariant
  you're protecting, not the row you're writing. Two threads concurrent on
  the same order serialize; loser gets clean 422 instead of 409.
- **Day 15** — Same fix with optimistic locking via `@Version`. Honest finding:
  the version mechanism never fires for this race because both threads modify
  different rows. The partial unique index still does the work. Optimistic
  locking falls back on constraint-based detection. Implementation is in
  the codebase as the right tool for future same-row conflict scenarios.
- **Day 17** — Three-way decision matrix ([`docs/concurrency-decisions.md`](docs/concurrency-decisions.md))
  covering pessimistic vs optimistic vs constraint-only, with explicit
  reasoning for the chosen approach.

Three endpoints in the codebase demonstrating each approach:
- `POST /api/payments/{id}/approve` — pessimistic DB lock (recommended for this race)
- `POST /api/payments/{id}/approve-optimistic` — optimistic `@Version` retry

The Redis distributed lock approach is deferred to Week 6 (Day 32) where it
fits the reconciliation scheduler use case naturally — there's no DB row
representing "the scheduler running on this instance," and a Redis lock is
the right primitive for that coordination.

---

## What's Coming

### Week 4 — Kafka & Async Processing (Days 18–23)

The API stops blocking on payment processing.

- Kafka producer publishes `PaymentRequestedEvent` on creation
- Consumer transitions PENDING → PROCESSING via a fake payment gateway client
- `POST /api/payments` returns `202 Accepted` (was `201 Created`)
- Consumer-side idempotency via a `processed_messages` table + Redis fast-path
- Retry handling with exponential backoff
- Dead Letter Topic for permanent failures

### Week 5 — Webhooks & State Machine (Days 24–28)

External state corrections handled cleanly.

- Formalize the payment state machine validator
- Webhook endpoint with HMAC signature verification
- Duplicate webhook handling
- State correction: UNKNOWN → SUCCESS via webhook
- Testcontainers integration test suite (Postgres + Redis + Kafka in tests)
- End-to-end scenarios: happy path, timeout-then-webhook, timeout-then-reconciliation

### Week 6 — Reconciliation (Days 29–33)

The safety net for everything else.

- `@Scheduled` job querying stuck PROCESSING and UNKNOWN payments
- Reconciliation audit endpoint
- Redis distributed lock to ensure exactly one instance runs the scheduler at a time
- Stress testing under simulated failure scenarios

### Week 7 — Observability (Days 34–38)

Make the system debuggable.

- Structured logging with SLF4J + MDC correlation IDs
- Request/response filter with sensitive-field masking
- Correlation ID propagation through Kafka message headers
- Spring Boot Actuator with custom health indicators
- Swagger polish

### Week 8 — Docker, README & Showcase (Days 39–42)

The final polish.

- Multi-stage Dockerfile for the application
- Full `docker-compose.yml` with health checks and seed data
- Architecture diagrams and demo script
- Final tag `v1.0.0`

---

## Design Decisions

Architectural reasoning is documented separately. Each file covers a specific
concern with the rationale behind the choice.

- **[Concurrency: three approaches to the same race](docs/concurrency-decisions.md)** —
  pessimistic vs optimistic vs constraint-only, with a decision matrix
- **[Transaction isolation levels](docs/isolation-levels.md)** —
  what Read Committed does and doesn't protect against
- **[Idempotency: PostgreSQL truth + Redis cache](docs/idempotency.md)** —
  two-layer pattern with graceful degradation
- **[Payment state machine](docs/payment-state-machine.md)** —
  valid transitions and the role of `UNKNOWN`

---

## Notable Engineering Lessons Surfaced

These are the moments where the project produced a real engineering lesson —
the kind you remember because you debugged it, not because you read it.

**`UnexpectedRollbackException` after catch (Day 10).** Catching a
`DataIntegrityViolationException` doesn't undo Spring's rollback-only flag
on the outer transaction. The catch block ran, the refetch returned
correctly, then Spring threw at commit time because the transaction was
already marked dead. Fix: isolate the failing INSERT in its own
`REQUIRES_NEW` transaction via a separate bean. The pattern: Spring's
`@Transactional` is enforced by proxies; crossing a bean boundary is what
opens a fresh transaction.

**`BigDecimal` deserialization in Redis (Day 11).** Jackson's polymorphic
type validator was configured to allow project packages, `java.util`, and
`java.time` — but not `java.math`. Cached `PaymentResponse` objects failed
to deserialize because `BigDecimal` is in `java.math`. The graceful-degradation
fallback worked perfectly (warn log, Postgres fallback), so the bug was
visible without being customer-impacting. The fix was one line in
`RedisConfig`. The lesson: graceful degradation pays off even when you
don't expect it to.

**Lock target reasoning (Day 14).** Two concurrent approvals were on
different payments under the same order. Locking individual payment rows
wouldn't coordinate (different threads lock different rows). The protected
invariant ("at most one SUCCESS per order") belongs to the order. Lock
the order. This generalizes: the lock target is the resource whose invariant
you're protecting, not the row you're writing.

**Optimistic locking doesn't shine for cross-row invariants (Day 15).** The
`@Version` mechanism protects single-row update conflicts. For a cross-row
business rule like the order-level invariant, two threads each successfully
update their own payment rows — neither version fails — and the partial
unique index is what actually catches the conflict. Useful tool, wrong
fit for this race. Documented honestly in the decision matrix.

---

## Code Quality Notes

- No `@Autowired` field injection. All dependencies via constructor.
- No service interfaces unless there are multiple implementations.
- No premature abstractions (no MapStruct for one mapper, no Lombok overuse).
- Bean Validation at the DTO boundary; business validation in services.
- `BigDecimal.compareTo()` for money equality, never `.equals()`.
- DTOs separate from entities; entities never leak through the API.
- Indexes added with the schema, not retrofitted later.
- Sensitive logging avoided (constraint names, raw exception messages stay internal).

---

## Project Structure

```text
src/main/java/com/payment/paymentsystem/
├── PaymentSystemApplication.java
├── config/
│   ├── RedisConfig.java
│   └── OpenApiConfig.java
├── controller/
│   ├── PaymentController.java
│   └── HealthController.java
├── dto/
│   ├── CreatePaymentRequest.java
│   ├── PaymentResponse.java
│   └── ErrorResponse.java
├── entity/
│   ├── Payment.java
│   ├── Order.java
│   ├── PaymentStatus.java
│   └── OrderStatus.java
├── exception/
│   ├── GlobalExceptionHandler.java
│   └── Domain exceptions
├── mapper/
│   └── PaymentMapper.java
├── repository/
│   ├── PaymentRepository.java
│   └── OrderRepository.java
└── service/
    ├── PaymentService.java
    ├── PaymentApprovalService.java
    ├── OptimisticPaymentApprovalService.java
    ├── IdempotencyCacheService.java
    ├── PaymentPersistenceService.java
    └── PaymentApprovalTransaction.java

src/main/resources/
├── application.yaml
└── db/migration/
    ├── V1__create_orders_and_payments.sql
    └── V2__add_indexes_and_constraints.sql

docs/
├── concurrency-decisions.md
├── idempotency.md
├── isolation-levels.md
└── payment-state-machine.md

---

## Why This Project Exists

This is a learning project, built to internalize the hard problems of payment
systems through deliberate practice rather than abstract reading. Each day
produces something demoable, each week ends with something shippable. The
commits are the artifact; the documentation is part of the deliverable.

The goal is not to build a payment processor that competes with Stripe. The
goal is to deeply understand the problems Stripe and Razorpay solve every
day — idempotency, concurrency, eventual consistency, reconciliation — and
to be able to discuss them with the depth that comes from having built them.

---

## License

MIT
