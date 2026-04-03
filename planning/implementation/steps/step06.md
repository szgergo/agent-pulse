# Step 6: claude — Claude Code hook provider

> **⚠️ READ `shared-context.md` FIRST** — it contains all design principles, architecture,
> and project structure that apply to this step.
> Path: `planning/implementation/shared-context.md`
>
> 📚 **Further reading**: Claude Code OTel endpoint, hook schemas, and settings.json structure in [`research/agent-research.md`](../../research/agent-research.md)

---

**Goal**: Deploy Claude Code hook config (merged into `~/.claude/settings.json`) and implement `ClaudeCodeProvider.processEvent()` so that Claude Code tool-use events flow through hooks into the dashboard.

**Pre-check**: Step 5 PR is merged. `git checkout main && git pull`. `./gradlew build` passes.

**App state AFTER this step**: When Claude Code uses tools, the `PostToolUse` hook fires, writing an event file. FileWatcher picks it up, routes it through `ClaudeCodeProvider.processEvent()`, and the dashboard shows the session with an incrementing event count and last-activity timestamp. Each tool use updates the session state in real time.

---

- [ ] **6.1 Deploy Claude Code hook config**
    - Read existing `~/.claude/settings.json` (may contain permissions, allowedTools, etc.)
    - Merge a `hooks` section into it — add agent-pulse entries without removing any existing hooks
    - Write the merged file back
    - If the file doesn't exist, create it with only the hooks section
    - Hook config to merge:
      ```json
      {
        "hooks": {
          "PostToolUse": [
            {
              "type": "command",
              "command": "$HOME/.agent-pulse/hooks/report.sh PostToolUse claude-code"
            }
          ]
        }
      }
      ```
    - We only use `PostToolUse` — not `PreToolUse`, to avoid interfering with Claude Code's tool authorization flow
    - Add this logic to `HookDeployer.deployClaudeCodeHooks()`:
      1. Parse existing JSON (or start with empty `JsonObject`)
      2. Get or create `hooks` object
      3. Get or create `PostToolUse` array inside `hooks`
      4. Append the agent-pulse entry if not already present (match on `command` string containing `agent-pulse`)
      5. Write back with `prettyPrint = true`
    - MUST NOT overwrite the file — always read-merge-write

- [ ] **6.2 Implement ClaudeCodeProvider.processEvent()**
    - Create `src/main/kotlin/com/agentpulse/provider/ClaudeCodeProvider.kt`
    - Replace the stub from Step 2 with the full implementation
    - The first event for a given PID is treated as a synthetic session start (no separate `SessionStart` hook needed)
    - Extract `tool_name` from the hook JSON payload for display in `extra`
    - Track cumulative `toolCalls` count in `extra`
    ```kotlin
    package com.agentpulse.provider

    import com.agentpulse.model.*
    import com.agentpulse.model.ClaudePayload

    class ClaudeCodeProvider : AgentProvider {
        override val agentType = AgentType.ClaudeCode

        override fun processEvent(event: HookEvent, currentState: AgentState?): AgentState {
            val p = event.payload as ClaudePayload
            val toolName = p.toolName   // @SerialName("tool_name") handles the mapping

            if (currentState == null) {
                // First event for this PID = session start
                return AgentState(
                    id = "${agentType.name}_${event.pid}",
                    name = "Claude Code — PID ${event.pid}",
                    agentType = agentType,
                    status = AgentStatus.Running,
                    pid = event.pid,
                    eventCount = 1,
                    lastActivity = event.timestamp * 1000,
                    extra = buildMap { toolName?.let { put("lastTool", it) } },
                )
            }

            return currentState.copy(
                eventCount = currentState.eventCount + 1,
                lastActivity = event.timestamp * 1000,
                extra = currentState.extra + buildMap {
                    toolName?.let { put("lastTool", it) }
                    put("toolCalls", ((currentState.extra["toolCalls"]?.toIntOrNull() ?: 0) + 1).toString())
                },
            )
        }
    }
    ```
    - Note: PPID of the hook process is Claude Code's PID — the hook script captures `$PPID` and encodes it in the event filename

- [ ] **6.3 Verify end-to-end**
    - Run `./gradlew build` — must compile with zero errors
    - Start agent-pulse: `./gradlew run`
    - Open Claude Code and trigger any tool use (e.g. read a file)
    - Confirm an event file appears in `~/.agent-pulse/events/`
    - Confirm the dashboard shows a Claude Code session with event count incrementing on each tool use

- [ ] **6.4 Commit, push, and open PR**
    ```bash
    git checkout -b step-6-claude-code-provider
    git add -A && git commit -m "feat: Claude Code hook provider

    - Deploy PostToolUse hook config merged into ~/.claude/settings.json
    - Implement ClaudeCodeProvider.processEvent() with session detection
    - First event per PID treated as synthetic session start
    - Track tool name, tool call count, and last activity

    Co-authored-by: Copilot <223556219+Copilot@users.noreply.github.com>"
    git push -u origin step-6-claude-code-provider
    gh pr create --title "Step 6: Claude Code hook provider" \
      --body "Deploys PostToolUse hook (merged into settings.json) and implements ClaudeCodeProvider.processEvent()." \
      --base main
    ```
