---
applyTo: "src/main/kotlin/**/*.kt,build.gradle.kts,gradle.properties,settings.gradle.kts"
---

# Kotlin and Compose Desktop guidance

## Kotlin style

- Prefer immutable state and `data class` updates with `copy`.
- Keep nullable handling explicit; avoid force unwraps unless proven safe.
- Use sealed types and enums for event/state domains.
- Keep functions single-purpose and side effects obvious.

## Coroutines

- Use `Dispatchers.IO` for blocking file and process operations.
- Do not block UI/main thread with file I/O.
- Preserve cancellation behavior; do not swallow `CancellationException`.

## Compose Desktop

- Keep tray interactions predictable and platform-conscious.
- Avoid heavy recomposition work in composables.
- Keep UI state flow unidirectional from manager/state source to UI.

## Build discipline

- Keep toolchain vendor and language settings aligned with current Gradle setup.
- Prefer explicit imports and deterministic plugin/dependency versions.

