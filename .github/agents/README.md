# Custom Agents for agent-pulse

This directory defines role-specific agents for planning, implementation, review, and release work.

**Global contract:** `planning/` is the de-facto contract for all agents. Agents should use `planning/implementation/shared-context.md` and the user-specified step file as primary references before making decisions.

## Agents

- `planner.agent.md` - turns goals into step-aligned execution plans.
- `implementer.agent.md` - makes scoped code changes with low blast radius.
- `reviewer.agent.md` - performs findings-first, risk-focused code reviews.
- `release.agent.md` - fixes CI/release workflow and packaging issues.

## Suggested usage flow

1. Start with `planner` for complex work.
2. Handoff to `implementer` with explicit file scope.
3. Run `reviewer` before merge.
4. Use `release` for build/workflow breakages or release hardening.

Note: The user may periodically request a full review; in those cases reviewer should assess both `src/` and `planning/`, not only changed files.

### Planner-specific requirements

- Planner must not modify code files.
- Planner may sync docs in `planning/` only to prevent plan drift.
- Planner drafts should be written as root Markdown files like `working-plan-<topic>.md` and remain uncommitted until finalized.
- Every recommendation from planner must include: basis, credibility rationale, and source links.

## Handoff template

- Goal:
- Constraints:
- Planning contract files (required):
- Files in scope:
- Out of scope:
- Required verification:
- Done criteria:
- Sources reviewed (with links):
