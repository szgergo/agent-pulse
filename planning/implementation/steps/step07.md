# Step 7: cursor — Cursor hook provider

> **⚠️ READ `shared-context.md` FIRST** — it contains all design principles, architecture,
> and project structure that apply to this step.
> Path: `planning/implementation/shared-context.md`
>
> 📚 **Further reading**: Cursor hook schemas, SQLite structure, and conversation_id details in [`research/agent-research.md`](../../research/agent-research.md)

---

**Goal**: Deploy Cursor hook config and implement `CursorProvider.processEvent()` so that Cursor AI activity is captured via hooks and flows through the dashboard.

**Pre-check**: Step 6 PR is merged. `git checkout main && git pull`.

**App state AFTER this step**: When Cursor AI features are used (chat, edits, tool calls), events flow through hooks → `report.sh` → event files → FileWatcher → `CursorProvider.processEvent()` → dashboard. Cursor sessions appear in the agent list with session identity from `conversation_id`, file-edit tracking, and event counts.

---

- [ ] **7.1 Deploy Cursor hook config**
    - Cursor v1.7+ supports first-class hooks via `~/.cursor/hooks.json` (global) or `.cursor/hooks.json` (project-level)
    - Supported events: `sessionStart`, `afterFileEdit`, `postToolUse`, `beforeShellExecution`, `afterShellExecution`
    - Hook receives JSON on stdin with `conversation_id`, tool details, file path, etc.
    - `conversation_id` in the payload = unique session ID per Cursor conversation
    - `$PPID` in hook shell = Cursor's process PID
    - Create `~/.cursor/hooks.json` (or merge into existing if the file already exists):
      ```json
      {
        "hooks": {
          "sessionStart": [
            { "command": "$HOME/.agent-pulse/hooks/report.sh sessionStart cursor" }
          ],
          "afterFileEdit": [
            { "command": "$HOME/.agent-pulse/hooks/report.sh afterFileEdit cursor" }
          ],
          "postToolUse": [
            { "command": "$HOME/.agent-pulse/hooks/report.sh postToolUse cursor" }
          ]
        }
      }
      ```
    - Add deployment logic to `HookDeployer.deployCursorHooks()`:
        - Read existing `~/.cursor/hooks.json` if present
        - Merge agent-pulse hook entries into each event array (avoid duplicates)
        - Write the merged config back
        - Log the deployed hook config path

- [ ] **7.2 Implement CursorProvider.processEvent()**
    - Replace the stub provider with full Cursor-specific event handling
    - Session identity: use `conversation_id` from the hook JSON payload (falls back to PID)
    - Track file edits: count edits, record last-edited file in `extra`
    - Handle `sessionStart`, `afterFileEdit`, and generic events (e.g. `postToolUse`)

  ```kotlin
  class CursorProvider : AgentProvider {
      override val agentType = AgentType.CursorIde

      override fun processEvent(event: HookEvent, currentState: AgentState?): AgentState {
          val p = event.payload as CursorPayload
          val conversationId = p.conversationId   // @SerialName("conversation_id")
          val sessionId = conversationId ?: event.pid.toString()

          return when (event.eventType) {
              "sessionStart" -> AgentState(
                  id = "${agentType.name}_$sessionId",
                  name = "Cursor — ${conversationId?.take(8) ?: "PID ${event.pid}"}",
                  agentType = agentType,
                  status = AgentStatus.Running,
                  pid = event.pid,
                  sessionId = conversationId,
                  eventCount = 1,
                  lastActivity = event.timestamp * 1000,
              )
              "afterFileEdit" -> {
                  val filePath = p.filePath          // @SerialName("file_path")
                  currentState?.copy(
                      eventCount = currentState.eventCount + 1,
                      lastActivity = event.timestamp * 1000,
                      extra = currentState.extra + buildMap {
                          filePath?.let { put("lastEditedFile", it) }
                          put("fileEdits", ((currentState.extra["fileEdits"]?.toIntOrNull() ?: 0) + 1).toString())
                      },
                  ) ?: fallbackState(event, sessionId)
              }
              else -> currentState?.copy(
                  eventCount = (currentState.eventCount) + 1,
                  lastActivity = event.timestamp * 1000,
              ) ?: fallbackState(event, sessionId)
          }
      }

      private fun fallbackState(event: HookEvent, sessionId: String) = AgentState(
          id = "${agentType.name}_$sessionId",
          name = "Cursor — ${sessionId.take(8)}",
          agentType = agentType,
          status = AgentStatus.Running,
          pid = event.pid,
          sessionId = sessionId,
          eventCount = 1,
          lastActivity = event.timestamp * 1000,
      )
  }
  ```

- [ ] **7.3 Verify** — Run agent-pulse, open Cursor, use AI features (chat, edit, tool use), confirm:
    - Hook events land in `~/.agent-pulse/events/` as JSON files
    - Dashboard shows Cursor session(s) with correct name, event count, and last-activity timestamp
    - File edits are tracked (`fileEdits` count and `lastEditedFile` in extra)

- [ ] **7.4 Commit, push, and open PR**
  ```bash
  git checkout -b step-7-cursor-provider
  git add -A && git commit -m "feat: Cursor hook provider with processEvent()

  - Deploy ~/.cursor/hooks.json for sessionStart, afterFileEdit, postToolUse
  - HookDeployer.deployCursorHooks() with merge-if-exists logic
  - CursorProvider.processEvent() with conversation_id session identity
  - Track file edits count and last-edited file in extra map

  Co-authored-by: Copilot <223556219+Copilot@users.noreply.github.com>"
  git push -u origin step-7-cursor-provider
  gh pr create --title "Step 7: Cursor hook provider" \
    --body "Deploy Cursor hook config, implement CursorProvider.processEvent() with conversation_id session identity and file-edit tracking." \
    --base main
  ```
