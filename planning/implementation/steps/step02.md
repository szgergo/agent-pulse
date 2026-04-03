# Step 2: data-model — Core data model + provider system

> **⚠️ READ `shared-context.md` FIRST** — it contains all design principles, architecture,
> and project structure that apply to this step.
> Path: `planning/implementation/shared-context.md`

---

**Goal**: All Kotlin data classes, interfaces, and the provider system defined. Stub providers for all agent types. App compiles and runs.

**Pre-check**: Step 1 PR is merged. `git checkout main && git pull`. `./gradlew run` shows the tray app.

**App state AFTER this step**: Same visible behavior as Step 1. But now the code has: `AgentState`/`HookEvent` data classes, `AgentProvider` interface with `processEvent()` method, `AgentStateManager` (central state holder with `StateFlow`), `SearchIndexer` interface, and stub providers for all 5 agent types. `./gradlew build` passes with zero warnings.

---

- [ ] **2.1 Add dependencies to build.gradle.kts**
  Add to the `dependencies` block:
  ```kotlin
  // JSON parsing (hook event payloads)
  implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.7.3")

  // Coroutines (background FileWatch, state flow)
  implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.9.0")
  implementation("org.jetbrains.kotlinx:kotlinx-coroutines-swing:1.9.0")
  ```
  Add serialization plugin:
  ```kotlin
  plugins {
      // ... existing plugins
      kotlin("plugin.serialization") version "2.0.21"
  }
  ```
  **Note**: No OSHI, no sqlite-jdbc, no kaml. The MVP uses hooks+FileWatch only.

- [ ] **2.2 Create src/main/kotlin/com/agentpulse/model/AgentType.kt**
  ```kotlin
  package com.agentpulse.model

  enum class AgentType(val displayName: String, val icon: String) {
      CopilotCli("Copilot CLI", "🤖"),
      CopilotVsCode("Copilot (VS Code)", "🤖"),
      CopilotIntelliJ("Copilot (IntelliJ)", "🤖"),
      ClaudeCode("Claude Code", "🧠"),
      CursorIde("Cursor", "⚡"),
      CodexCli("Codex CLI", "📦"),
      GeminiCli("Gemini CLI", "💎"),
  }
  ```

- [ ] **2.3 Create src/main/kotlin/com/agentpulse/model/AgentStatus.kt**
  ```kotlin
  package com.agentpulse.model

  enum class AgentStatus { Running, Idle, Stopped, Error }
  ```

- [ ] **2.4 Create src/main/kotlin/com/agentpulse/model/HookPayload.kt**
  Typed payload classes — one per agent. Parsed from hook event file content at the
  watcher boundary (Step 3). From this point on, all code works with typed Kotlin
  objects — no raw JSON access.
  ```kotlin
  package com.agentpulse.model

  import kotlinx.serialization.SerialName
  import kotlinx.serialization.Serializable

  /**
   * Typed hook event payload. Each agent has its own data class reflecting
   * the JSON schema of its hook events. Parsed in HookEventWatcher (Step 3).
   */
  @Serializable
  sealed interface HookPayload {
      val cwd: String?
  }

  @Serializable
  data class CopilotPayload(
      override val cwd: String? = null,
      val toolName: String? = null,
      val prompt: String? = null,
      val errorMessage: String? = null,
  ) : HookPayload

  @Serializable
  data class ClaudePayload(
      override val cwd: String? = null,
      @SerialName("tool_name") val toolName: String? = null,
  ) : HookPayload

  @Serializable
  data class CursorPayload(
      override val cwd: String? = null,
      @SerialName("conversation_id") val conversationId: String? = null,
      @SerialName("file_path") val filePath: String? = null,
  ) : HookPayload

  @Serializable
  data class CodexPayload(
      override val cwd: String? = null,
      @SerialName("thread_id") val threadId: String? = null,
  ) : HookPayload

  @Serializable
  data class GeminiPayload(
      override val cwd: String? = null,
      val toolName: String? = null,
  ) : HookPayload
  ```

- [ ] **2.5 Create src/main/kotlin/com/agentpulse/model/HookEventType.kt**
  Typed enum of all documented hook event types across all supported agents.
  Use `fromRaw(String)` to map the raw filename token to a typed constant; falls back
  to `Unknown` for unrecognised values.
  ```kotlin
  package com.agentpulse.model

  enum class HookEventType(vararg val rawValues: String) {
      // Lifecycle — shared across Copilot CLI, Claude Code, Gemini CLI, Cursor
      SessionStart("sessionStart", "SessionStart"),
      SessionEnd("sessionEnd", "SessionEnd"),
      // Tool execution — Copilot CLI, Claude Code, Cursor
      PreToolUse("preToolUse", "PreToolUse"),
      PostToolUse("postToolUse", "PostToolUse"),
      // Agent completion — Copilot CLI, Claude Code, Cursor
      Stop("stop", "Stop"),
      SubagentStop("subagentStop", "SubagentStop"),
      // Context management — Claude Code, Cursor
      PreCompact("preCompact", "PreCompact"),
      // Copilot CLI specific
      UserPromptSubmitted("userPromptSubmitted"),
      AgentStop("agentStop"),
      ErrorOccurred("errorOccurred"),
      // Claude Code specific
      UserPromptSubmit("UserPromptSubmit"),
      Notification("Notification"),
      // Gemini CLI specific
      BeforeTool("BeforeTool"),
      AfterTool("AfterTool"),
      BeforeAgent("BeforeAgent"),
      AfterAgent("AfterAgent"),
      BeforeModel("BeforeModel"),
      AfterModel("AfterModel"),
      // Cursor specific
      BeforeShellExecution("beforeShellExecution"),
      AfterShellExecution("afterShellExecution"),
      AfterFileEdit("afterFileEdit"),
      BeforeSubmitPrompt("beforeSubmitPrompt"),
      BeforeMcpExecution("beforeMCPExecution"),
      AfterMcpExecution("afterMCPExecution"),
      // Codex CLI specific
      Notify("notify"),
      // Fallback
      Unknown("unknown");

      companion object {
          private val index: Map<String, HookEventType> =
              entries.flatMap { type -> type.rawValues.map { raw -> raw to type } }.toMap()

          fun fromRaw(raw: String): HookEventType =
              index[raw] ?: entries.firstOrNull { type ->
                  type.rawValues.any { it.equals(raw, ignoreCase = true) }
              } ?: Unknown
      }
  }
  ```

- [ ] **2.6 Create src/main/kotlin/com/agentpulse/model/HookEvent.kt**
  Parsed from hook event filename + typed payload. The filename encodes metadata:
  `<timestamp>-<agent>-<eventType>-<pid>.json`
  ```kotlin
  package com.agentpulse.model

  data class HookEvent(
      val agent: AgentType,
      val eventType: HookEventType,    // Typed event — resolved from hook filename or payload
      val pid: Int,                    // Agent PID (from $PPID in hook script)
      val timestamp: Long,             // Epoch seconds (from filename)
      val payload: HookPayload,        // Typed payload — parsed at watcher boundary
  )
  ```

- [ ] **2.7 Create src/main/kotlin/com/agentpulse/model/AgentState.kt**
  Snapshot of an agent's current state, projected from hook events by the provider.
  ```kotlin
  package com.agentpulse.model

  import java.nio.file.Path

  data class AgentState(
      val id: String,                    // "{type}_{sessionId}" or "{type}_{pid}"
      val name: String,                  // "Copilot CLI — session a1b2c3d4"
      val agentType: AgentType,
      val status: AgentStatus,
      val pid: Int,
      val sessionId: String? = null,     // Agent-specific session identifier
      val cwd: Path? = null,             // Working directory (if available from hook payload)
      val model: String? = null,         // Post-MVP enrichment
      val eventCount: Int = 0,           // Number of hook events received for this session
      val lastActivity: Long? = null,    // Epoch millis of most recent hook event
      val uptimeSecs: Long? = null,
      val tokenUsage: Long? = null,      // Post-MVP enrichment
      val extra: Map<String, String> = emptyMap(),
  )
  ```

- [ ] **2.8 Create src/main/kotlin/com/agentpulse/provider/AgentProvider.kt**
  Pure event processor. Each implementation knows how to translate its agent's hook JSON
  into the universal `AgentState`. The provider is stateless — state is held by `AgentStateManager`.
  ```kotlin
  package com.agentpulse.provider

  import com.agentpulse.model.AgentState
  import com.agentpulse.model.AgentType
  import com.agentpulse.model.HookEvent

  /**
   * Translates agent-specific hook events into universal [AgentState].
   *
   * Each implementation knows how to interpret its agent's JSON payload —
   * extracting session IDs, status, working directory, etc.
   *
   * Providers are pure functions: given an event and optional current state,
   * they return an updated state. State management is handled by [AgentStateManager].
   */
  interface AgentProvider {
      val agentType: AgentType

      /**
       * Process a hook event and return the updated agent state.
       *
       * @param event The hook event to process.
       * @param currentState The current state for this session, or null if this is the first event.
       * @return Updated [AgentState] reflecting the new event.
       */
      fun processEvent(event: HookEvent, currentState: AgentState?): AgentState
  }
  ```

- [ ] **2.9 Create src/main/kotlin/com/agentpulse/provider/AgentStateManager.kt**
  Central state holder. Routes hook events to providers, maintains the reactive state
  that Compose UI observes. Replaces both `HookEventStore` and `ProviderRegistry`.
  ```kotlin
  package com.agentpulse.provider

  import com.agentpulse.model.AgentState
  import com.agentpulse.model.AgentType
  import com.agentpulse.model.HookEvent
  import kotlinx.coroutines.flow.MutableStateFlow
  import kotlinx.coroutines.flow.StateFlow
  import kotlinx.coroutines.flow.asStateFlow

  /**
   * Central state holder for all agent sessions.
   *
   * Receives [HookEvent]s (pushed by FileWatcher), routes them to the appropriate
   * [AgentProvider], and updates the reactive [agents] StateFlow that Compose UI observes.
   */
  class AgentStateManager(
      providers: List<AgentProvider>,
  ) {
      private val providerMap: Map<AgentType, AgentProvider> =
          providers.associateBy { it.agentType }

      private val _agents = MutableStateFlow<List<AgentState>>(emptyList())
      val agents: StateFlow<List<AgentState>> = _agents.asStateFlow()

      /**
       * Process an incoming hook event. Called by FileWatcher on each new event file.
       * Routes to the appropriate provider, updates the state flow.
       */
      fun onEvent(event: HookEvent) {
          val provider = providerMap[event.agent] ?: return
          val current = _agents.value
          val existingState = current.find { it.agentType == event.agent && it.pid == event.pid }
          val newState = provider.processEvent(event, existingState)

          _agents.value = if (existingState != null) {
              current.map { if (it.id == newState.id) newState else it }
          } else {
              current + newState
          }
      }
  }
  ```

- [ ] **2.10 Create src/main/kotlin/com/agentpulse/search/SearchIndexer.kt**
  ```kotlin
  package com.agentpulse.search

  import com.agentpulse.model.AgentState

  interface SearchIndexer {
      fun indexAgent(agent: AgentState)
      fun removeAgent(agentId: String)
      fun clearAll()
  }
  ```

- [ ] **2.11 Create src/main/kotlin/com/agentpulse/search/NoopIndexer.kt**
  ```kotlin
  package com.agentpulse.search

  import com.agentpulse.model.AgentState

  class NoopIndexer : SearchIndexer {
      override fun indexAgent(agent: AgentState) {}
      override fun removeAgent(agentId: String) {}
      override fun clearAll() {}
  }
  ```

- [ ] **2.12 Create stub providers** — one file per agent type in `src/main/kotlin/com/agentpulse/provider/`
  Each stub returns a minimal `AgentState` from `processEvent()`.
  Example for CopilotCliProvider:
  ```kotlin
  package com.agentpulse.provider

  import com.agentpulse.model.*

  class CopilotCliProvider : AgentProvider {
      override val agentType = AgentType.CopilotCli
      override fun processEvent(event: HookEvent, currentState: AgentState?): AgentState {
          // Stub — real implementation in Step 4
          return currentState?.copy(
              eventCount = currentState.eventCount + 1,
              lastActivity = event.timestamp * 1000,
          ) ?: AgentState(
              id = "${agentType.name}_${event.pid}",
              name = "${agentType.displayName} — PID ${event.pid}",
              agentType = agentType,
              status = AgentStatus.Running,
              pid = event.pid,
              eventCount = 1,
              lastActivity = event.timestamp * 1000,
          )
      }
  }
  ```
  Create analogous stubs for:
    - `ClaudeCodeProvider` — `AgentType.ClaudeCode`
    - `CursorProvider` — `AgentType.CursorIde`
    - `CodexProvider` — `AgentType.CodexCli`
    - `GeminiProvider` — `AgentType.GeminiCli`

- [ ] **2.13 Verify compilation**
  ```bash
  cd <project-root> && ./gradlew build
  ```
  Must compile with zero errors. Then `./gradlew run` — app should launch unchanged.

- [ ] **2.14 Commit, push, and open PR**
  ```bash
  git checkout -b step-2-data-model
  git add -A && git commit -m "feat: data model, provider system, and state manager

  - AgentState, HookEvent, AgentType, AgentStatus data classes
  - AgentProvider interface with processEvent(event, currentState?) → AgentState
  - AgentStateManager: central state holder with StateFlow, routes events to providers
  - SearchIndexer interface + NoopIndexer
  - Stub providers for Copilot, Claude, Cursor, Codex, Gemini

  Co-authored-by: Copilot <223556219+Copilot@users.noreply.github.com>"
  git push -u origin step-2-data-model
  gh pr create --title "Step 2: Data model and provider system" \
    --body "Core types, AgentProvider with processEvent(), AgentStateManager with StateFlow, stub providers." \
    --base main
  ```
