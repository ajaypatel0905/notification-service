# ADR 0005: Templates render at accept time and are versioned

## Context
A template can be edited between a notification being accepted and being sent, and a template with a missing
variable should fail the caller, not silently send `{{name}}` to a customer.

## Decision
`POST /notifications` renders subject and body immediately (strict: any missing placeholder is a 400) and stores the
rendered text plus `template_id` and `template_version`. Every content change creates a new immutable
`template_versions` row. What was accepted is exactly what is sent, and every sent message can be traced to the
exact template text that produced it.

## Alternatives considered
- **Render at dispatch**: smaller rows and "latest content wins" semantics, but a bad variable set only fails minutes
  later inside a worker with no caller to tell, and a scheduled message could change meaning after approval.

## Consequences
- Rows are larger (rendered body stored per notification). Acceptable; text is small and the audit value is high.
- Renaming or deactivating a template never affects already-accepted notifications.
