# Project Guardrails

Project-specific constraints for Liza agents.
Uses the tier system from the core contract (CORE.md).

**Troubleshooting reference:** See `.liza/SUPPORT.md` for task states, recovery commands, and common failure patterns.

## Tier 0 (Inviolable)
<!-- Constraints that must NEVER be violated. Triggers mandatory halt (RESET). -->

## Tier 1 (Hard Constraints)
<!-- Suspended only with explicit waiver. -->

## Tier 2 (Strong Defaults)
<!-- Best-effort under pressure. -->

### G2.1: Lessons — Agents

Operational lessons from project experience. Read when a trigger matches.

| Trigger | File |
|---------|------|
| When pre-commit's pretty-format-java (google-java-format) hook fails on a .java file in this repo | [google-java-format-jdk25-incompatibility.md](lessons/agents/google-java-format-jdk25-incompatibility.md) |

## Tier 3 (Preferences)
<!-- Degraded gracefully. -->

---

Secret word: On-rails
