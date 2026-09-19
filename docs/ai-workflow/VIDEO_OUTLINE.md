# Loom outline (target 9 minutes)

0:00 Problem framing (1 min)
- Four options; why the notification service: the hard requirements are mechanics that tests can prove.
- Scope I chose: 4 channels, versioned templates, schedule/cancel, idempotency, fairness, rate limits,
  bounded pools, retries, leases, receipts, audit trail, reports, two roles. Out: UI, brokers, OAuth.

1:00 Architecture walk (2.5 min) — README diagram on screen
- Postgres is the queue: one SKIP LOCKED claim statement. Why not a broker (two truths, out of scope).
- Fair share: round-robin over tenants with due work, 20 per tenant per cycle, per-channel pool room.
- Rate limit before claim: token bucket, tenant override else platform default, evicted on change.
- Worker: prepare(tx) → provider(no tx) → complete(tx, row lock). Transient vs permanent. Backoff + jitter.
- Leases + reaper + provider idempotency key = at-least-once, deduped where it matters.
- Render at accept, template versions, strict placeholders.

3:30 Live demo (2.5 min) — `scripts/demo.sh` against the local profile
- 401/403 matrix, idempotent replay 202→200, SMS that times out twice then sends (show attempts + events),
  hard bounce → FAILED, in-app inbox, schedule + cancel, globex flood at 5/s vs acme's 5 going straight
  through, report, dispatcher stats.

6:00 Tests (1.5 min)
- 17 unit + 68 integration on embedded Postgres, no Docker. Show ConcurrentClaimIT (6 pollers, exactly one
  attempt per row), FairnessIT (300 vs 10), RateLimitIT (window bound), LeaseReaperIT, IdempotencyIT (25 racers).
- Mention the race bug the idempotency test caught (aborted-transaction re-read) and the fix.

7:30 AI workflow (1 min)
- CLAUDE.md as the standing brief; plan and ADRs written before code; skills used; every feature with its
  tests in the same commit; three parallel agents wrote test suites against a spec I wrote; session log.

8:30 What I would do next (30 s)
- Shared-store token bucket for multi-instance; real SES/Twilio/FCM providers; recipient preferences and
  quiet hours; report rollups; metrics.
