# Why this assignment

Four options were offered (movie tickets, e-commerce orders, food delivery, notification service),
all Spring Boot, 48 hours, identical submission rules. Picked the notification service because:

- Smallest CRUD surface, deepest engineering: the hard requirements (bounded pools, fairness,
  rate limits, retries, idempotency, audit trail) are all backend mechanics that can be proven
  with tests instead of described in prose.
- It matches production experience: multi-client verification gateway with provider retries,
  callback dedup and notification fan-out, so design decisions come from real operational scars.
- The out-of-scope list (no UI, no distributed systems) is natural for a backend service rather
  than a compromise: an in-process worker pool over a Postgres-backed queue is the right answer.
