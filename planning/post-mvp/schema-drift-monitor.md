# Plan: Agent Schema Drift Monitor (GitHub Actions)

## TL;DR
Daily GitHub Action that detects when any of the 7 supported AI agent providers change their hook schemas, file formats, or documentation — then auto-creates a GitHub Issue with structured context and assigns the Copilot coding agent to attempt a fix PR. Catches provider drift early so agent-pulse doesn't silently break.

---

## Prerequisites (must exist before the workflow is useful)

### P1. Test Infrastructure
- Add JUnit 5 + kotlinx-coroutines-test + Mockk to `build.gradle.kts` test dependencies
- Configure Gradle test task for JUnit Platform

### P2. Golden Fixture Files
Create `src/test/resources/fixtures/` with sample JSON payloads per agent:
```
fixtures/
  hooks/
    copilot-cli/session-start.json, post-tool-use.json, session-end.json, error-occurred.json
    claude-code/SessionStart.json, PostToolUse.json, SessionEnd.json, PreCompact.json
    cursor/sessionStart.json, postToolUse.json, beforeShellExecution.json, afterFileEdit.json
    codex/notify.json
    gemini/SessionStart.json, BeforeTool.json, AfterTool.json
  files/
    copilot-cli/inuse.lock, vscode.metadata.json
    claude-code/session-health.json
    cursor/chatSession.json
    codex/rollout-meta.jsonl
    gemini/session.json
```
These are the "contracts" — what we expect each agent to produce.

### P3. Contract Tests
`src/test/kotlin/com/agentpulse/schema/SchemaContractTest.kt`:
- For each fixture file, parse through the same `Json { ignoreUnknownKeys = true }` + `decodeFromString<XPayload>()` pipeline used in production
- Assert that all expected fields are non-null / correctly typed
- Assert that `HookEventType.fromRaw()` correctly maps all known event type strings
- These tests are what the Copilot coding agent will run to validate its fix attempts

---

## Workflow Architecture

### Phase 1: Detection (deterministic, no AI)

**Job: `detect-drift`**
Runs daily at 08:00 UTC via cron (also `workflow_dispatch` for manual trigger).

**Step 1.1 — Version Check**
For each agent, query latest version and compare against `.github/schema-monitor/versions.json` (committed baseline):

| Agent | Version Source | Method |
|---|---|---|
| Copilot CLI | GitHub Releases (`github/copilot-cli` or brew API) | `gh api repos/github/copilot-cli/releases/latest` |
| Claude Code | npm registry | `curl https://registry.npmjs.org/@anthropic-ai/claude-code/latest` → `.version` |
| Cursor | Cursor changelog page or update API | HTTP fetch + hash |
| Codex CLI | npm registry | `curl https://registry.npmjs.org/@openai/codex/latest` → `.version` |
| Gemini CLI | npm registry | `curl https://registry.npmjs.org/@google/gemini-cli/latest` → `.version` |
| Copilot VS Code/IntelliJ | VS Code Marketplace API / JetBrains Marketplace API | HTTP fetch version field |

Output: list of agents with version bumps.

**Step 1.2 — Documentation Page Monitor**
Fetch and hash key documentation pages. Compare against `.github/schema-monitor/docs-hashes.json`:

| Agent | Documentation URL |
|---|---|
| Claude Code | `https://docs.anthropic.com/en/docs/claude-code/hooks` |
| Copilot | `https://docs.github.com/en/copilot/customizing-copilot/extending-copilot-agent-mode-in-vs-code/using-copilot-coding-agent-hooks` |
| Cursor | `https://docs.cursor.com/agent/hooks` |
| Codex | `https://github.com/openai/codex/blob/main/README.md` (raw) |
| Gemini | `https://github.com/google-gemini/gemini-cli/blob/main/docs/hooks.md` (raw, if exists) |

Hashing: SHA-256 of page body text (strip HTML/navigation with a simple text extraction to avoid false positives from layout changes).

Output: list of docs pages with content changes.

**Step 1.3 — Source Code Schema Check (open-source agents only)**
For agents with version bumps, fetch hook-related source files from their repos at the new tag and diff against stored baselines in `.github/schema-monitor/source-baselines/`:

| Agent | Repo | Key files to track |
|---|---|---|
| Claude Code | `anthropics/claude-code` | Hook type definitions, event enum, payload interfaces |
| Codex CLI | `openai/codex` | Notification payload types, event schema |
| Gemini CLI | `google-gemini/gemini-cli` | Hook event types, payload definitions |

Method: `gh api repos/{owner}/{repo}/contents/{path}?ref={latest_tag}` → compare with baseline.

Output: structured diff of schema-relevant source changes.

**Step 1.4 — Composite Decision**
If ANY of steps 1.1–1.3 detected changes → proceed to Phase 2.
If nothing changed → update run timestamp in `versions.json`, exit cleanly.

---

### Phase 2: Report + Trigger Fix (AI-assisted)

**Job: `create-issue-and-fix`** (depends on `detect-drift`)

**Step 2.1 — Create GitHub Issue**
Auto-create a GitHub Issue with label `schema-drift` containing:
- Which agents have version bumps (old → new version)
- Which documentation pages changed (with diff links if available)
- Which source schemas changed (inline diff snippets)
- Links to relevant changelogs / release notes
- List of our affected files: model classes, provider implementations, fixture files
- Checklist of what needs updating

Issue title format: `[Schema Drift] {Agent1}, {Agent2} — {date}`

**Step 2.2 — Assign Copilot Coding Agent**
Assign `@copilot` to the issue. The issue body provides enough context for Copilot to:
1. Read the detected changes
2. Update `HookPayload` subclasses in `src/main/kotlin/com/agentpulse/model/HookPayload.kt`
3. Update affected provider implementations
4. Update golden fixture files
5. Update `HookEventType` enum if new events appeared
6. Run `./gradlew test` to validate

**Step 2.3 — Fallback: Draft PR with raw diffs**
If Copilot coding agent is unavailable or fails to produce a PR within a timeout:
- Create a draft PR that updates `.github/schema-monitor/versions.json` and `docs-hashes.json` with the new baselines
- Attach the diff report as a PR comment
- This ensures we at least track that we know about the change

---

## Repository Structure Additions

```
.github/
  workflows/
    schema-monitor.yml                    # The cron workflow
  schema-monitor/
    versions.json                         # { "claude-code": "1.2.3", "codex": "0.5.0", ... }
    docs-hashes.json                      # { "claude-code-hooks": "sha256:...", ... }
    source-baselines/                     # Stored baseline source files for diffing
      claude-code/
        hook-types.ts                     # (or whatever the relevant source file is)
      codex/
        notify-schema.ts
      gemini/
        hook-events.ts
    monitor.sh                            # Main detection script (or split into per-step scripts)
src/test/
  resources/fixtures/                     # Golden JSON fixture files (see P2 above)
  kotlin/com/agentpulse/
    schema/SchemaContractTest.kt          # Contract tests (see P3 above)
```

---

## Steps (Implementation Order)

### Phase A: Test Foundation (prerequisite, blocks everything)
1. Add test dependencies to `build.gradle.kts` (JUnit 5, Mockk, kotlinx-coroutines-test)
2. Create golden fixture JSON files for all 7 providers — one per event type that agent-pulse handles
3. Write `SchemaContractTest.kt` that parses each fixture through the production deserialization path
4. Verify `./gradlew test` passes

### Phase B: Monitoring Infrastructure
5. Create `.github/schema-monitor/versions.json` with current known versions for each agent *(parallel with step 6)*
6. Create `.github/schema-monitor/docs-hashes.json` by fetching and hashing current documentation pages *(parallel with step 5)*
7. Create `.github/schema-monitor/source-baselines/` with current hook-related source files from open-source repos *(depends on 5 for version tags)*
8. Write the detection script (`.github/schema-monitor/monitor.sh` or a composite action) that performs steps 1.1–1.4 *(depends on 5, 6, 7)*

### Phase C: GitHub Actions Workflow
9. Create `.github/workflows/schema-monitor.yml` with cron trigger + `workflow_dispatch` *(depends on 8)*
10. Implement issue creation step with structured body template *(depends on 9)*
11. Implement Copilot coding agent assignment *(depends on 10)*
12. Implement fallback draft PR creation *(depends on 10)*
13. Add status badge to README.md *(parallel with 12)*

### Phase D: Hardening
14. Add retry logic for HTTP requests (3 retries, exponential backoff)
15. Add rate-limit awareness for GitHub API (check `X-RateLimit-Remaining`)
16. Add workflow concurrency control (`concurrency: schema-monitor`) to prevent overlapping runs
17. Test full workflow via `workflow_dispatch` manual trigger

---

## Relevant Files

- `build.gradle.kts` — add test dependencies (Phase A)
- `src/main/kotlin/com/agentpulse/model/HookPayload.kt` — payload classes Copilot agent would update
- `src/main/kotlin/com/agentpulse/model/HookEventType.kt` — event enum Copilot agent would update
- `src/main/kotlin/com/agentpulse/provider/*.kt` — all 7 providers Copilot agent would update
- `planning/implementation/steps/step03.md` — reference for `parsePayload()` deserialization logic and filename parsing regex

---

## Verification

1. **Prereq validation**: `./gradlew test` passes with contract tests against all fixture files
2. **Detection dry-run**: Run `monitor.sh` locally, verify it correctly identifies current versions and hashes
3. **Workflow smoke test**: Trigger via `workflow_dispatch`, verify it completes without errors and produces no false-positive changes
4. **Simulated drift test**: Temporarily modify a fixture file to mismatch, push, trigger workflow, verify it creates an issue
5. **End-to-end**: Modify `versions.json` to an older version of one agent, trigger workflow, verify issue + Copilot assignment works
6. **Fallback test**: Verify draft PR is created when Copilot agent doesn't produce a PR within timeout

---

## Decisions

- **AI Agent**: GitHub Copilot coding agent (assigned to auto-created issues)
- **Frequency**: Daily at 08:00 UTC
- **Failure mode**: Issue + draft PR (visibility + preserved attempt)
- **Scope**: Full — hook schemas, file formats, and documentation pages
- **`ignoreUnknownKeys = true`**: Already used in deserialization — additive schema changes won't break parsing, but removed/renamed fields will. Contract tests catch both.
- **Closed-source agents (Cursor, Copilot VS Code/IntelliJ)**: Documentation + version monitoring only; no source baseline diffing
- **Script language**: Shell (Bash) for the detection script — keeps it simple, runs natively in GitHub Actions ubuntu runners, no extra dependencies

---

## Further Considerations

1. **Baseline bootstrapping**: The first run needs to populate `versions.json`, `docs-hashes.json`, and `source-baselines/` from scratch. Should the workflow have a `bootstrap` mode that only captures baselines without creating issues? **Recommendation**: Yes — add a `bootstrap` input to the `workflow_dispatch` trigger.

2. **Issue deduplication**: If the same agent has drift on consecutive days, should we create a new issue each time or update the existing one? **Recommendation**: Search for open issues with label `schema-drift` + agent name before creating — update existing if found, create new if not.

3. **Copilot coding agent availability**: This feature is still evolving. If it becomes unavailable or changes its API, the fallback (draft PR with raw diffs) ensures the workflow still provides value. Consider adding a simple notification (e.g., GitHub Actions summary annotation) as additional signal.
