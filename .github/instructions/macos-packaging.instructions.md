---
applyTo: "build.gradle.kts,src/main/kotlin/**/*.kt,.github/workflows/build.yml"
---

# macOS tray and packaging guidance

## Runtime behavior

- Preserve macOS tray-app behavior (`LSUIElement` and local run UI element flags as configured).
- Avoid introducing dock icon regressions in tray-only flows.
- Keep popup placement logic stable and easy to reason about.

## Packaging

- Keep Compose Desktop native distribution configuration aligned with current release target.
- Avoid changing bundle id/versioning behavior without release-plan updates.
- Validate packaging changes with Gradle tasks before PR merge.

## UX constraints

- Respect platform conventions for tray/menu behavior.
- Prefer small iterative UX changes with quick manual verification notes.

