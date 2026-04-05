# Copilot Instructions for agent-pulse

This repository is a Kotlin/Compose Desktop system-tray app for monitoring AI coding agents via hook events.

## Project goals

- Keep monitoring read-only for agent-owned data.
- Keep hooks safe and fast so they never block agent workflows.
- Keep state updates resilient to malformed or changing payload schemas.
- Keep implementation aligned with `planning/implementation/shared-context.md` and step docs.
- Build and maintain for macOS, Windows, and Linux; always apply cross-platform best practices.

## Non-negotiable rules

- Never write to agent-owned data paths except approved hook registration files.
- Always make hook scripts exit zero, even on failure.
- Keep hook work minimal (disk write only, no network).
- Parse payloads with defensive defaults and `ignoreUnknownKeys = true`.
- Wrap provider reconciliation in error isolation so one provider cannot crash all updates.
- Use `Dispatchers.IO` for filesystem operations.

## Runtime and watcher rules

- Assume JetBrains Runtime behavior where documented in planning docs.
- For `WatchService`, use blocking `take()` loop and avoid polling/debounce patterns.
- Keep startup recovery logic idempotent and safe against stale event files.
- In planning, implementation, and review, always consider cross-platform behavior and avoid platform-specific regressions unless explicitly scoped.

## Gradle and CI

- Keep Kotlin and Compose plugin versions consistent.
- Preserve Sonar configuration and required keys in `build.gradle.kts`.
- Update workflows with pinned major versions or full SHAs where required by policy.

## Change style

- Prefer small, explicit changes over broad refactors.
- Do not silently change architecture from the implementation plan.
- If plan and code diverge, update docs in the same PR or call out the mismatch clearly.

## Validation

For code changes, run at minimum:

```bash
./gradlew build
```

For CI or static-analysis changes, also run:

```bash
./gradlew sonar --info
```
