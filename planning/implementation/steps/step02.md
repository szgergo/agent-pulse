# Step 2: data-model — Core data model + provider system

> **⚠️ READ `shared-context.md` FIRST** — it contains all design principles, architecture,
> and project structure that apply to this step.
> Path: `planning/implementation/shared-context.md`

---

**Goal**: All Kotlin data classes, interfaces, and the provider system defined. Stub providers for all agent types. App compiles and runs.

**Pre-check**: Step 1 PR is merged. `git checkout main && git pull`. `./gradlew run` shows the tray app.

**App state AFTER this step**: Same visible behavior as Step 1. But now the code has: `AgentState`/`HookEvent` data classes, `AgentProvider` interface with single `gatherInformation()` method, `HookEventStore` (thread-safe event accumulator), `ProviderRegistry`, `SearchIndexer` interface, and stub providers for all 5 agent types. `./gradlew build` passes with zero warnings.

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

- [ ] **2.4 Create src/main/kotlin/com/agentpulse/model/HookEvent.kt**
  Parsed from hook event filename + file content. The filename encodes metadata:
  `<timestamp>-<agent>-<eventType>-<pid>.json`
  ```kotlin
  package com.agentpulse.model

  import kotlinx.serialization.json.JsonObject

  data class HookEvent(
      val agent: AgentType,
      val eventType: String,       // "sessionStart", "postToolUse", "sessionEnd", etc.
      val pid: Int,                // Agent PID (from $PPID in hook script)
      val timestamp: Long,         // Epoch seconds (from filename)
      val rawJson: JsonObject,     // Parsed file content — raw event payload from agent
  )
  ```

- [ ] **2.5 Create src/main/kotlin/com/agentpulse/model/AgentState.kt**
  Snapshot of an agent's current state, projected from hook events by the provider.
  ```kotlin
  package com.agentpulse.model

  data class AgentState(
      val id: String,                    // "{type}_{sessionId}" or "{type}_{pid}"
      val name: String,                  // "Copilot CLI — session a1b2c3d4"
      val agentType: AgentType,
      val status: AgentStatus,
      val pid: Int,
      val sessionId: String? = null,     // Agent-specific session identifier
      val cwd: String? = null,           // Working directory (if available from hook payload)
      val model: String? = null,         // Post-MVP enrichment
      val eventCount: Int = 0,           // Number of hook events received for this session
      val lastActivity: Long? = null,    // Epoch millis of most recent hook event
      val uptimeSecs: Long? = null,
      val tokenUsage: Long? = null,      // Post-MVP enrichment
      val extra: Map<String, String> = emptyMap(),
  )
  ```

- [ ] **2.6 Create src/main/kotlin/com/agentpulse/provider/AgentProvider.kt**
  Single-method interface. How data is gathered is an implementation concern — the caller
  only knows that this returns a list of agent states.
  ```kotlin
  package com.agentpulse.provider

  import com.agentpulse.model.AgentState
  import com.agentpulse.model.AgentType

  /**
   * Core provider interface. Each agent type implements this.
   *
   * The single [gatherInformation] method returns a snapshot of all known agents
   * of this type. Implementations internally access whatever data sources they need
   * (e.g., [HookEventStore]) via constructor injection.
   */
  interface AgentProvider {
      val agentType: AgentType

      /**
       * Gather current state of all agents of this type.
       * Returns one [AgentState] per active session/instance.
       */
      fun gatherInformation(): List<AgentState>
  }
  ```

- [ ] **2.7 Create src/main/kotlin/com/agentpulse/provider/HookEventStore.kt**
  Thread-safe event accumulator. FileWatcher (Step 3) writes events in,
  providers read them out via `gatherInformation()`.
  ```kotlin
  package com.agentpulse.provider

  import com.agentpulse.model.AgentType
  import com.agentpulse.model.HookEvent
  import java.util.concurrent.ConcurrentHashMap
  import java.util.concurrent.CopyOnWriteArrayList

  /**
   * Thread-safe in-memory store for hook events.
   * FileWatcher adds events; providers query by agent type.
   */
  class HookEventStore {
      private val events = ConcurrentHashMap<AgentType, CopyOnWriteArrayList<HookEvent>>()

      fun addEvent(event: HookEvent) {
          events.getOrPut(event.agent) { CopyOnWriteArrayList() }.add(event)
      }

      fun getEvents(agentType: AgentType): List<HookEvent> =
          events[agentType]?.toList() ?: emptyList()

      fun removeEvents(agentType: AgentType, predicate: (HookEvent) -> Boolean) {
          events[agentType]?.removeIf(predicate)
      }

      fun clear() {
          events.clear()
      }
  }
  ```

- [ ] **2.8 Create src/main/kotlin/com/agentpulse/provider/ProviderRegistry.kt**
  ```kotlin
  package com.agentpulse.provider

  import com.agentpulse.model.AgentState

  class ProviderRegistry {
      private val providers = mutableListOf<AgentProvider>()

      fun register(provider: AgentProvider) {
          providers.add(provider)
      }

      fun gatherAll(): List<AgentState> =
          providers.flatMap { it.gatherInformation() }
  }
  ```

- [ ] **2.9 Create src/main/kotlin/com/agentpulse/search/SearchIndexer.kt**
  ```kotlin
  package com.agentpulse.search

  import com.agentpulse.model.AgentState

  interface SearchIndexer {
      fun indexAgent(agent: AgentState)
      fun removeAgent(agentId: String)
      fun clearAll()
  }
  ```

- [ ] **2.10 Create src/main/kotlin/com/agentpulse/search/NoopIndexer.kt**
  ```kotlin
  package com.agentpulse.search

  import com.agentpulse.model.AgentState

  class NoopIndexer : SearchIndexer {
      override fun indexAgent(agent: AgentState) {}
      override fun removeAgent(agentId: String) {}
      override fun clearAll() {}
  }
  ```

- [ ] **2.11 Create stub providers** — one file per agent type in `src/main/kotlin/com/agentpulse/provider/`
  Each stub is injected with `HookEventStore` and returns empty results from `gatherInformation()`.
  Example for CopilotCliProvider:
  ```kotlin
  package com.agentpulse.provider

  import com.agentpulse.model.*

  class CopilotCliProvider(private val eventStore: HookEventStore) : AgentProvider {
      override val agentType = AgentType.CopilotCli
      override fun gatherInformation(): List<AgentState> = emptyList() // Implemented in Step 4
  }
  ```
  Create analogous stubs for:
    - `ClaudeCodeProvider(eventStore: HookEventStore)` — `AgentType.ClaudeCode`
    - `CursorProvider(eventStore: HookEventStore)` — `AgentType.CursorIde`
    - `CodexProvider(eventStore: HookEventStore)` — `AgentType.CodexCli`
    - `GeminiProvider(eventStore: HookEventStore)` — `AgentType.GeminiCli`

- [ ] **2.12 Verify compilation**
  ```bash
  cd <project-root> && ./gradlew build
  ```
  Must compile with zero errors. Then `./gradlew run` — app should launch unchanged.

- [ ] **2.13 Commit, push, and open PR**
  ```bash
  git checkout -b step-2-data-model
  git add -A && git commit -m "feat: data model, provider system, and hook event store

  - AgentState, HookEvent, AgentType, AgentStatus data classes
  - AgentProvider interface with single gatherInformation() method
  - HookEventStore: thread-safe event accumulator for hook events
  - ProviderRegistry for multi-provider dispatch
  - SearchIndexer interface + NoopIndexer
  - Stub providers for Copilot, Claude, Cursor, Codex, Gemini

  Co-authored-by: Copilot <223556219+Copilot@users.noreply.github.com>"
  git push -u origin step-2-data-model
  gh pr create --title "Step 2: Data model and provider system" \
    --body "Core types, AgentProvider interface with gatherInformation(), HookEventStore, ProviderRegistry, stub providers." \
    --base main
  ```
