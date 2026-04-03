# Step 8: codex-gemini — Codex + Gemini hook providers

> **⚠️ READ `shared-context.md` FIRST** — it contains all design principles, architecture,
> and project structure that apply to this step.
> Path: `planning/implementation/shared-context.md`

---

**Goal**: Deploy Codex notify config and Gemini hook config, implement `CodexProvider` and `GeminiProvider` `processEvent()`.

**Pre-check**: Step 7 PR is merged. `git checkout main && git pull`. `./gradlew build` passes.

**App state AFTER this step**: Codex CLI fires its `notify` command which invokes `report.sh`, and events flow through `CodexProvider.processEvent()` into `AgentStateManager`. Gemini CLI fires `AfterTool` hook events which flow through `GeminiProvider.processEvent()`. Both agents appear in the dashboard when active.

---

- [ ] **8.1 Deploy Codex notify config**
  Add/update the `notify` command in `~/.codex/config.toml`. Be careful to preserve existing config content (TOML format).
  Add to `HookDeployer.deployCodexHooks()`:
  ```toml
  notify = ["$HOME/.agent-pulse/hooks/report.sh notify codex-cli"]
  ```

- [ ] **8.2 Implement CodexProvider.processEvent()**
  Replace the stub in `src/main/kotlin/com/agentpulse/provider/CodexProvider.kt`:
  ```kotlin
  class CodexProvider : AgentProvider {
      override val agentType = AgentType.CodexCli

      override fun processEvent(event: HookEvent, currentState: AgentState?): AgentState {
          val threadId = event.rawJson["thread_id"]?.jsonPrimitive?.contentOrNull
              ?: event.rawJson["thread-id"]?.jsonPrimitive?.contentOrNull
          val sessionId = threadId ?: event.pid.toString()

          return currentState?.copy(
              eventCount = currentState.eventCount + 1,
              lastActivity = event.timestamp * 1000,
              extra = currentState.extra + buildMap {
                  put("turns", ((currentState.extra["turns"]?.toIntOrNull() ?: 0) + 1).toString())
              },
          ) ?: AgentState(
              id = "${agentType.name}_$sessionId",
              name = "Codex CLI — ${sessionId.take(8)}",
              agentType = agentType,
              status = AgentStatus.Running,
              pid = event.pid,
              sessionId = threadId,
              eventCount = 1,
              lastActivity = event.timestamp * 1000,
          )
      }
  }
  ```

- [ ] **8.3 Deploy Gemini hook config**
  Merge into `~/.gemini/settings.json` hooks section. Preserve existing settings content.
  Add to `HookDeployer.deployGeminiHooks()`:
  ```json
  {
    "hooks": {
      "AfterTool": [
        { "command": "$HOME/.agent-pulse/hooks/report.sh AfterTool gemini-cli" }
      ]
    }
  }
  ```

- [ ] **8.4 Implement GeminiProvider.processEvent()**
  Replace the stub in `src/main/kotlin/com/agentpulse/provider/GeminiProvider.kt`:
  ```kotlin
  class GeminiProvider : AgentProvider {
      override val agentType = AgentType.GeminiCli

      override fun processEvent(event: HookEvent, currentState: AgentState?): AgentState {
          return currentState?.copy(
              eventCount = currentState.eventCount + 1,
              lastActivity = event.timestamp * 1000,
          ) ?: AgentState(
              id = "${agentType.name}_${event.pid}",
              name = "Gemini CLI — PID ${event.pid}",
              agentType = agentType,
              status = AgentStatus.Running,
              pid = event.pid,
              eventCount = 1,
              lastActivity = event.timestamp * 1000,
          )
      }
  }
  ```

- [ ] **8.5 Verify both**
  ```bash
  cd <project-root> && ./gradlew build
  ```
  Must compile with zero errors. Then `./gradlew run` — app should launch unchanged.
  Manual verification:
  - Configure Codex CLI with the notify command, run a Codex session, confirm events appear in dashboard.
  - Configure Gemini CLI with the hook config, run a Gemini session, confirm events appear in dashboard.

- [ ] **8.6 Commit, push, and open PR**
  ```bash
  git checkout -b step-8-codex-gemini
  git add -A && git commit -m "feat: Codex + Gemini hook providers

  - Deploy Codex notify config via HookDeployer
  - CodexProvider.processEvent() — parses thread-id, tracks turns
  - Deploy Gemini hook config (AfterTool) via HookDeployer
  - GeminiProvider.processEvent() — tracks events by PID

  Co-authored-by: Copilot <223556219+Copilot@users.noreply.github.com>"
  git push -u origin step-8-codex-gemini
  gh pr create --title "Step 8: Codex + Gemini hook providers" \
    --body "Deploy hook configs for Codex (notify) and Gemini (AfterTool), implement processEvent() for both providers." \
    --base main
  ```
