# Skill: Defensive Schema Parsing for Hook Payloads

Use this skill when editing payload models and event parsing under `src/main/kotlin/com/agentpulse/model/` and watcher/provider code.

## Principles

- Assume additive schema evolution from upstream agents.
- Keep parsing forward-compatible.
- Prefer nullable fields with defaults in payload models.

## Required practices

- Use `Json { ignoreUnknownKeys = true }` for payload decoding.
- Use `@SerialName` for wire names that differ from Kotlin names.
- Keep unknown/extra values in `extra: Map<String, String>` where needed.
- Fail one event safely, not the entire app loop.

## Validation checklist

- Can old payloads still decode after adding new fields?
- Can new payloads decode when unknown keys appear?
- Do providers continue functioning with missing optional fields?

