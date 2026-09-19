---
name: feature-with-tests
description: Land one feature slice of the notification service end to end, with its tests, in a single commit. Use whenever adding or changing behaviour in src/main.
---

# feature-with-tests

1. Read `CLAUDE.md` and the milestone in `docs/ai-workflow/PLAN.md`.
2. Decide the package (`tenant`, `template`, `notification`, `dispatch`, ...). Do not create cross-cutting utilities without an ADR.
3. Schema first: if persistence changes, add `V<n>__<name>.sql`. Never edit an applied migration.
4. Entity → repository → service → controller. Constructor injection. DTOs are records. Errors are domain exceptions from `common.error`.
5. Every state change of a `Notification` goes through `NotificationAuditor.transition` so the audit trail and the state machine stay consistent.
6. Tests in the same commit:
   - pure logic → `src/test/java/.../unit/*Test.java`
   - HTTP + DB behaviour → `src/test/java/.../it/*IT.java` extending `AbstractIntegrationTest`, one fresh tenant per test for isolation.
7. Run `./mvnw test`. Fix the code, not the assertion, unless the assertion was wrong.
8. Commit with a plain description of the change. No adjectives about quality.
