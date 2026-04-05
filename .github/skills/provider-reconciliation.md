# Skill: Provider Reconciliation and State Safety

Use this skill when editing providers under `src/main/kotlin/com/agentpulse/provider/`.

## Goal

Convert `HookEvent` updates into resilient `AgentState` transitions.

## Rules

- Keep state transitions explicit per `HookEventType`.
- Treat payload fields as optional and evolving.
- Preserve previous state on parse/reconcile failure where possible.
- Keep fallback state deterministic (stable id/name composition).

## Recommended pattern

1. Validate/cast payload safely.
2. Resolve session identity (if provider supports it).
3. Branch on event type.
4. Increment counters and activity timestamps consistently.
5. Return fallback state for first-seen non-start events.

## Error isolation

- Isolate provider failures from global pipeline.
- Avoid broad silent catches; log with provider and event context.
- Do not let malformed payloads crash other providers.

