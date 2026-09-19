# ADR 0002: Fairness by round-robin claiming, not by priority queues

## Context
A tenant that bulk-loads 500k marketing emails must not delay another tenant's OTP SMS. Worker pools are bounded,
so "who gets the next free slot" is the fairness decision.

## Decision
Each poll cycle the claimer lists tenants with due work, rotates the list by a moving cursor so the starting tenant
changes every cycle, and claims at most `claim-batch-per-tenant` rows per tenant per cycle. Within a tenant, rows
are ordered by `priority DESC, next_attempt_at ASC`, so a tenant can prioritise its own OTPs over its own
newsletters, but cannot buy priority over other tenants.

## Alternatives considered
- **Global FIFO**: the simplest and exactly the starvation scenario the requirement forbids.
- **Weighted fair queuing with per-tenant weights**: more expressive, but the requirement asks for fairness, not
  paid tiers. The rate limit per tenant already differentiates tenants where needed; adding weights would be a
  second knob to explain.
- **One worker pool per tenant**: fair, but thread count grows with tenants and idle tenants waste threads.

## Consequences
- With N active tenants and batch B, each tenant is guaranteed B claims per cycle while capacity lasts.
- The claim is per (tenant, channel), bounded by that channel pool's free capacity, so a slow SMS vendor cannot
  fill the email pool.
- Cost: one `SELECT DISTINCT tenant_id` per cycle. Acceptable; the partial index covers it.
