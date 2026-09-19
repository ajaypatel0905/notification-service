# CLAUDE.md — notification-service

Spring Boot 3.5 / Java 21 / PostgreSQL. Multi-tenant notification service built as an SDE take-home.
This file is the standing brief for AI-assisted development sessions on this repo.

## Product requirement (verbatim scope)
Multi-tenant notification service supporting email, SMS, push and in-app channels; tenant-defined
templates with variable substitution; scheduled and immediate sends; per-tenant rate limiting;
retries with backoff on transient failures; delivery tracking. Dispatch high volumes concurrently
using bounded worker pools, enforce per-tenant fairness and rate limits under load, avoid duplicate
deliveries on retry. Persist delivery state transitions and retry attempts with an audit trail.
Roles: platform admin (tenants, global limits) and tenant admin (templates, channel config, reports).

Out of scope: UI, deployment/containers/CI, distributed systems, OAuth/SSO/MFA, production observability.

## Architecture in one paragraph
Single Spring Boot process, modular-monolith packages under `com.ajaypatel.notify`. PostgreSQL is
the queue: `notifications` rows are claimed with `FOR UPDATE SKIP LOCKED` by a poller that walks
tenants round-robin (fair share) and asks a per-tenant token bucket how many it may claim (rate
limit), then hands work to bounded per-channel `ThreadPoolExecutor`s. Workers call a
`ChannelProvider`, record a `delivery_attempts` row and a `notification_events` audit row, and
either mark SENT/DELIVERED, schedule a retry (exponential backoff + full jitter) or mark FAILED.
Leases with expiry make a crashed worker's rows reclaimable; provider calls carry the notification
id as an idempotency key so a re-send after a crash is deduplicated on the provider side.

## Conventions
- Java 21, records for DTOs, Lombok only on JPA entities. No field injection; constructor injection.
- Package by feature: `tenant`, `security`, `template`, `channel`, `notification`, `dispatch`,
  `ratelimit`, `provider`, `report`, `inbox`, `common`.
- All tenant-scoped queries take `tenantId` explicitly. Never trust a client-supplied tenant id;
  it always comes from the authenticated API key.
- Errors are RFC 7807 `ProblemDetail`. Domain exceptions live in `common.error`.
- State transitions go through `NotificationStateMachine`; never set `status` directly elsewhere.
- Flyway migrations in `src/main/resources/db/migration`, never edit an applied migration.
- Tests: unit tests `*Test`, integration tests `*IT` on embedded Postgres (zonky), Awaitility for async.
- Comments are sparse; explain the why, not the what.
- Commits: small, plain descriptions of the change. No quality adjectives.

## Commands
- `./mvnw test` — full suite (starts embedded Postgres, no Docker needed)
- `./mvnw spring-boot:run -Dspring-boot.run.profiles=local` — run with embedded Postgres + seeded demo data
- `scripts/demo.sh` — drives the API end to end with curl

## Working agreement for AI sessions
1. Read `docs/ai-workflow/PLAN.md` for the current milestone before touching code.
2. Write or update the ADR in `docs/adr/` when a design decision changes.
3. Every feature lands with its tests in the same commit.
4. Append a short entry to `docs/ai-workflow/SESSION_LOG.md` at the end of each session.
