# Step 8: codex-gemini — Codex + Gemini hook providers

> **⚠️ READ `shared-context.md` FIRST** — it contains all design principles, architecture,
> and project structure that apply to this step.
> Path: `planning/implementation/shared-context.md`
>
> 📚 **Further reading**: Codex notify config and Gemini hook schemas in [`research/agent-research.md`](../../research/agent-research.md)

---

**Goal**: Deploy Codex notify config and Gemini hook config, implement `CodexProvider` and `GeminiProvider` `reconcileAgentState()`.

**Pre-check**: Step 7 PR is merged. `git checkout main && git pull`. `./gradlew build` passes.

**App state AFTER this step**: Codex CLI fires its `notify` command which invokes `report.sh`, and events flow through `CodexProvider.reconcileAgentState()` into `AgentSessionManager`. Gemini CLI fires `AfterTool` hook events which flow through `GeminiProvider.reconcileAgentState()`. Both agents appear in the dashboard when active.

---

- [ ] **8.1 Deploy Codex notify config**
  **Resolve config directory**: Check `CODEX_HOME` env var first, fall back to `~/.codex/`:
  ```kotlin
  val codexDir = agentConfigDir("CODEX_HOME", ".codex")
  val configFile = codexDir.resolve("config.toml")
  ```
  > ⚠️ Without this check, all Codex sessions are invisible when `CODEX_HOME` is set.
  > See shared-context.md "Agent Config Directory Env Var Overrides".

  Add/update the `notify` command in `config.toml`. Be careful to preserve existing config content (TOML format).
  Add to `HookDeployer.deployCodexHooks()`:
  ```toml
  notify = ["$HOME/.agent-pulse/hooks/report.sh notify codex-cli"]
  ```

- [ ] **8.2 Implement CodexProvider.reconcileAgentState()**
  Replace the stub in `src/main/kotlin/com/agentpulse/provider/CodexProvider.kt`:
  ```kotlin
  class CodexProvider : AgentProvider {
      override val agentType = AgentType.CodexCli

      override fun reconcileAgentState(event: HookEvent, currentState: AgentState?): AgentState {
          val p = event.payload as CodexPayload
          val threadId = p.threadId   // @SerialName("thread_id")
          val sessionId = threadId ?: event.pid.toString()

          return currentState?.copy(
              eventCount = currentState.eventCount + 1,
              lastActivity = event.timestamp,
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
              lastActivity = event.timestamp,
          )
      }
  }
  ```

- [ ] **8.3 Deploy Gemini hook config**
  **Resolve config directory**: Check `GEMINI_CLI_HOME` env var first, fall back to `~/.gemini/`:
  ```kotlin
  val geminiDir = agentConfigDir("GEMINI_CLI_HOME", ".gemini")
  val settingsFile = geminiDir.resolve("settings.json")
  ```
  > ⚠️ Without this check, all Gemini sessions are invisible when `GEMINI_CLI_HOME` is set.
  > See shared-context.md "Agent Config Directory Env Var Overrides".

  Merge into `settings.json` hooks section. Preserve existing settings content.
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

  > **Gemini dual path note**: Gemini CLI uses two session path layouts. Both MUST be checked when
  > reading session data (post-MVP enrichment):
  > - **New path**: `<geminiDir>/tmp/{hash}/chats/session-*.json`
  > - **Legacy path**: `<geminiDir>/sessions/*.json`
  >
  > **Format note**: Gemini uses `.json` (single JSON object per file), NOT `.jsonl` (newline-delimited).
  > See [cli-continues #23](https://github.com/nicobailey/cli-continues/issues/23).

- [ ] **8.4 Implement GeminiProvider.reconcileAgentState()**
  Replace the stub in `src/main/kotlin/com/agentpulse/provider/GeminiProvider.kt`:
  ```kotlin
  class GeminiProvider : AgentProvider {
      override val agentType = AgentType.GeminiCli

      override fun reconcileAgentState(event: HookEvent, currentState: AgentState?): AgentState {
          val p = event.payload as GeminiPayload
          return currentState?.copy(
              eventCount = currentState.eventCount + 1,
              lastActivity = event.timestamp,
              extra = currentState.extra + buildMap {
                  p.toolName?.let { put("lastTool", it) }
                  put("toolCalls", ((currentState.extra["toolCalls"]?.toIntOrNull() ?: 0) + 1).toString())
              },
          ) ?: AgentState(
              id = "${agentType.name}_${event.pid}",
              name = "Gemini CLI — PID ${event.pid}",
              agentType = agentType,
              status = AgentStatus.Running,
              pid = event.pid,
              cwd = p.cwd?.let { Path.of(it) },
              eventCount = 1,
              lastActivity = event.timestamp,
              extra = buildMap { p.toolName?.let { put("lastTool", it) } },
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
  - CodexProvider.reconcileAgentState() — parses thread-id, tracks turns
  - Deploy Gemini hook config (AfterTool) via HookDeployer
  - GeminiProvider.reconcileAgentState() — tracks events by PID

  Co-authored-by: Copilot <223556219+Copilot@users.noreply.github.com>"
  git push -u origin step-8-codex-gemini
  gh pr create --title "Step 8: Codex + Gemini hook providers" \
    --body "Deploy hook configs for Codex (notify) and Gemini (AfterTool), implement reconcileAgentState() for both providers." \
    --base main
  ```
