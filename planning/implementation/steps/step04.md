# Step 4: copilot — Copilot CLI hook provider

> **⚠️ READ `shared-context.md` FIRST** — it contains all design principles, architecture,
> and project structure that apply to this step.
> Path: `planning/implementation/shared-context.md`
>
> 📚 **Further reading**: Copilot CLI process signatures, session ID lookup, hook schemas, and events.jsonl structure in [`research/agent-research.md`](../../research/agent-research.md)

---

**Goal**: Deploy Copilot CLI hook config and implement `CopilotCliProvider.reconcileAgentState()` to parse hook events and track Copilot sessions in real time.

**Pre-check**: Step 3 PR is merged. `./gradlew run` shows watcher output.

**App state AFTER this step**: When Copilot CLI sessions start/stop/use tools, events flow through hooks → watcher → provider. Terminal shows `[agent-pulse] Event: copilot-cli/sessionStart (PID 12345)`. `AgentState` objects are created and updated in the `StateFlow`. UI display comes in Step 5.

---

- [ ] **4.1 Deploy Copilot CLI hook config**
    - Create `~/.copilot/hooks/agent-pulse.json` (Copilot CLI merges all JSON files in `hooks/` dir)
    - Config maps events to `report.sh` invocation:
      ```json
      {
        "copilot:sessionStart": [
          { "command": "$HOME/.agent-pulse/hooks/report.sh sessionStart copilot-cli" }
        ],
        "copilot:sessionEnd": [
          { "command": "$HOME/.agent-pulse/hooks/report.sh sessionEnd copilot-cli" }
        ],
        "copilot:postToolUse": [
          { "command": "$HOME/.agent-pulse/hooks/report.sh postToolUse copilot-cli" }
        ],
        "copilot:userPromptSubmitted": [
          { "command": "$HOME/.agent-pulse/hooks/report.sh userPromptSubmitted copilot-cli" }
        ]
      }
      ```
    - Add deployment to `HookDeployer.deployCopilotCliHooks()`:
      - Check if `~/.copilot/hooks/` exists → create if needed
      - Write `agent-pulse.json` with the config above
      - Never overwrite existing hook files from other tools — only write our own `agent-pulse.json`

  ```kotlin
  // In HookDeployer.kt

  fun deployCopilotCliHooks() {
      val hooksDir = Path.of(System.getProperty("user.home"), ".copilot", "hooks")
      hooksDir.createDirectories()

      val hookConfig = hooksDir.resolve("agent-pulse.json")
      val events = listOf("sessionStart", "sessionEnd", "postToolUse", "userPromptSubmitted")
      val configJson = buildJsonObject {
          for (event in events) {
              putJsonArray("copilot:$event") {
                  addJsonObject {
                      put("command", "\$HOME/.agent-pulse/hooks/report.sh $event copilot-cli")
                  }
              }
          }
      }
      hookConfig.writeText(Json { prettyPrint = true }.encodeToString(configJson))
  }
  ```

- [ ] **4.2 Implement CopilotCliProvider.reconcileAgentState()**
    - Replace the Step 2 stub with full event-driven state management
    - Handle: `SessionStart`, `SessionEnd`, `PostToolUse`, `UserPromptSubmitted`
    - Unknown event types are handled gracefully (increment eventCount, update lastActivity)

  ```kotlin
  // In CopilotCliProvider.kt — replace the stub from Step 2

  class CopilotCliProvider : AgentProvider {
      override val agentType = AgentType.CopilotCli

      override fun reconcileAgentState(event: HookEvent, currentState: AgentState?): AgentState {
          val p = event.payload as CopilotPayload
          val sessionId = resolveSessionId(event.pid)
          return when (event.eventType) {
              HookEventType.SessionStart -> AgentState(
                  id = "${agentType.name}_${sessionId ?: event.pid}",
                  name = "Copilot CLI — ${sessionId?.take(8) ?: "PID ${event.pid}"}",
                  agentType = agentType,
                  status = AgentStatus.Running,
                  pid = event.pid,
                  sessionId = sessionId,
                  cwd = p.cwd?.let { Path.of(it) },
                  eventCount = 1,
                  lastActivity = event.timestamp,
              )
              HookEventType.SessionEnd -> currentState?.copy(
                  status = AgentStatus.Stopped,
                  eventCount = currentState.eventCount + 1,
                  lastActivity = event.timestamp,
              ) ?: fallbackState(event)
              HookEventType.PostToolUse -> {
                  val toolName = p.toolName
                  currentState?.copy(
                      eventCount = currentState.eventCount + 1,
                      lastActivity = event.timestamp,
                      extra = currentState.extra + buildMap {
                          toolName?.let { put("lastTool", it) }
                          put("toolCalls", ((currentState.extra["toolCalls"]?.toIntOrNull() ?: 0) + 1).toString())
                      },
                  ) ?: fallbackState(event)
              }
              HookEventType.UserPromptSubmitted -> currentState?.copy(
                  eventCount = currentState.eventCount + 1,
                  lastActivity = event.timestamp,
                  extra = currentState.extra + mapOf(
                      "prompts" to ((currentState.extra["prompts"]?.toIntOrNull() ?: 0) + 1).toString()
                  ),
              ) ?: fallbackState(event)
              else -> currentState?.copy(
                  eventCount = currentState.eventCount + 1,
                  lastActivity = event.timestamp,
              ) ?: fallbackState(event)
          }
      }

      private fun fallbackState(event: HookEvent): AgentState {
          val sessionId = resolveSessionId(event.pid)
          return AgentState(
              id = "${agentType.name}_${sessionId ?: event.pid}",
              name = "Copilot CLI — ${sessionId?.take(8) ?: "PID ${event.pid}"}",
              agentType = agentType,
              status = AgentStatus.Running,
              pid = event.pid,
              sessionId = sessionId,
              eventCount = 1,
              lastActivity = event.timestamp,
          )
      }
  }
  ```

- [ ] **4.3 Session ID resolution**
    - Given a PID from the hook, find the Copilot CLI session UUID by scanning for `inuse.<PID>.lock` files
    - This is a read-only scan — we never write to Copilot's directory

  ```kotlin
  // In CopilotCliProvider.kt

  private fun resolveSessionId(pid: Int): String? {
      val sessionStateDir = Path.of(System.getProperty("user.home"), ".copilot", "session-state")
      if (!sessionStateDir.exists()) return null
      return sessionStateDir.listDirectoryEntries().firstOrNull { dir ->
          dir.isDirectory() && dir.listDirectoryEntries("inuse.$pid.lock").isNotEmpty()
      }?.fileName?.toString()
  }
  ```

- [ ] **4.4 Verify**
    - Run `./gradlew run` with hook deployed
    - Start a Copilot CLI session — `sessionStart` event should flow through
    - Use a tool in the session — `postToolUse` event should update state
    - End the session — `sessionEnd` event should set status to `Stopped`
    - Terminal logs confirm events arrive and `AgentState` is created/updated

- [ ] **4.5 Commit, push, and open PR**
  ```bash
  git checkout -b step-4-copilot-provider
  git add -A && git commit -m "feat: Copilot CLI hook provider with session tracking

  - Deploy hook config to ~/.copilot/hooks/agent-pulse.json
  - CopilotCliProvider.reconcileAgentState() handles SessionStart/End, PostToolUse, UserPromptSubmitted
  - Session ID resolution via inuse.<PID>.lock file scan
  - HookDeployer.deployCopilotCliHooks() for hook config management

  Co-authored-by: Copilot <223556219+Copilot@users.noreply.github.com>"
  git push -u origin step-4-copilot-provider
  gh pr create --title "Step 4: Copilot CLI hook provider" \
    --body "Hook config deployment, CopilotCliProvider.reconcileAgentState() with session tracking, PID→session resolution." \
    --base main
  ```
