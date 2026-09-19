---
name: adr
description: Record an architecture decision for the notification service in docs/adr. Use when choosing between designs (queue, locking, retry policy, auth) or when reversing an earlier choice.
---

# adr

Create `docs/adr/NNNN-short-title.md` (next number in sequence) with sections:

- **Context**: the forces at play, in a paragraph.
- **Decision**: what was chosen, stated as a fact.
- **Alternatives considered**: each with the reason it lost.
- **Consequences**: what gets easier, what gets harder, what to revisit if scope changes (e.g. multi-instance).

Keep it under a page. Link the ADR from the README's design section.
