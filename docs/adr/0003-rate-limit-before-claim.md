# ADR 0003: Rate limits are enforced at claim time with a token bucket

## Context
Per-tenant rate limits must hold under load. The naive place, the worker just before calling the provider, means
work is claimed, a thread is occupied, and then the row is thrown back, which churns the queue and burns threads.

## Decision
A token bucket per tenant (`rate`, `burst`, refilled continuously) lives in memory. Before claiming for a tenant,
the claimer reads the whole tokens available and claims at most that many; after claiming it debits exactly the
number claimed. Nothing is claimed that cannot be sent immediately. A tenant with zero tokens is skipped this cycle
and the skip is counted (visible in `/api/v1/admin/dispatch/stats`).

Limits resolve as tenant override, else platform default from `platform_settings`. Changing either evicts the
tenant's bucket so the new limit applies on the next cycle without a restart.

## Alternatives considered
- **Reject at the API with 429**: punishes the caller for the vendor's limit and moves retry logic to every client.
  The API accepts and the dispatcher paces; that is what a notification service is for.
- **Fixed-window counters**: allow 2x bursts at window edges. Token bucket gives a smooth rate with an explicit burst.
- **Database-backed bucket**: correct across instances but adds a hot row per tenant per cycle. Not needed while the
  service is single-process (scope), and the seam is one class (`TenantRateLimiter`).

## Consequences
- The rate limit is exact per process and approximate across processes (N instances allow up to N× the limit) until
  the limiter moves to a shared store.
- Under a sustained limit the queue simply grows; there is no dropping.
