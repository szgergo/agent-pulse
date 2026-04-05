# Skill: JBR WatchService on macOS

Use this skill when touching watcher behavior in `src/main/kotlin/com/agentpulse/watcher/`.

## Context

The project relies on JetBrains Runtime behavior for responsive file watching on macOS.

Guardrail policy:
- macOS runtime is supported **only** on JetBrains Runtime (JBR).
- Non-JBR runtime on macOS is unsupported.
- Linux/Windows should also use JBR for consistency (recommended, not a hard requirement yet).

## Rules

- Use a blocking watch loop with `take()`.
- Avoid polling loops and ad-hoc debounce.
- Keep event handling idempotent and safe on restart.
- Handle `OVERFLOW` by recovery scan.

## Implementation checklist

- Register watcher before startup replay scan.
- Ignore temp or incomplete files.
- Parse filename metadata defensively.
- Log failures at useful granularity without crash loops.
- Delete processed files in `finally` where appropriate.

## Failure patterns to avoid

- Busy loops around `poll()`.
- UI-thread file I/O from watcher callbacks.
- Unbounded stale-file accumulation.


