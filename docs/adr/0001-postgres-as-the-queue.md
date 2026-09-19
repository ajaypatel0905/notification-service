# ADR 0001: PostgreSQL is the queue

## Context
The service must buffer high volumes, dispatch them concurrently, survive a process crash without losing or
duplicating work, and keep a per-notification audit trail. Distributed systems and external brokers are out of
scope for the assignment, and a broker would add a second source of truth that has to be reconciled with the
database anyway (a notification row must exist before it is queued, and its state must be updated after).

## Decision
The `notifications` table is the queue. A row with `status = QUEUED` and `next_attempt_at <= now()` is claimable.
Claiming is an `UPDATE ... WHERE id IN (SELECT ... FOR UPDATE SKIP LOCKED LIMIT n) RETURNING id` that sets
`PROCESSING`, `leased_by` and `lease_expires_at` atomically. Retries are the same row with a future
`next_attempt_at`. Scheduled sends are the same row with `status = SCHEDULED` until a promoter flips them.

## Alternatives considered
- **In-memory queue (`BlockingQueue`)**: lost on crash; cannot enforce fairness across a restart; unbounded growth or
  intake blocking under load.
- **Redis / RabbitMQ / Kafka**: two systems to keep consistent, outbox pattern needed anyway, and forbidden by the
  "no distributed systems" scope.
- **Polling with plain `SELECT` + optimistic `UPDATE ... WHERE version = ?`**: correct but every concurrent claimer
  collides on the same head-of-queue rows and wastes round trips. `SKIP LOCKED` makes contention free.

## Consequences
- One transactional truth: accept, claim, attempt, and audit are all rows in one database.
- Throughput is bounded by Postgres row-lock throughput; for this scope (thousands/sec) that is ample. Partial
  indexes on `(tenant_id, status, next_attempt_at)` keep claims index-only.
- Multiple service instances would already work (SKIP LOCKED is instance-agnostic); only the in-memory token
  bucket would need to move to a shared store. See ADR 0003.
