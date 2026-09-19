# Build plan

Chosen assignment: Multi-tenant Notification Service (of four options). Rationale in
`docs/ai-workflow/DECISIONS.md`.

## Milestones (all complete as of 2026-09-19)
1. Skeleton: pom, CLAUDE.md, migrations for tenants / api keys / settings.
2. Security: API-key auth, PLATFORM_ADMIN vs TENANT_ADMIN, tenant scoping.
3. Tenant admin surface: channel configs, templates (versioned), render preview.
4. Notification intake: single + batch, idempotency key, scheduled vs immediate, cancel.
5. Dispatch engine: fair-share claim (SKIP LOCKED), per-tenant token bucket, bounded per-channel
   pools, retry/backoff, lease reaper, state machine + audit trail.
6. Providers: simulated email/sms/push with deterministic failure hooks, real in-app inbox,
   provider status callbacks.
7. Reports: per-tenant delivery summary, failures, admin dispatch stats.
8. Tests: unit (renderer, backoff, token bucket, state machine) + integration on embedded Postgres
   (happy path, idempotency, retry, permanent failure, rate limit, fairness, no-duplicate claim,
   scheduled, cancel, suspended tenant, RBAC, tenant isolation, callback, lease expiry, inbox).
9. Docs: README, ADRs, demo script, session log.
