# Step 2: data-model — Core data model + provider system

> **⚠️ NEEDS REVISION FOR HOOKS+FILEWATCH MVP**
> This step was designed for process scanning + file reading. Key changes needed:
> - Remove OSHI dependency (`com.github.oshi:oshi-core`)
> - Remove SQLite dependency (`org.xerial:sqlite-jdbc`) — deferred to post-MVP
> - Remove YAML dependency (`com.charleskorn.kaml:kaml`) — deferred to post-MVP
> - Replace `ProcessInfo` data class with `HookEvent` (parsed from filename + file content)
> - Replace `AgentProvider.scan(processes: List<ProcessInfo>)` with `AgentProvider.handleEvent(event: HookEvent)`
> - Remove `ReadOnlyDb`, `SafeFileReader`, `CachedFileState` utilities — deferred to post-MVP
> - Add `HookEvent` data class: agent, eventType, pid, timestamp, rawJson
> - Keep: `Agent`, `AgentType`, `AgentStatus`, `ProviderRegistry`, `SearchIndexer`

> **⚠️ READ `shared-context.md` FIRST** — it contains all design principles, architecture,
> SQLite safety rules, connection hygiene, tech stack, and project structure that apply to this step.
> Path: `planning/implementation/shared-context.md`

---


**Goal**: All Kotlin data classes, interfaces, and utilities defined. Stub providers for all agent types. App compiles and runs.

**Pre-check**: Step 1 PR is merged. `git checkout main && git pull`. `./gradlew run` shows the tray app.

**App state AFTER this step**: Same visible behavior as Step 1. But now the code has: Agent/ProcessInfo data classes, AgentProvider interface, ProviderRegistry, SearchIndexer interface, ReadOnlyDb utility (with busy_timeout + SQLITE_BUSY retry), SafeFileReader utility (with JSONL mid-write protection), CachedFileState (last-known-good cache), and stub providers for all 5 agent types. `./gradlew build` passes with zero warnings.

- [ ] **2.1 Add dependencies to build.gradle.kts**
  Add to the `dependencies` block:
  ```kotlin
  // Process scanning
  implementation("com.github.oshi:oshi-core:6.6.5")

  // SQLite (read-only access to agent databases)
  implementation("org.xerial:sqlite-jdbc:3.46.1.0")

  // JSON
  implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.7.3")

  // YAML (for Copilot workspace.yaml)
  implementation("com.charleskorn.kaml:kaml:0.61.0")

  // Coroutines
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

- [ ] **2.4 Create src/main/kotlin/com/agentpulse/model/ProcessInfo.kt**
  ```kotlin
  package com.agentpulse.model

  data class ProcessInfo(
      val pid: Int,
      val name: String,
      val commandLine: String,
      val exePath: String?,
      val cpuPercent: Double,
      val memoryBytes: Long,
      val parentPid: Int?,
      val startTime: Long, // epoch millis
  )
  ```

- [ ] **2.5 Create src/main/kotlin/com/agentpulse/model/Agent.kt**
  ```kotlin
  package com.agentpulse.model

  data class Agent(
      val id: String,                    // "{type}_{pid}" e.g. "copilot-cli_3251"
      val name: String,                  // "Copilot CLI — agent-pulse"
      val agentType: AgentType,
      val status: AgentStatus,
      val pid: Int,
      val model: String? = null,         // "claude-opus-4.6"
      val cwd: String? = null,           // "/Users/.../agent-pulse"
      val sessionId: String? = null,     // "a1b2c3d4-..."
      val uptimeSecs: Long? = null,
      val tokenUsage: Long? = null,
      val children: List<Agent> = emptyList(),
      val extra: Map<String, String> = emptyMap(),
  )
  ```

- [ ] **2.6 Create src/main/kotlin/com/agentpulse/provider/AgentProvider.kt**
  ```kotlin
  package com.agentpulse.provider

  import com.agentpulse.model.Agent
  import com.agentpulse.model.AgentType
  import com.agentpulse.model.ProcessInfo
  import java.nio.file.Path

  /**
   * Core plugin interface. Each agent type implements this.
   * ALL data access MUST be read-only. Providers MUST NOT write to any agent directory.
   */
  interface AgentProvider {
      val name: String
      val agentType: AgentType
      val watchDirs: List<Path>
      val processNames: List<String>

      /**
       * Given the current process list, detect agents of this type.
       * Read-only: scan processes and read files, never write.
       */
      fun scan(processes: List<ProcessInfo>): List<Agent>

      /**
       * Enrich an already-detected agent with additional metadata.
       * Read-only: read files/databases, never write.
       */
      fun enrich(agent: Agent): Agent
  }
  ```

- [ ] **2.7 Create src/main/kotlin/com/agentpulse/provider/ProviderRegistry.kt**
  ```kotlin
  package com.agentpulse.provider

  import com.agentpulse.model.Agent
  import com.agentpulse.model.ProcessInfo
  import java.nio.file.Path

  class ProviderRegistry {
      private val providers = mutableListOf<AgentProvider>()

      fun register(provider: AgentProvider) { providers.add(provider) }

      fun allWatchDirs(): List<Path> = providers.flatMap { it.watchDirs }
          .filter { it.toFile().exists() }

      fun allProcessNames(): List<String> = providers.flatMap { it.processNames }

      fun scanAll(processes: List<ProcessInfo>): List<Agent> =
          providers.flatMap { provider ->
              provider.scan(processes).map { provider.enrich(it) }
          }
  }
  ```

- [ ] **2.8 Create src/main/kotlin/com/agentpulse/util/ReadOnlyDb.kt**
  ```kotlin
  package com.agentpulse.util

  import java.nio.file.Path
  import java.sql.DriverManager
  import java.sql.ResultSet
  import kotlin.io.path.exists
  import kotlinx.coroutines.withTimeout

  // We intentionally do NOT use connection pooling (HikariCP, etc.) for agent database access.
  // At our poll frequency (every 2-5s), connection open/close overhead (~1-5ms) is negligible,
  // making pooling unnecessary complexity. Fresh connection per query keeps things simple:
  // each query is independent, error handling is straightforward, no lifecycle management needed.
  //
  // Do NOT add immutable=1 to the JDBC URL. Agent databases are actively written by their
  // owning processes. immutable=1 disables change detection and would return stale/corrupt data.
  //
  // TIMEOUT STRATEGY (defense-in-depth against holding WAL snapshots open):
  // 1. busy_timeout=3000 in JDBC URL — SQLite retries internally when DB is locked
  // 2. statement.queryTimeout = 5 — JDBC driver cancels queries after 5s
  // 3. withTimeout(10_000) — coroutine cancellation as outer safety net
  // 4. connection.use {} — guarantees close() even on exceptions/cancellation
  object ReadOnlyDb {

      init {
          DriverManager.setLoginTimeout(5) // 5 seconds max to open any connection
      }

      /**
       * Execute a read-only query against a SQLite database.
       * Returns empty list if the file doesn't exist, the database is busy, or any error occurs.
       * The busy_timeout=3000 handles the common case where an agent briefly holds an
       * exclusive lock during writes (SQLite delete journal mode, sub-100ms typical).
       */
      suspend fun <T> query(dbPath: Path, sql: String, mapper: (ResultSet) -> T): List<T> {
          if (!dbPath.exists()) return emptyList()
          return try {
              withTimeout(10_000) { // 10s absolute max for entire DB operation
                  val url = "jdbc:sqlite:file:${dbPath.toAbsolutePath()}?mode=ro&busy_timeout=3000"
                  DriverManager.getConnection(url).use { conn ->
                      conn.createStatement().execute("PRAGMA query_only = ON")
                      conn.createStatement().use { stmt ->
                          stmt.queryTimeout = 5 // 5s max per query
                          val rs = stmt.executeQuery(sql)
                          buildList { while (rs.next()) add(mapper(rs)) }
                      }
                  }
              }
          } catch (e: Exception) {
              // If SQLITE_BUSY after 3s timeout, retry once after 500ms
              if (e.message?.contains("SQLITE_BUSY") == true || e.message?.contains("database is locked") == true) {
                  try {
                      kotlinx.coroutines.delay(500)
                      withTimeout(10_000) {
                          val url = "jdbc:sqlite:file:${dbPath.toAbsolutePath()}?mode=ro&busy_timeout=3000"
                          DriverManager.getConnection(url).use { conn ->
                              conn.createStatement().execute("PRAGMA query_only = ON")
                              conn.createStatement().use { stmt ->
                                  stmt.queryTimeout = 5
                                  val rs = stmt.executeQuery(sql)
                                  buildList { while (rs.next()) add(mapper(rs)) }
                              }
                          }
                      }
                  } catch (retryEx: Exception) {
                      System.err.println("[agent-pulse] DB still busy after retry (${dbPath.fileName}): ${retryEx.message}")
                      emptyList()
                  }
              } else {
                  System.err.println("[agent-pulse] DB read error (${dbPath.fileName}): ${e.message}")
                  emptyList()
              }
          }
      }

      /** Convenience: query returning rows as Map<String, String>. Used by CachedFileState. */
      suspend fun queryMap(dbPath: Path, sql: String): List<Map<String, String>> =
          query(dbPath, sql) { rs ->
              val meta = rs.metaData
              (1..meta.columnCount).associate { meta.getColumnName(it) to (rs.getString(it) ?: "") }
          }

      /** Read a single string value from a key-value table. */
      suspend fun readKv(dbPath: Path, table: String, key: String): String? {
          val results = query(dbPath, "SELECT value FROM $table WHERE key = '$key' LIMIT 1") {
              it.getString("value")
          }
          return results.firstOrNull()
      }
  }
  ```

- [ ] **2.9 Create src/main/kotlin/com/agentpulse/util/SafeFileReader.kt**
  ```kotlin
  package com.agentpulse.util

  import java.nio.file.Path
  import kotlin.io.path.bufferedReader
  import kotlin.io.path.exists
  import kotlin.io.path.isReadable
  import kotlin.io.path.readLines
  import kotlin.io.path.readText
  import kotlinx.serialization.json.Json

  /**
   * Safe, read-only file access. Returns null on any error.
   * NEVER writes to files.
   *
   * Handles mid-write safety:
   * - readText/readLines: returns null if file is unreadable or mid-write causes IOException
   * - readJsonlSafe: uses BufferedReader.readLine() which only returns complete lines (terminated
   *   with \n), so partial last lines from an agent mid-write are never returned. Additionally
   *   validates each line as JSON, skipping any that fail to parse.
   * - readJsonWithRetry: retries once after 200ms if JSON parsing fails (handles non-atomic writes)
   */
  object SafeFileReader {

      fun readText(path: Path): String? = try {
          if (path.exists() && path.isReadable()) path.readText() else null
      } catch (e: Exception) { null }

      fun readLines(path: Path): List<String>? = try {
          if (path.exists() && path.isReadable()) path.readLines() else null
      } catch (e: Exception) { null }

      /**
       * Read a JSONL file safely while the agent may be appending to it.
       * Uses BufferedReader.readLine() which guarantees only complete lines
       * (terminated with \n) are returned — partial last lines are never included.
       * Additionally validates each line as JSON, skipping any malformed entries.
       */
      fun readJsonlSafe(path: Path): List<String> {
          if (!path.exists() || !path.isReadable()) return emptyList()
          return try {
              path.bufferedReader().useLines { lines ->
                  lines.filter { line ->
                      line.isNotBlank() && try {
                          Json.parseToJsonElement(line); true
                      } catch (e: Exception) { false }
                  }.toList()
              }
          } catch (e: Exception) { emptyList() }
      }

      /**
       * Read and parse a JSON/YAML config file with retry.
       * If parsing fails (file mid-write), waits 200ms and retries once.
       * Falls back to cached last-known-good value on repeated failure.
       */
      fun <T> readJsonWithRetry(path: Path, cache: MutableMap<Path, T>, parser: (String) -> T): T? {
          repeat(2) { attempt ->
              val content = readText(path) ?: return cache[path]
              try {
                  val result = parser(content)
                  cache[path] = result
                  return result
              } catch (e: Exception) {
                  if (attempt == 0) Thread.sleep(200)
              }
          }
          return cache[path]
      }
  }
  ```

- [ ] **2.10 Create src/main/kotlin/com/agentpulse/util/CachedFileState.kt**
  ```kotlin
  package com.agentpulse.util

  import java.nio.file.Path
  import java.util.concurrent.ConcurrentHashMap
  import kotlinx.serialization.json.JsonElement

  /**
   * Last-known-good cache for agent file reads.
   * Each provider owns a CachedFileState instance.
   *
   * When a file read succeeds, the result is cached.
   * When a file read fails (mid-write, locked, permissions), the cached value is returned.
   * This means the dashboard shows slightly stale data instead of blank data.
   */
  class CachedFileState {
      private val textCache = ConcurrentHashMap<Path, String>()
      private val jsonCache = ConcurrentHashMap<Path, JsonElement>()
      private val dbCache = ConcurrentHashMap<String, List<Map<String, String>>>()

      fun getText(path: Path): String? {
          val result = SafeFileReader.readText(path)
          if (result != null) textCache[path] = result
          return result ?: textCache[path]
      }

      fun getDbRows(dbPath: Path, sql: String): List<Map<String, String>> {
          val cacheKey = "${dbPath}::${sql}"
          val result = ReadOnlyDb.queryMap(dbPath, sql)
          if (result.isNotEmpty()) dbCache[cacheKey] = result
          return result.ifEmpty { dbCache[cacheKey] ?: emptyList() }
      }

      fun getJsonlLines(path: Path): List<String> {
          val result = SafeFileReader.readJsonlSafe(path)
          // JSONL cache uses textCache with a synthetic key
          if (result.isNotEmpty()) textCache[path] = result.joinToString("\n")
          return result.ifEmpty {
              textCache[path]?.split("\n")?.filter { it.isNotBlank() } ?: emptyList()
          }
      }
  }
  ```

- [ ] **2.11 Create src/main/kotlin/com/agentpulse/search/SearchIndexer.kt**
  ```kotlin
  package com.agentpulse.search

  import com.agentpulse.model.Agent

  interface SearchIndexer {
      fun indexAgent(agent: Agent)
      fun removeAgent(agentId: String)
      fun clearAll()
  }
  ```

- [ ] **2.12 Create src/main/kotlin/com/agentpulse/search/NoopIndexer.kt**
  ```kotlin
  package com.agentpulse.search

  import com.agentpulse.model.Agent

  class NoopIndexer : SearchIndexer {
      override fun indexAgent(agent: Agent) {}
      override fun removeAgent(agentId: String) {}
      override fun clearAll() {}
  }
  ```

- [ ] **2.13 Create stub providers** — one file per agent type in `src/main/kotlin/com/agentpulse/provider/`
  Each stub returns empty results from `scan()` and passes through from `enrich()`. Example for CopilotCliProvider:
  ```kotlin
  package com.agentpulse.provider

  import com.agentpulse.model.*
  import java.nio.file.Path

  class CopilotCliProvider : AgentProvider {
      private val home = Path.of(System.getProperty("user.home"))

      override val name = "Copilot CLI"
      override val agentType = AgentType.CopilotCli
      override val watchDirs = listOf(home.resolve(".copilot/session-state"))
      override val processNames = listOf("copilot")

      override fun scan(processes: List<ProcessInfo>): List<Agent> = emptyList() // Step 4
      override fun enrich(agent: Agent): Agent = agent // Step 4
  }
  ```
  Create analogous stubs for:
    - `ClaudeCodeProvider` — watchDirs: `~/.claude/`, processNames: `["claude"]`
    - `CursorProvider` — watchDirs: `[]` (dynamic, discovered via process), processNames: `["Cursor"]`
    - `CodexProvider` — watchDirs: `~/.codex/sessions/`, processNames: `["codex"]`
    - `GeminiProvider` — watchDirs: `~/.gemini/tmp/`, processNames: `["gemini"]`

- [ ] **2.14 Verify compilation**
  ```bash
  cd <project-root> && ./gradlew build
  ```
  Must compile with zero errors. Then `./gradlew run` — app should launch unchanged.

- [ ] **2.15 Commit, push, and open PR**
  ```bash
  git checkout -b step-2-data-model
  git add -A && git commit -m "feat: data model, provider system, and read-only utilities

  - Agent, ProcessInfo, AgentType, AgentStatus data classes
  - AgentProvider interface with read-only contract
  - ProviderRegistry for multi-provider dispatch
  - ReadOnlyDb: SQLite read-only with busy_timeout + SQLITE_BUSY retry
  - SafeFileReader: null-safe file reading with JSONL mid-write protection
  - CachedFileState: last-known-good cache for agent file reads
  - SearchIndexer interface + NoopIndexer
  - Stub providers for Copilot, Claude, Cursor, Codex, Gemini

  Co-authored-by: Copilot <223556219+Copilot@users.noreply.github.com>"
  git push -u origin step-2-data-model
  gh pr create --title "Step 2: Data model and provider system" \
    --body "Core types, AgentProvider interface, ProviderRegistry, read-only DB/file utilities, stub providers." \
    --base main
  ```
