# Multi-tenant Notification Service

A single Spring Boot service that accepts notifications for many tenants, renders them from versioned
templates, and dispatches them across email, SMS, push and in-app channels with per-tenant fairness,
per-tenant rate limits, bounded worker pools, retries with exponential backoff, crash-safe leases and a
full audit trail. Built as a 48-hour SDE take-home; the brief is in
[`docs/assignment/`](docs/assignment/Multi-tenant%20Notification%20Service.pdf).

- **Stack:** Java 21, Spring Boot 3.5, Spring Data JPA, Spring Security, Flyway, PostgreSQL 16, springdoc OpenAPI.
- **Zero-setup run:** an embedded PostgreSQL boots inside the JVM for the `local` profile and for tests. No Docker.
- **Tests:** 17 unit + 68 integration tests on a real PostgreSQL, including concurrency, fairness, rate-limit,
  retry, lease-recovery and idempotency proofs. `./mvnw test` runs everything in under a minute once dependencies are cached.

```bash
export JAVA_HOME=$(/usr/libexec/java_home -v 21)      # or any JDK 21 on PATH
./mvnw test                                           # full suite, embedded Postgres
./mvnw spring-boot:run -Dspring-boot.run.profiles=local
scripts/demo.sh                                       # drives every flow with curl
open http://localhost:8080/swagger-ui.html            # X-API-Key: acme-tenant-key-000000
```

The `local` profile seeds two tenants: `acme` (50 msg/s) with key `acme-tenant-key-000000` and `globex`
(5 msg/s) with key `globex-tenant-key-0000`; the platform admin key is `local-admin-key`.

---

## 1. What it does

| Capability | Where | Proof |
|---|---|---|
| Tenants, suspend/activate, per-tenant rate overrides, global limits | `tenant/` | `AdminIT`, `TenantSuspensionIT` |
| API keys per role, hashed at rest, revocable; tenant scope from the key only | `security/` | `SecurityIT` |
| Channel configuration per tenant: provider, enabled flag, settings, max attempts | `channel/` | `ValidationIT` |
| Versioned templates with strict `{{variable}}` / `{{nested.path}}` substitution and render preview | `template/` | `TemplateIT`, `TemplateRendererTest` |
| Send / batch send / schedule / cancel, idempotency keys, priorities, metadata | `notification/` | `NotificationLifecycleIT`, `IdempotencyIT`, `SchedulingIT` |
| Fair-share claiming across tenants, per-tenant token-bucket rate limits | `dispatch/FairShareClaimer`, `ratelimit/` | `FairnessIT`, `RateLimitIT`, `TokenBucketTest` |
| Bounded per-channel worker pools | `dispatch/WorkerPools` | `FairnessIT` |
| Retries with exponential backoff + full jitter, transient vs permanent errors, attempt cap | `dispatch/DeliveryWorker` | `RetryIT`, `ExponentialBackoffPolicyTest` |
| Crash-safe leases, reaper, no duplicate processing under concurrent claimers | `dispatch/LeaseReaper` | `LeaseReaperIT`, `ConcurrentClaimIT` |
| Provider delivery receipts (webhooks), idempotent | `callback/` | `CallbackIT` |
| In-app channel with a real inbox (list, unread count, mark read) | `inbox/` | `NotificationLifecycleIT` |
| Audit trail: every state transition and every attempt persisted | `notification_events`, `delivery_attempts` | every IT reads them |
| Delivery reports: status mix, attempt outcomes, success rate, p50/p95 latency, top errors | `report/` | `ReportIT` |
| Dispatcher stats and pause/resume for operators | `dispatch/DispatchAdminController` | `AdminIT` |

## 2. Architecture

```
                 ┌─────────────────────────────── Spring Boot process ───────────────────────────────┐
  tenant admin   │  REST /api/v1/**            ┌──────────────┐        ┌────────────────────────────┐ │
  ─────────────► │  X-API-Key ─► ApiKeyFilter ─►│ Notification │─render─►│ notifications (QUEUED)     │ │
  platform admin │  RBAC: PLATFORM_ADMIN /      │   Service    │        │ notification_events        │ │
  ─────────────► │        TENANT_ADMIN          └──────────────┘        └────────────┬───────────────┘ │
                 │                                                                   │ FOR UPDATE      │
                 │   every 200 ms ┌──────────────────┐   per tenant: min(batch,       │ SKIP LOCKED     │
                 │   ───────────► │ FairShareClaimer │   tokens, pool room)  ◄────────┘                 │
                 │                │  round-robin over│──debit──► TenantRateLimiter (token bucket/tenant) │
                 │                │  tenants w/ work │                                                  │
                 │                └────────┬─────────┘                                                  │
                 │              PROCESSING + lease                                                       │
                 │        ┌────────────────┼──────────────────┬──────────────────┐                      │
                 │   EMAIL pool (8)   SMS pool (8)       PUSH pool (8)      IN_APP pool (4)             │
                 │        │                │                  │                  │                      │
                 │   DeliveryWorker: prepare (tx) ─► provider.send (no tx) ─► complete (tx, row lock)   │
                 │        │   SUCCESS → SENT/DELIVERED   TRANSIENT → backoff, QUEUED   PERMANENT → FAILED│
                 │        ▼                                                                             │
                 │   delivery_attempts + notification_events                                            │
                 │                                                                                      │
                 │   SchedulePromoter (SCHEDULED→QUEUED when due)   LeaseReaper (expired PROCESSING→QUEUED)│
                 │   /api/v1/callbacks/{provider} ◄── vendor receipts (SENT→DELIVERED/FAILED)           │
                 └──────────────────────────────────────────────────────────────────────────────────────┘
```

### The queue is PostgreSQL ([ADR 0001](docs/adr/0001-postgres-as-the-queue.md))
A notification row *is* the queue entry. Claiming is one statement:

```sql
UPDATE notifications SET status='PROCESSING', leased_by=:worker, lease_expires_at=:until
WHERE id IN (SELECT id FROM notifications
             WHERE tenant_id=:t AND channel=:c AND status='QUEUED' AND next_attempt_at <= now()
             ORDER BY priority DESC, next_attempt_at LIMIT :n FOR UPDATE SKIP LOCKED)
RETURNING id
```

`SKIP LOCKED` means any number of claimers can run at once and never block on or double-claim a row.
Accept, claim, attempt and audit are all rows in one transactional store; there is no second system to
reconcile. Partial indexes on `(tenant_id, status, next_attempt_at, priority)` keep the claim index-only.

### Fairness ([ADR 0002](docs/adr/0002-fair-share-claiming.md))
Each cycle the claimer lists tenants with due work, rotates the list by a moving cursor, and takes at most
`claim-batch-per-tenant` (20) rows per tenant. A tenant with 300 queued emails and a tenant with 10 both get
served in the first cycle; `FairnessIT` asserts the small tenant finishes before the large tenant reaches
its 20th percentile. Priority orders a tenant's *own* work; it never buys priority over another tenant.

### Rate limits ([ADR 0003](docs/adr/0003-rate-limit-before-claim.md))
One token bucket per tenant (tenant override, else platform default), consulted *before* claiming: the claimer
takes at most the tokens available, then debits exactly what it took. Nothing is claimed that cannot be
sent now, so there is no claim-then-throw-back churn. Over the limit, the queue grows; nothing is dropped.
Changing a tenant's limit evicts its bucket, so it applies on the next cycle without a restart.

### Bounded pools
One `ThreadPoolExecutor` per channel (threads + bounded queue). Claims are capped by each pool's free
capacity, so a slow SMS vendor cannot fill the email pool, and back-pressure is exact rather than
exception-driven. If concurrent claimers still over-subscribe a pool, the rejected row is returned to
`QUEUED` immediately with a `REQUEUED` audit event.

### Retries and failure classes
Providers throw `TransientDeliveryException` (timeouts, 5xx, throttling) or `PermanentDeliveryException`
(hard bounce, invalid recipient). Transient failures schedule a retry at
`random(cap/10, cap)` where `cap = min(maxBackoff, initial × 2^(attempt−1))` (full jitter, so a burst that
failed together does not retry together). `max_attempts` comes from the channel config or the platform
default. Permanent failures fail on the first attempt. Every attempt is a `delivery_attempts` row with
outcome, error code, provider message id, worker and timing.

### Exactly-once semantics, honestly ([ADR 0004](docs/adr/0004-at-least-once-with-provider-idempotency.md))
Dispatch is at-least-once. Every provider call carries the notification id as an idempotency key so a
re-send after a crash is deduplicated on the provider side, which is how SES, Twilio and FCM behave.
Inside the service: a row is `PROCESSING` under a lease; only the lease holder may complete it; the reaper
returns expired leases to `QUEUED` without counting an attempt; `(notification_id, attempt_no)` is unique.
`ConcurrentClaimIT` runs six pollers against 150 rows and asserts exactly 150 attempts, all
`attempt_count = 1`, 150 `SENT` events.

### Templates ([ADR 0005](docs/adr/0005-render-at-accept-time.md))
Rendering happens at accept time and is strict: a missing placeholder is a 400 to the caller, never
`{{name}}` in a customer's inbox. The rendered text plus `template_version` is stored, so what was accepted
is exactly what is sent and every message traces to the exact template text. Content edits create a new
immutable version; `template_versions` keeps history.

### Security ([ADR 0006](docs/adr/0006-api-key-auth.md))
`X-API-Key`. Keys are 192-bit random, shown once, stored as SHA-256. A key carries a role and (for tenant
admins) a tenant id; every tenant-scoped query uses the id from the key, never from the request. Admin
routes need `PLATFORM_ADMIN`, everything else `TENANT_ADMIN`. Vendor callbacks use a shared secret compared
in constant time. Errors are RFC 7807 `application/problem+json`.

## 3. Data model

```
tenants ─┬─< api_keys                 (role, key_hash, key_prefix, revoked_at)
         ├─< channel_configs          (channel, enabled, provider, settings jsonb, max_attempts)
         ├─< templates ─< template_versions
         └─< notifications ─┬─< delivery_attempts   (attempt_no, outcome, provider_message_id, error, timing)
                            ├─< notification_events  (event_type, from_status, to_status, actor, detail)
                            └── inbox_messages       (IN_APP channel only)
platform_settings                     (default rate, burst, max attempts, max rate cap)
```

Notification state machine (enforced by `NotificationStateMachine`, every transition audited):

```
SCHEDULED ─► QUEUED ─► PROCESSING ─► SENT ─► DELIVERED
    │          │           │  ▲        └───► FAILED
    │          │           │  │ retry / lease expired / pool saturated
    │          │           └──┴──────────► FAILED (permanent, or attempts exhausted)
    └──────────┴─► CANCELLED           PROCESSING ─► DELIVERED (in-app, synchronous)
```

## 4. API

Full OpenAPI at `/v3/api-docs`, Swagger UI at `/swagger-ui.html`.

**Platform admin** (`X-API-Key: <admin key>`)

| Method | Path | Purpose |
|---|---|---|
| POST/GET | `/api/v1/admin/tenants` | create / list tenants |
| GET/PATCH | `/api/v1/admin/tenants/{id}` | read / update name and rate overrides |
| POST | `/api/v1/admin/tenants/{id}/suspend` · `/activate` | pause / resume a tenant's dispatch |
| POST/GET/DELETE | `/api/v1/admin/tenants/{id}/api-keys[/{keyId}]` | issue (shown once) / list / revoke |
| GET/PUT | `/api/v1/admin/settings` | global defaults and caps |
| GET | `/api/v1/admin/dispatch/stats` | pools, backlog by tenant/channel, limiter state |
| POST | `/api/v1/admin/dispatch/pause` · `/resume` | operator control |

**Tenant admin** (`X-API-Key: <tenant key>`)

| Method | Path | Purpose |
|---|---|---|
| GET/PUT | `/api/v1/channels[/{channel}]` · `GET /providers` | channel config, available providers |
| POST/GET | `/api/v1/templates` | create / list |
| GET/PUT/DELETE | `/api/v1/templates/{code}/{channel}` | read / new version / deactivate |
| GET | `/api/v1/templates/{code}/{channel}/versions` | history |
| POST | `/api/v1/templates/{code}/{channel}/render` | preview with variables |
| POST | `/api/v1/notifications` | send or schedule (202; 200 on idempotent replay) |
| POST | `/api/v1/notifications/batch` | up to 1000, each accepted independently |
| GET | `/api/v1/notifications?status&channel&recipient&idempotencyKey&from&to&page&size` | search |
| GET | `/api/v1/notifications/{id}` | notification + attempts + audit events |
| POST | `/api/v1/notifications/{id}/cancel` | cancel SCHEDULED/QUEUED |
| GET | `/api/v1/notifications/status-counts` | quick health of the tenant's traffic |
| GET | `/api/v1/reports/deliveries?from&to&channel` | outcomes, success rate, latency p50/p95, top errors |
| GET | `/api/v1/inbox/{recipient}?unreadOnly` · `/unread-count` · `POST /messages/{id}/read` | in-app inbox |

**Vendors** (`X-Callback-Secret`)

| POST | `/api/v1/callbacks/{provider}` | `{providerMessageId, status: DELIVERED\|FAILED, reason}`; idempotent |

Send request (either `templateCode`+`variables` or `subject`/`body`):

```json
{ "channel": "EMAIL", "recipient": "jane@example.com",
  "templateCode": "welcome", "variables": { "company": "Acme", "user": { "name": "Jane" }, "plan": "Pro" },
  "idempotencyKey": "welcome-jane-2026-09", "scheduledAt": "2026-09-20T09:00:00Z", "priority": 5,
  "metadata": { "campaign": "onboarding" } }
```

### Steering the simulated providers
The email/SMS/push providers are simulations with a vendor-like contract, steerable per recipient so demos
and tests are deterministic: `+transient<N>` fails N times then succeeds, `+bounce` fails permanently,
`+slow<ms>` adds latency. Random failure rates are configurable (`notify.providers.simulated.*`); the `local`
profile injects 10% transient failures so retries are visible. Simulated vendors also emit an asynchronous
"delivered" receipt through the same code path as the HTTP callback. The IN_APP channel is real.

## 5. Configuration

| Property | Default | Meaning |
|---|---|---|
| `notify.security.bootstrap-admin-key` | `NOTIFY_ADMIN_KEY` | platform admin key registered at start (≥12 chars) |
| `notify.security.callback-secret` | `NOTIFY_CALLBACK_SECRET` | shared secret for vendor receipts |
| `notify.dispatch.poll-interval-ms` | 200 | claim cycle period |
| `notify.dispatch.claim-batch-per-tenant` | 20 | fairness quantum |
| `notify.dispatch.lease-duration` | 60s | how long a worker may hold a row |
| `notify.dispatch.pools.{EMAIL,SMS,PUSH,IN_APP}` | 8/8/8/4 | threads per channel |
| `notify.dispatch.queue-capacity-per-pool` | 200 | bounded queue per pool |
| `notify.retry.initial-backoff` / `multiplier` / `max-backoff` | 1s / 2.0 / 5m | backoff policy |
| `spring.datasource.url` | `DATABASE_URL` | real PostgreSQL for non-local runs |

Platform defaults editable at runtime via `/api/v1/admin/settings`: default rate 50/s, burst 100,
max attempts 5, max allowed tenant rate 1000/s.

## 6. Tests

```
./mvnw test                               # everything
./mvnw test -Dtest='*Test'                # unit only (< 1 s)
./mvnw test -Dtest='FairnessIT,RateLimitIT'
```

Integration tests boot the full application on a random port against an embedded PostgreSQL 16 (zonky),
run Flyway, and drive the real HTTP API with `TestRestTemplate`. Each test creates its own tenant, so the
suite shares one database and one Spring context without interference. Async behaviour is asserted with
Awaitility, never with fixed sleeps except where a *negative* is being proved (e.g. "still SCHEDULED after
500 ms", "suspended tenant not dispatched in 1.5 s").

What the notable tests prove:

- `ConcurrentClaimIT`: 6 concurrent pollers × 150 rows → exactly one attempt per row, one `SENT` event each.
- `FairnessIT`: 300 slow emails from tenant A then 10 from tenant B → B's last send precedes A's 60th; one
  `pollOnce()` across three tenants claims exactly 20 each.
- `RateLimitIT`: 5/s burst 5 → 20 SMS take ≥ 2.5 s, ≤ 6 sends in any later 1 s window, limiter skips counted.
- `RetryIT`: two timeouts then success = 3 attempts with growing delays; bounce = 1 attempt `FAILED`;
  cap of 2 attempts → `FAILED` with "Exhausted 2 attempts".
- `LeaseReaperIT`: a row leased by a dead worker is requeued with `attempt_count` unchanged and then sent.
- `IdempotencyIT`: 25 concurrent submissions with one key → one row, one 202, 24 × 200.
- `SecurityIT`: 401/403 matrix, cross-tenant 404, revoked key, hashed storage, once-only key display.

## 7. Scope decisions and assumptions

- **Single process by design** (brief excludes distributed systems). Everything except the in-memory token
  bucket is already multi-instance safe because of `SKIP LOCKED` + leases; the bucket is one class to swap
  for a shared store.
- **Email/SMS/push vendors are simulated** behind a `ChannelProvider` contract that mirrors a real vendor
  (idempotency key in, provider message id out, async receipt). Wiring SES/Twilio/FCM is one class each.
- **Ingestion is not rate-limited**; the database is the buffer and dispatch is paced. A 429 at intake would
  move retry logic into every client.
- **Recipients are opaque strings** validated only for length; address-format validation belongs to the
  provider that knows its channel.
- **No per-recipient preferences, quiet hours or unsubscribe** (would be a `recipient_preferences` table
  consulted at accept time).
- **Reports are computed on read** over indexed columns and capped at 92 days; a rollup table would be the
  next step at higher volume.
- **Template syntax** is `{{path}}` with dotted lookup; no conditionals or loops. Strictness over power.
- **Schedules** are capped at 30 days ahead and are absolute UTC instants; no recurrence.
- Out of scope per brief: UI, containers, CI/CD, OAuth/SSO/MFA, metrics/alerting (only `/actuator/health`).

## 8. Repository layout

```
src/main/java/com/ajaypatel/notify/
  tenant/       tenants, platform settings, admin controller
  security/     API keys, filter, RBAC config, bootstrap key
  channel/      per-tenant channel configuration
  template/     templates, versions, strict renderer
  notification/ entity, state machine, auditor, intake service, search, controller
  dispatch/     fair-share claimer, worker pools, delivery worker, backoff, lease reaper, promoter, admin stats
  ratelimit/    token bucket, per-tenant limiter
  provider/     ChannelProvider contract, simulated vendors, registry
  inbox/        IN_APP provider and inbox API
  callback/     vendor receipts (HTTP + in-process sink)
  report/       delivery summary
  common/       errors (RFC 7807), JSON converter, clock, OpenAPI, embedded Postgres for `local`, demo seed
src/main/resources/db/migration/V1__init.sql
src/test/java/com/ajaypatel/notify/{unit,it,support}/
docs/adr/            six architecture decision records
docs/ai-workflow/    plan, decisions, session log
docs/assignment/     the brief
.claude/skills/      skills used by the AI sessions on this repo (see CLAUDE.md)
scripts/demo.sh      end-to-end walkthrough with curl
```

## 9. AI workflow

This repository was built with Claude Code as a pair. `CLAUDE.md` is the standing brief the agent reads
every session; `.claude/skills/` holds the three skills used (`feature-with-tests`, `adr`, `smoke-run`);
`docs/ai-workflow/` has the plan, the choice of assignment, a session log with the prompts that shaped the
design. Design decisions, scope calls and the test plan were made by me and written
down first; the agent implemented against them, and every feature landed with its tests in the same commit.
