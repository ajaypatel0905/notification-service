# ADR 0004: At-least-once dispatch, deduplicated with a provider idempotency key

## Context
A worker can crash after the vendor accepted the message but before the database recorded it. Exactly-once between
two systems without a shared transaction is impossible; the choice is which side absorbs the duplicate.

## Decision
Dispatch is at-least-once. Every provider call carries `idempotencyKey = notification id`, stable across retries and
across lease loss. Real vendors (SES, Twilio, FCM) honour such keys; the simulated providers do too and return the
original message id on a repeat. Inside the service:

- A row is `PROCESSING` with a lease. Only the lease holder may complete it; a result arriving after the lease was
  reaped is applied only if it is a success (the vendor already sent; recording it is the truthful state).
- The lease reaper returns expired `PROCESSING` rows to `QUEUED` without incrementing `attempt_count`, because a
  crash is not a delivery failure.
- `delivery_attempts (notification_id, attempt_no)` is unique, so the database refuses a duplicate attempt record.
- Vendor receipts (callbacks) are idempotent: a receipt for a terminal notification is acknowledged and ignored.

## Alternatives considered
- **Two-phase commit / XA with the vendor**: vendors do not participate in transactions.
- **At-most-once (mark SENT before calling the vendor)**: loses messages on crash. Wrong trade for notifications;
  a duplicate OTP is annoying, a missing one is an incident.

## Consequences
- Correctness depends on the provider honouring the idempotency key. The `ChannelProvider` contract states it.
- Tests: `ConcurrentClaimIT` proves no double-claim under concurrent pollers; `LeaseReaperIT` proves lease recovery.
