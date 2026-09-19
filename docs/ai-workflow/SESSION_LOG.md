# Session log

Chronological notes of AI-assisted sessions. Prompts that shaped the design are quoted; the rest
is summarised. Raw agent configuration lives in `CLAUDE.md` and `.claude/skills/`.

## 2026-09-19 — session 1: selection and skeleton
- Prompt: "select one of four attached assignments ... due Sep 19th EOD". Read all four PDFs,
  compared scope vs 48h, recommended the notification service (see DECISIONS.md).
- Prompt: "start with the development, design in such a way you are applying for sde3 and
  complete e2e". Set up repo, CLAUDE.md, plan, milestones.
- Design settled before code: Postgres-as-queue with SKIP LOCKED, round-robin claimer, token bucket at
  claim time, bounded per-channel pools, three-phase worker, leases + reaper, provider idempotency key,
  render-at-accept with template versions, API-key RBAC. Written up as ADR 0001–0006.
- Implementation order followed PLAN.md milestones 1–7 in one pass; compiled clean first time, booted on
  the embedded Postgres, and every flow worked in a curl smoke test (the only "failure" was zsh `echo`
  expanding `\n` in my shell script, now noted in the smoke-run skill).
- Tests: wrote the support layer (embedded Postgres config, `AbstractIntegrationTest`, `ApiClient`), the
  unit tests and `NotificationLifecycleIT` by hand, then handed three parallel agents a written spec per
  suite (exact assertions, tenants, timings) with the rule "test code only; report main-code bugs, do not
  fix them". They were told to use separate Maven build directories (`-DbuildDir`) to avoid colliding.
- Two findings came back from the agents and were acted on:
  1. `IdempotencyIT` (25 concurrent posts, one key) returned 19×500: the unique-constraint loser re-read
     inside its own aborted PostgreSQL transaction. Fixed by running the insert in its own
     `TransactionTemplate` boundary and re-reading after it ends.
  2. `ConcurrentClaimIT` showed that concurrent claimers can over-subscribe a pool; a rejected row used to
     wait for lease expiry. Now `DispatchPoller` releases the lease immediately with a `REQUEUED` event.
  Two spec corrections were also made by the agents (token-bucket window bound in the first second; the
  platform max rate of 1000/s), both correct.
- Final: 85 tests green (17 unit, 68 integration), ~31 s of test time on embedded Postgres.
