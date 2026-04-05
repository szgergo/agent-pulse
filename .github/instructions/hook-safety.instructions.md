---
applyTo: "src/main/resources/hooks/*.sh,src/main/kotlin/com/agentpulse/deploy/**/*.kt,src/main/kotlin/com/agentpulse/watcher/**/*.kt,src/main/kotlin/com/agentpulse/provider/**/*.kt"
---

# Hook safety and event pipeline guidance

## Hook script safety

- Monitoring hooks must always end with exit code 0.
- Never use hook logic that can block for long (network, long subprocess chains).
- Prefer atomic temp-write + rename for event file creation.
- Keep event filenames parseable and stable.

## Event pipeline safety

- Treat all event payloads as untrusted input.
- Parse defensively and tolerate unknown/new fields.
- Ensure malformed events are dropped with diagnostics, not crashes.
- Keep stale-file cleanup bounded and deterministic.

## Provider safety

- Reconciliation must be isolated per provider.
- Use safe fallback state when current state is missing.
- Never allow one bad payload to break all agent updates.

