# Step 7: cursor — Cursor hook provider

> **⚠️ NEEDS REVISION FOR HOOKS+FILEWATCH MVP**
> Replace process scanning + SQLite reading with hook-based monitoring:
> - Deploy Cursor hooks: `~/.cursor/hooks.json` (v1.7+ first-class hooks)
> - Parse sessionStart/afterFileEdit/postToolUse/beforeShellExecution events
> - Session ID from `conversation_id` in payload
> - Remove state.vscdb SQLite reading — deferred to post-MVP
> - Remove ai-code-tracking.db reading — deferred
> - Remove JSONL transcript reading — deferred

> **⚠️ READ `shared-context.md` FIRST** — it contains all design principles, architecture,
> SQLite safety rules, connection hygiene, tech stack, and project structure that apply to this step.
> Path: `planning/implementation/shared-context.md`

---


**Goal**: Detect Cursor sessions via process scanning and read rich metadata from Cursor's SQLite databases and JSONL transcripts.

**Pre-check**: Step 6 PR is merged.

**This provider reads the most databases — read-only safety is critical.**

**Data sources** (all read-only, verified from live Cursor installation):

| Source | Path | Format | Read Method |
|---|---|---|---|
| Process | `Cursor`, `Cursor Helper` | OSHI | Process scan |
| Workspace state | `~/Library/Application Support/Cursor/User/workspaceStorage/<hash>/state.vscdb` | SQLite | `ReadOnlyDb` |
| AI tracking | `~/.cursor/ai-tracking/ai-code-tracking.db` | SQLite | `ReadOnlyDb` |
| Agent transcripts | `~/.cursor/projects/<path-hash>/agent-transcripts/<composerId>/<composerId>.jsonl` | JSONL | `SafeFileReader.readJsonl()` |
| App logs | `~/Library/Application Support/Cursor/logs/<date>/main.log` | Text | `SafeFileReader.readLines()` (tail) |

- [ ] **7.1 Implement CursorProvider.scan()**
    - Detect Cursor process via OSHI (name: `Cursor`)
    - Enumerate workspace storage directories to find `state.vscdb` files
    - For each `state.vscdb`, read `composer.composerData` from `ItemTable` (key-value table)
    - Parse JSON blob to extract: composerId, name, unifiedMode, totalLinesAdded/Removed, filesChangedCount, createdAt, lastUpdatedAt, contextUsagePercent

  ```kotlin
  class CursorProvider : AgentProvider {
      private val home = Path.of(System.getProperty("user.home"))

      override val name = "Cursor"
      override val agentType = AgentType.Cursor
      override val watchDirs = emptyList<Path>() // Dynamic: discovered via process
      override val processNames = listOf("Cursor")

      override fun scan(processes: List<ProcessInfo>): List<Agent> {
          val cursorProcs = processes.filter { it.name.equals("Cursor", ignoreCase = true) }
          if (cursorProcs.isEmpty()) return emptyList()

          val mainProc = cursorProcs.firstOrNull() ?: return emptyList()
          val agents = mutableListOf<Agent>()

          // Enumerate workspace storage directories
          val wsStorageDir = home.resolve("Library/Application Support/Cursor/User/workspaceStorage")
          if (!wsStorageDir.exists()) return listOf(processOnlyAgent(mainProc))

          wsStorageDir.listDirectoryEntries().filter { it.isDirectory() }.forEach { wsDir ->
              val stateDb = wsDir.resolve("state.vscdb")
              if (!stateDb.exists()) return@forEach

              // Read composer data from ItemTable key-value store
              val composerJson = runBlocking {
                  ReadOnlyDb.readKv(stateDb, "ItemTable", "composer.composerData")
              } ?: return@forEach

              try {
                  val root = Json.parseToJsonElement(composerJson).jsonObject
                  val composers = root["allComposers"]?.jsonArray ?: return@forEach

                  for (composer in composers) {
                      val obj = composer.jsonObject
                      val isArchived = obj["isArchived"]?.jsonPrimitive?.booleanOrNull ?: false
                      if (isArchived) continue

                      val composerId = obj["composerId"]?.jsonPrimitive?.contentOrNull ?: continue
                      val cName = obj["name"]?.jsonPrimitive?.contentOrNull ?: "Cursor session"
                      val mode = obj["unifiedMode"]?.jsonPrimitive?.contentOrNull ?: "chat"
                      val linesAdded = obj["totalLinesAdded"]?.jsonPrimitive?.longOrNull ?: 0
                      val linesRemoved = obj["totalLinesRemoved"]?.jsonPrimitive?.longOrNull ?: 0
                      val createdAt = obj["createdAt"]?.jsonPrimitive?.longOrNull ?: 0

                      agents.add(Agent(
                          id = "Cursor:$composerId",
                          agentType = AgentType.Cursor,
                          status = AgentStatus.Running,
                          pid = mainProc.pid,
                          sessionId = composerId,
                          cwd = null,
                          model = null,
                          summary = "$cName ($mode)",
                          startTime = createdAt,
                          cpuPercent = mainProc.cpuPercent,
                          memoryBytes = mainProc.memoryBytes,
                          extra = mapOf(
                              "mode" to mode,
                              "linesAdded" to linesAdded.toString(),
                              "linesRemoved" to linesRemoved.toString(),
                              "contextUsage" to (obj["contextUsagePercent"]?.jsonPrimitive?.contentOrNull ?: ""),
                          ),
                      ))
                  }
              } catch (e: Exception) {
                  System.err.println("[agent-pulse] Cursor composer parse error: ${e.message}")
              }
          }
          return agents.ifEmpty { listOf(processOnlyAgent(mainProc)) }
      }

      override fun enrich(agent: Agent): Agent {
          // Read ai-code-tracking.db for conversation summaries
          val trackingDb = home.resolve(".cursor/ai-tracking/ai-code-tracking.db")
          if (!trackingDb.exists()) return agent

          val summaries = runBlocking {
              ReadOnlyDb.query(trackingDb,
                  "SELECT title, tldr, model FROM conversation_summaries WHERE conversationId = '${agent.sessionId}' LIMIT 1"
              ) { rs ->
                  Triple(rs.getString("title"), rs.getString("tldr"), rs.getString("model"))
              }
          }
          val (title, tldr, model) = summaries.firstOrNull() ?: return agent
          return agent.copy(
              model = model.takeIf { it.isNotBlank() } ?: agent.model,
              extra = agent.extra + mapOf(
                  "title" to (title ?: ""),
                  "tldr" to (tldr ?: ""),
              ),
          )
      }

      private fun processOnlyAgent(proc: ProcessInfo) = Agent(
          id = "Cursor:${proc.pid}",
          agentType = AgentType.Cursor,
          status = AgentStatus.Running,
          pid = proc.pid,
          sessionId = proc.pid.toString(),
          startTime = proc.startTime,
          cpuPercent = proc.cpuPercent,
          memoryBytes = proc.memoryBytes,
      )
  }
  ```

- [ ] **7.2 Implement CursorProvider.enrich()** — already included above
    - Read `ai-code-tracking.db` for conversation summaries and scored commits
    - Read agent transcript JSONL for conversation history summary (count messages, last user message)
    - **All reads use ReadOnlyDb or SafeFileReader — never write**

- [ ] **7.3 Verify** — Run with Cursor sessions, verify detection

- [ ] **7.4 Commit, push, and open PR**
