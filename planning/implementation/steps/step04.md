# Step 4: copilot — Copilot CLI hook provider

> **⚠️ READ `shared-context.md` FIRST** — it contains all design principles, architecture,
> and project structure that apply to this step.
> Path: `planning/implementation/shared-context.md`
>
> 📚 **Further reading**: Copilot CLI process signatures, session ID lookup, hook schemas, and events.jsonl structure in [`research/agent-research.md`](../../research/agent-research.md)

---

**Goal**: Deploy Copilot hook config (`report-copilot.sh`) and implement `reconcileAgentState()` for all three Copilot providers (`CopilotCliProvider`, `CopilotVsCodeProvider`, `CopilotIntelliJProvider`) to parse hook events and track sessions in real time.

**Pre-check**: Step 3 PR is merged. `./gradlew run` shows watcher output.

**App state AFTER this step**: When any Copilot session (CLI, VS Code, or IntelliJ) starts/stops/uses tools, events flow through hooks → watcher → provider. Terminal shows e.g. `[agent-pulse] Event: copilot-vscode/sessionStart (PID 12345)`. `AgentState` objects are created and updated in the `StateFlow`. UI display comes in Step 5.

---

- [x] **4.0 PoC — DONE — manually wired hooks and confirmed event flow (no Kotlin) — DO NOT RUN AGAIN**

  The PoC was executed and verified: `report-copilot.sh` receives events from real Copilot
  CLI sessions, detects the agent type correctly, and writes event JSON files to
  `~/.agent-pulse/events/`.
  **Do not run this PoC again** — future verification will be performed by the Copilot agent
  implementation (Step 4.1 HookDeployer will manage deployment).

- [x] **4.0a — Write `report-copilot.sh` directly to `~/.agent-pulse/hooks/`**

  Write the script straight to its runtime location via a heredoc — no repo files, no git:

  ```sh
  mkdir -p "$HOME/.agent-pulse/hooks" "$HOME/.agent-pulse/events"
  cat > "$HOME/.agent-pulse/hooks/report-copilot.sh" << 'EOF'
  #!/bin/sh
  # Copilot hook — detects copilot-cli / copilot-vscode / copilot-intellij via $PPID inspection.
  # MUST always exit 0 — failure must never block the Copilot session.
  trap 'exit 0' ERR

  EVENTS_DIR="$HOME/.agent-pulse/events"
  mkdir -p "$EVENTS_DIR" || exit 0
  [ "$(find "$EVENTS_DIR" -name '*.json' -maxdepth 1 | head -1001 | wc -l)" -gt 1000 ] && exit 0

  # Two-level process tree detection:
  #   $PPID  = direct parent (the agent process that spawned this hook)
  #   GPID   = grandparent ($PPID's parent)
  # We check both because IntelliJ ACP mode wraps copilot behind a neutral binary,
  # so the IntelliJ marker only appears in the grandparent.
  # See: planning/research/agent-research.md → "Copilot Hook-Based Client Detection"
  PARENT_ARGS=$(ps -p "$PPID" -o args= 2>/dev/null || true)
  if echo "$PARENT_ARGS" | grep -q "github-copilot-intellij"; then
      AGENT_TYPE="copilot-intellij"   # IntelliJ plugin direct
  else
      GPID=$(ps -p "$PPID" -o ppid= 2>/dev/null | tr -d '[:space:]' || true)
      GRANDPARENT_ARGS=$(ps -p "$GPID" -o args= 2>/dev/null || true)
      if echo "$GRANDPARENT_ARGS" | grep -q "github-copilot-intellij"; then
          AGENT_TYPE="copilot-intellij"   # IntelliJ ACP mode
      elif echo "$GRANDPARENT_ARGS" | grep -qE "copilotCLIShim|copilotCli/copilot"; then
          AGENT_TYPE="copilot-vscode"     # VS Code extension shim
      else
          AGENT_TYPE="copilot-cli"        # Homebrew/npm CLI or CLI in any IDE terminal
      fi
  fi

  T=$(mktemp "$EVENTS_DIR/.tmp.XXXXXX") || exit 0
  cat > "$T" || { rm -f "$T" 2>/dev/null; exit 0; }
  mv "$T" "$EVENTS_DIR/$(date +%s)-${AGENT_TYPE}-$1-$PPID.json" || { rm -f "$T" 2>/dev/null; exit 0; }
  EOF
  chmod +x "$HOME/.agent-pulse/hooks/report-copilot.sh"
  ```

  > **Why no agent type arg:** The Copilot hooks directory fires for CLI, VS Code, IntelliJ, and
  > CLI inside IDE terminals. The script detects the sub-type via `$PPID` inspection:
  > - `$PPID` args contain `github-copilot-intellij` → `copilot-intellij`
  > - GPID args contain `github-copilot-intellij` → `copilot-intellij` (ACP mode)
  > - GPID args match `copilotCLIShim` or `copilotCli/copilot` → `copilot-vscode`
  > - Otherwise → `copilot-cli`
  >
  > See `research/agent-research.md` → "Copilot Hook-Based Client Detection" for the full process
  > hierarchy trees, detection matrix, and ACP design decision.

- [x] **4.0c — Deploy `agent-pulse.json` and smoke test**

  Deploy the hook config to the Copilot hooks directory (resolved via `COPILOT_HOME`, fallback `~/.copilot`):

  ```sh
  COPILOT_HOOKS="${COPILOT_HOME:-$HOME/.copilot}/hooks"
  mkdir -p "$COPILOT_HOOKS"
  cat > "$COPILOT_HOOKS/agent-pulse.json" << 'EOF'
  {
    "version": 1,
    "hooks": {
      "sessionStart":        [{ "type": "command", "bash": "$HOME/.agent-pulse/hooks/report-copilot.sh sessionStart" }],
      "sessionEnd":          [{ "type": "command", "bash": "$HOME/.agent-pulse/hooks/report-copilot.sh sessionEnd" }],
      "postToolUse":         [{ "type": "command", "bash": "$HOME/.agent-pulse/hooks/report-copilot.sh postToolUse" }],
      "userPromptSubmitted": [{ "type": "command", "bash": "$HOME/.agent-pulse/hooks/report-copilot.sh userPromptSubmitted" }],
      "subagentStart":       [{ "type": "command", "bash": "$HOME/.agent-pulse/hooks/report-copilot.sh subagentStart" }]
    }
  }
  EOF
  ```

  > **Note — `subagentStart`:** added in CLI 1.0.7. Fires in the **parent** session when
  > the CLI spawns a background sub-agent. This is the only hook that fires for background
  > agent activity — see the Known Limitation callout below.

  > **Known limitation — background sub-agent hooks (CLI issue #2293, unfixed as of 1.0.18):**
  > When Copilot spawns a background sub-agent subprocess (e.g. with `--autopilot`),
  > `preToolUse`, `postToolUse`, and other event hooks do **not** fire inside that subprocess.
  > Only `subagentStart` fires (in the parent) at spawn time. agent-pulse therefore tracks:
  > - main session events (start, end, tool use, prompt) ✓
  > - sub-agent spawn events (`subagentStart`) ✓
  > - sub-agent internal tool use ✗ (known gap, upstream bug)

  **Smoke test:**

  ```sh
  # Watch for arriving events (terminal 1):
  watch -n1 'ls -lt ~/.agent-pulse/events/ | head -6'

  # Run a Copilot CLI session (terminal 2):
  copilot
  # Ask something that invokes a tool, then /quit

  # Inspect the latest event file:
  cat $(ls -t ~/.agent-pulse/events/*.json | head -1) | jq .
  ```

  **Gate — confirm before moving on:**
  - Event JSON files appear in `~/.agent-pulse/events/` ✓
  - File names contain `copilot-cli` (e.g. `1712300000-copilot-cli-sessionStart-12345.json`) ✓
  - `sessionStart` / `postToolUse` / `sessionEnd` events all produce files ✓
  - *(Optional)* Repeat from VS Code's Copilot extension → file names contain `copilot-vscode` ✓

- [x] **4.0d — Revert PoC deployment (clean slate)**

  Remove everything deployed during the PoC so the Kotlin automation in 4.1 starts from scratch:

  ```sh
  rm -rf "$HOME/.agent-pulse"
  rm -f "${COPILOT_HOME:-$HOME/.copilot}/hooks/agent-pulse.json"
  ```

- [ ] **4.1 Commit `report-copilot.sh` as a repo resource**

  The HookDeployer needs to load the script at runtime from the classpath
  (`getResourceAsStream("/hooks/report-copilot.sh")`). The PoC wrote it via heredoc directly to
  `~/.agent-pulse/hooks/` and then deleted it — it was never committed. This step adds it to the repo.

  Script content is identical to what was used in the PoC (4.0a). Create the file:

  ```sh
  mkdir -p src/main/resources/hooks
  cat > src/main/resources/hooks/report-copilot.sh << 'EOF'
  #!/bin/sh
  # Copilot hook — detects copilot-cli / copilot-vscode / copilot-intellij via $PPID inspection.
  # MUST always exit 0 — failure must never block the Copilot session.
  trap 'exit 0' ERR

  EVENTS_DIR="$HOME/.agent-pulse/events"
  mkdir -p "$EVENTS_DIR" || exit 0
  [ "$(find "$EVENTS_DIR" -name '*.json' -maxdepth 1 | head -1001 | wc -l)" -gt 1000 ] && exit 0

  # Two-level process tree detection:
  #   $PPID  = direct parent (the agent process that spawned this hook)
  #   GPID   = grandparent ($PPID's parent)
  # We check both because IntelliJ ACP mode wraps copilot behind a neutral binary,
  # so the IntelliJ marker only appears in the grandparent.
  # See: planning/research/agent-research.md → "Copilot Hook-Based Client Detection"
  PARENT_ARGS=$(ps -p "$PPID" -o args= 2>/dev/null || true)
  if echo "$PARENT_ARGS" | grep -q "github-copilot-intellij"; then
      AGENT_TYPE="copilot-intellij"   # IntelliJ plugin direct
  else
      GPID=$(ps -p "$PPID" -o ppid= 2>/dev/null | tr -d '[:space:]' || true)
      GRANDPARENT_ARGS=$(ps -p "$GPID" -o args= 2>/dev/null || true)
      if echo "$GRANDPARENT_ARGS" | grep -q "github-copilot-intellij"; then
          AGENT_TYPE="copilot-intellij"   # IntelliJ ACP mode
      elif echo "$GRANDPARENT_ARGS" | grep -qE "copilotCLIShim|copilotCli/copilot"; then
          AGENT_TYPE="copilot-vscode"     # VS Code extension shim
      else
          AGENT_TYPE="copilot-cli"        # Homebrew/npm CLI or CLI in any IDE terminal
      fi
  fi

  T=$(mktemp "$EVENTS_DIR/.tmp.XXXXXX") || exit 0
  cat > "$T" || { rm -f "$T" 2>/dev/null; exit 0; }
  mv "$T" "$EVENTS_DIR/$(date +%s)-${AGENT_TYPE}-$1-$PPID.json" || { rm -f "$T" 2>/dev/null; exit 0; }
  EOF
  chmod +x src/main/resources/hooks/report-copilot.sh
  git add src/main/resources/hooks/report-copilot.sh
  git commit -m "chore: add report-copilot.sh as classpath resource for HookDeployer"
  ```

- [ ] **4.2a Create path utility functions (`PathUtils.kt`)**

  These helpers are defined in shared-context.md but have never been implemented in source code.
  Both CopilotHookDeployer (4.2b) and CopilotAgentProvider (4.3) depend on `agentConfigDir()`.
  The path helpers (`agentPulseHooksDir`, etc.) are also used by 4.2b and replace hardcoded paths
  in two existing files (see end of this step). Create the file:

  ```kotlin
  // File: src/main/kotlin/com/agentpulse/util/PathUtils.kt
  package com.agentpulse.util

  import java.nio.file.Path

  /**
   * Resolve an agent's config directory, respecting env-var overrides.
   *
   * Every agent lets users relocate their config via an env var (e.g. COPILOT_HOME).
   * Hardcoding the default path misses sessions for users who set these vars.
   *
   * @param envVar the environment variable name to check (e.g. "COPILOT_HOME"), or null if N/A.
   * @param defaultPath the fallback relative to user.home (e.g. ".copilot").
   */
  fun agentConfigDir(envVar: String?, defaultPath: String): Path =
      System.getenv(envVar)?.let { Path.of(it) }
          ?: Path.of(System.getProperty("user.home"), defaultPath)

  /**
   * agent-pulse base directory: `~/.agent-pulse`.
   * All agent-pulse data lives under this tree (hooks, events, cache, etc.).
   */
  fun agentPulseBaseDir(): Path =
      Path.of(System.getProperty("user.home"), ".agent-pulse")

  /**
   * Hooks directory: `~/.agent-pulse/hooks`.
   * Deployed hook scripts (e.g., report-copilot.sh, report-claude.sh) live here.
   */
  fun agentPulseHooksDir(): Path =
      agentPulseBaseDir().resolve("hooks")

  /**
   * Events directory: `~/.agent-pulse/events`.
   * Hook events are written here as JSON files by running hook scripts.
   */
  fun agentPulseEventsDir(): Path =
      agentPulseBaseDir().resolve("events")
  ```

  Now that PathUtils.kt is created, update two existing files to use these helpers instead of
  hardcoded paths:

  - **`deploy/AgentPulseHookDeployer.kt`** — replace:
    ```kotlin
    private val baseDir = Path.of(System.getProperty("user.home"), ".agent-pulse")
    ```
    with:
    ```kotlin
    import com.agentpulse.util.agentPulseBaseDir
    // ...
    private val baseDir = agentPulseBaseDir()
    ```

  - **`watcher/HookEventWatcher.kt`** — replace the default parameter value:
    ```kotlin
    private val eventsDir: Path = Path.of(System.getProperty("user.home"), ".agent-pulse", "events"),
    ```
    with:
    ```kotlin
    import com.agentpulse.util.agentPulseEventsDir
    // ...
    private val eventsDir: Path = agentPulseEventsDir(),
    ```

- [ ] **4.2b Automate deployment (`CopilotHookDeployer`)**

  Create `CopilotHookDeployer : HookDeployer` in `deploy/CopilotHookDeployer.kt`.
  Now that `report-copilot.sh` is a committed classpath resource, this deployer handles
  Copilot-specific hook deployment. Register it in the `deployers` list in `Main.kt`.

  - Load `report-copilot.sh` from classpath via `getResourceAsStream`, write to `~/.agent-pulse/hooks/`, `chmod +x`
  - Write `agent-pulse.json` to the Copilot hooks directory (resolved via `COPILOT_HOME`)
  - Copilot merges **all** `*.json` files from its hooks directory — other hook files are untouched and stay active alongside ours
  - Always overwrite our own `agent-pulse.json` on startup (keeps it in sync after app updates)
  - Guard step 2 with a Copilot-installed check: if `COPILOT_HOME`/`~/.copilot` does not exist, log a warning and skip — Copilot is not installed
  - `hooksDir.createDirectories()` is unconditional (it's our own directory); `copilotHooksDir.createDirectories()` only runs after confirming the parent exists
  - Deploy the script first, then the config (same order as the PoC)

  ```kotlin
  // File: src/main/kotlin/com/agentpulse/deploy/CopilotHookDeployer.kt
  package com.agentpulse.deploy

  import com.agentpulse.util.agentConfigDir
  import com.agentpulse.util.agentPulseHooksDir
  import kotlinx.serialization.encodeToString
  import kotlinx.serialization.json.Json
  import kotlinx.serialization.json.JsonElement
  import kotlinx.serialization.json.buildJsonObject
  import kotlinx.serialization.json.put
  import kotlinx.serialization.json.putJsonArray
  import kotlinx.serialization.json.putJsonObject
  import kotlin.io.path.createDirectories
  import kotlin.io.path.exists
  import kotlin.io.path.outputStream
  import kotlin.io.path.writeText

  // Implements HookDeployer interface — registered in the deployers list in Main.kt.
  // Uses agentConfigDir() and agentPulseHooksDir() helpers from util/PathUtils.kt.
  // See: https://docs.github.com/en/copilot/reference/copilot-cli-reference/cli-config-dir-reference
  //
  // Threading: all deployers are launched in parallel on Dispatchers.IO from main().
  // All filesystem I/O runs on the IO dispatcher — no additional withContext needed.

  class CopilotHookDeployer : HookDeployer {
      override fun deployAgentHook() {
          // Step 1: deploy report-copilot.sh from classpath resource
          val hooksDir = agentPulseHooksDir()
          hooksDir.createDirectories()
          val scriptDest = hooksDir.resolve("report-copilot.sh")
          javaClass.getResourceAsStream("/hooks/report-copilot.sh")!!.use { input ->
              scriptDest.outputStream().use { output -> input.copyTo(output) }
          }
          scriptDest.toFile().setExecutable(true, false)

          // Step 2: write agent-pulse.json to Copilot hooks directory
          val copilotHomeDir = agentConfigDir("COPILOT_HOME", ".copilot")
          if (!copilotHomeDir.exists()) {
              println("[agent-pulse] Copilot home not found at $copilotHomeDir — skipping hook deployment")
              return
          }
          // Copilot merges all *.json files from its hooks dir, so agent-pulse.json coexists
          // safely alongside any user-defined hook configs. We only write our own file.
          val copilotHooksDir = copilotHomeDir.resolve("hooks")
          copilotHooksDir.createDirectories()  // safe: only creates 'hooks' subdir; parent already confirmed

          val hookConfig = copilotHooksDir.resolve("agent-pulse.json")
          val events = listOf("sessionStart", "sessionEnd", "postToolUse", "userPromptSubmitted", "subagentStart")
          val configJson = buildJsonObject {
              put("version", 1)
              putJsonObject("hooks") {
                  for (event in events) {
                      putJsonArray(event) {
                          addJsonObject {
                              put("type", "command")
                              put("bash", "\$HOME/.agent-pulse/hooks/report-copilot.sh $event")
                          }
                      }
                  }
              }
          }
          hookConfig.writeText(Json { prettyPrint = true }.encodeToString<JsonElement>(configJson))
      }
  }
  ```

  **Main.kt registration** — add `CopilotHookDeployer()` to the existing deployers list:
  ```kotlin
  val deployers: List<HookDeployer> = listOf(
      AgentPulseHookDeployer(),
      CopilotHookDeployer(),
  )
  ```

- [ ] **4.3 `AgentProvider` interface + `CopilotAgentProvider` implementation**

  **Interface change — `resolveSessionId`:**
  Session ID resolution is provider-specific (Copilot uses lock files; other agents use different
  mechanisms or none). Add it to the `AgentProvider` interface with a default null implementation.
  The override lives inside the provider class that uses it — no separate resolver file.

  ```kotlin
  // In AgentProvider.kt — add to the interface
  fun resolveSessionId(pid: Int): String? = null   // default: not supported by this provider
  ```

  **`CopilotAgentProvider.kt` — abstract base with Copilot session logic:**

  Create a new file `src/main/kotlin/com/agentpulse/provider/CopilotAgentProvider.kt`:

  ```kotlin
  // File: src/main/kotlin/com/agentpulse/provider/CopilotAgentProvider.kt
  package com.agentpulse.provider

  import com.agentpulse.model.AgentState
  import com.agentpulse.model.AgentStatus
  import com.agentpulse.model.CopilotPayload
  import com.agentpulse.model.HookEvent
  import com.agentpulse.model.HookEventType
  import com.agentpulse.util.agentConfigDir
  import java.nio.file.Path
  import kotlin.io.path.exists
  import kotlin.io.path.isDirectory
  import kotlin.io.path.listDirectoryEntries

  abstract class CopilotAgentProvider : AgentProvider {

      // Copilot-specific: scan $COPILOT_HOME/session-state/ for inuse.<PID>.lock files.
      // Wrapped in try/catch — filesystem errors (permissions, race conditions) must NOT
      // prevent the event from being processed. Returns null on any error (session ID
      // unknown is acceptable; losing the event is not).
      override fun resolveSessionId(pid: Int): String? = try {
          val sessionStateDir = agentConfigDir("COPILOT_HOME", ".copilot").resolve("session-state")
          if (!sessionStateDir.exists()) null
          else sessionStateDir.listDirectoryEntries().firstOrNull { dir ->
              dir.isDirectory() && dir.listDirectoryEntries("inuse.$pid.lock").isNotEmpty()
          }?.fileName?.toString()
      } catch (e: Exception) {
          System.err.println("[agent-pulse] Failed to resolve Copilot session ID for PID $pid: ${e.message}")
          null
      }

      override fun reconcileAgentState(event: HookEvent, currentState: AgentState?): AgentState {
          val p = event.payload as? CopilotPayload ?: return fallbackState(event)
          val sessionId = resolveSessionId(event.pid)
          return when (event.eventType) {
              HookEventType.SessionStart -> AgentState(
                  id = "${agentType.name}_${sessionId ?: event.pid}",
                  name = "${agentType.displayName} — ${sessionId?.take(8) ?: "PID ${event.pid}"}",
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
              HookEventType.SubagentStart -> currentState?.copy(
                  eventCount = currentState.eventCount + 1,
                  lastActivity = event.timestamp,
                  extra = currentState.extra + mapOf(
                      "subagents" to ((currentState.extra["subagents"]?.toIntOrNull() ?: 0) + 1).toString()
                  ),
              ) ?: fallbackState(event)
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
              name = "${agentType.displayName} — ${sessionId?.take(8) ?: "PID ${event.pid}"}",
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

  **Modify the three existing Copilot provider stubs to extend `CopilotAgentProvider`:**

  The files `CopilotCliProvider.kt`, `CopilotVsCodeProvider.kt`, and `CopilotIntelliJProvider.kt`
  already exist in `src/main/kotlin/com/agentpulse/provider/` as stubs that directly implement
  `AgentProvider`. Replace each file's content to extend `CopilotAgentProvider()` instead — they
  inherit `reconcileAgentState()`, `resolveSessionId()`, and `fallbackState()` from the abstract base.
  Remove the old stub `reconcileAgentState()` override and imports that are no longer needed.

  ```kotlin
  // File: src/main/kotlin/com/agentpulse/provider/CopilotCliProvider.kt
  package com.agentpulse.provider

  import com.agentpulse.model.AgentType

  class CopilotCliProvider : CopilotAgentProvider() {
      override val agentType = AgentType.CopilotCli
  }
  ```

  ```kotlin
  // File: src/main/kotlin/com/agentpulse/provider/CopilotVsCodeProvider.kt
  package com.agentpulse.provider

  import com.agentpulse.model.AgentType

  class CopilotVsCodeProvider : CopilotAgentProvider() {
      override val agentType = AgentType.CopilotVsCode
  }
  ```

  ```kotlin
  // File: src/main/kotlin/com/agentpulse/provider/CopilotIntelliJProvider.kt
  package com.agentpulse.provider

  import com.agentpulse.model.AgentType

  class CopilotIntelliJProvider : CopilotAgentProvider() {
      override val agentType = AgentType.CopilotIntelliJ
  }
  ```

  **Main.kt registration** — `CopilotCliProvider()` is already in the providers list. Add the
  other two (`CopilotVsCodeProvider()` and `CopilotIntelliJProvider()`) that exist as files but
  are not yet registered. Group all three Copilot variants into a named list first, then combine
  with the other providers:

  ```kotlin
  // In Main.kt — group Copilot variants, then combine with other providers
  val copilotProviders = listOf(
      CopilotCliProvider(),
      CopilotVsCodeProvider(),
      CopilotIntelliJProvider(),
  )
  val providers = copilotProviders + listOf(
      ClaudeCodeProvider(),
      CursorProvider(),
      CodexProvider(),
      GeminiProvider(),
  )
  ```

  > **Code changes also required in `HookEventType.kt`:**
  > - Add `SubagentStart` (Copilot 1.0.7+) — fires in parent when sub-agent spawned.
  >   **Note:** `SubagentStart` is a different event from the existing `SubagentStop`. The hook
  >   fires `subagentStart` (camelCase) — `fromRaw()` does case-insensitive matching so the
  >   PascalCase enum `SubagentStart` resolves correctly. Add it in the "Agent completion" section,
  >   next to `SubagentStop`, with comment: `/** Subagent spawned. Agents: Copilot (subagentStart, 1.0.7+). */`
  > - Add `PostToolUseFailure` (Copilot 1.0.15+) — fires when a tool call fails; `PostToolUse` now fires for successful calls only since 1.0.15.
  >   Add it in the "Tool execution" section, after `PostToolUse`, with comment: `/** Tool invocation failed. Agents: Copilot (postToolUseFailure, 1.0.15+). */`
  > - Add `PermissionRequest` (Copilot 1.0.16+) — fires on permission prompts.
  >   Add it in the "Copilot CLI specific" section, after `ErrorOccurred`, with comment: `/** Permission prompt shown. Agents: Copilot (permissionRequest, 1.0.16+). */`
  > - Update `Notification` comment from `/** User notification triggered. Raw: 'Notification'. */` to:
  >   `/** Notification triggered. Agents: Claude (Notification), Copilot (notification, 1.0.18+, async — fires on agent completion). */`

  **Hardening: `AgentSessionManager.onEvent()` error isolation** (per shared-context.md §Adapter Error Isolation):

  The current `onEvent()` calls `provider.reconcileAgentState()` with no error handling. A provider
  exception (ClassCastException, IOException, NPE from malformed payload) would propagate to the
  FileWatcher's catch block. Add `runCatching` so a single broken provider never crashes the event loop:

  ```kotlin
  // In AgentSessionManager.kt — replace the direct call with runCatching
  fun onEvent(event: HookEvent) {
      val provider = providerMap[event.agent] ?: return
      val currentAgentStates = _mutableAgentList.value
      val existingState = currentAgentStates.find { it.agentType == event.agent && it.pid == event.pid }

      val newState = runCatching {
          provider.reconcileAgentState(event, existingState)
      }.getOrElse { e ->
          System.err.println("[agent-pulse] ${event.agent.name} provider failed on ${event.eventType}: ${e.message}")
          return  // keep previous state, don't crash
      }

      _mutableAgentList.value = if (existingState != null) {
          currentAgentStates.map { if (it.id == newState.id) newState else it }
      } else {
          currentAgentStates + newState
      }
  }
  ```

- [ ] **4.4 Verify full stack**
    - Run `./gradlew run` with hook deployed (HookDeployer fires on startup, overwrites the manual deploy)
    - Start a Copilot CLI session — `sessionStart` event should flow through to `AgentState`
    - Start a session from VS Code — event should arrive as `copilot-vscode`
    - Use a tool in any session — `postToolUse` event should update state
    - End a session — `sessionEnd` event should set status to `Stopped`
    - Terminal logs confirm correct `agentType` per client and `AgentState` is created/updated
    - *(Optional)* Run `copilot --autopilot`: `subagentStart` event fires in parent; tool hooks inside sub-agent do NOT fire (expected — CLI issue #2293)

- [ ] **4.5 Commit, push, and open PR**
  ```bash
  git checkout -b step-4-copilot-provider
  git add -A && git commit -m "feat: Copilot hook provider — CLI, VS Code, IntelliJ

  - Add agentConfigDir() utility (util/PathUtils.kt) for env-var-aware config paths
  - Bundle report-copilot.sh as classpath resource (src/main/resources/hooks/)
  - Deploy hook config to Copilot hooks dir (COPILOT_HOME or ~/.copilot)
  - resolveSessionId() in AgentProvider interface (default null); Copilot override scans session-state/
  - reconcileAgentState() + fallbackState() in CopilotAgentProvider abstract base class
  - Modify three existing Copilot provider stubs to extend CopilotAgentProvider
  - CopilotHookDeployer (implements HookDeployer interface) automates deployment on startup
  - Add SubagentStart, PostToolUseFailure, PermissionRequest to HookEventType
  - runCatching error isolation in AgentSessionManager.onEvent()

  Co-authored-by: Copilot <223556219+Copilot@users.noreply.github.com>"
  git push -u origin step-4-copilot-provider
  gh pr create --title "Step 4: Copilot hook provider (CLI, VS Code, IntelliJ)" \
    --body "PoC-verified hook wiring. report-copilot.sh with two-level PPID detection, agentConfigDir utility, resolveSessionId() in AgentProvider interface, CopilotAgentProvider abstract base, three existing provider stubs modified, HookEventType additions, runCatching error isolation." \
    --base main
  ```
