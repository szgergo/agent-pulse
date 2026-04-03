# Step 4: copilot — Full Copilot CLI provider

> **⚠️ READ `shared-context.md` FIRST** — it contains all design principles, architecture,
> SQLite safety rules, connection hygiene, tech stack, and project structure that apply to this step.
> Path: `planning/implementation/shared-context.md`

---


**Goal**: Detect all running Copilot CLI instances via lock files and session data. Also detect Copilot in VS Code and IntelliJ via process signatures.

**Pre-check**: Step 3 PR is merged. `./gradlew run` shows scanner output.

**App state AFTER this step**: If Copilot CLI sessions are running, terminal shows `[agent-pulse] Scan: N agents found` where N > 0. The provider reads lock files, workspace.yaml, config.json, events.jsonl (all read-only).

**Data sources** (all read-only, verified from live system — see `agent-research.md`):

| File | Format | Read Method | Key Data |
|---|---|---|---|
| `~/.copilot/session-state/<UUID>/inuse.<PID>.lock` | Text (PID) | `SafeFileReader.readText()` | Active session with PID |
| `~/.copilot/session-state/<UUID>/workspace.yaml` | YAML | `kaml` parser | CWD, git root, branch, summary, created_at |
| `~/.copilot/session-state/<UUID>/vscode.metadata.json` | JSON | `kotlinx.serialization` | VS Code workspace folder, first message |
| `~/.copilot/session-state/<UUID>/events.jsonl` | JSONL | Line-by-line JSON | Event counts, model changes, token usage, shutdown metrics |
| `~/.copilot/session-state/<UUID>/session.db` | SQLite | `ReadOnlyDb.query()` | Session metadata |
| `~/.copilot/config.json` | JSON | `kotlinx.serialization` | Model, user |

- [ ] **4.1 Implement CopilotCliProvider.scan()**
    - Iterate `~/.copilot/session-state/` subdirectories (UUID format)
    - For each dir, find `inuse.<PID>.lock` files → extract PID from filename
    - Cross-reference PID with `processes` list (skip stale locks where process is dead)
    - Read `workspace.yaml` for CWD, session summary
    - Read `config.json` for model name
    - Read `vscode.metadata.json` to detect VS Code sessions (set agentType = CopilotVsCode)
    - Detect IntelliJ Copilot via process path containing `github-copilot-intellij` (set agentType = CopilotIntelliJ)
    - Build fleet worker tree: find child processes of each Copilot PID

  ```kotlin
  // In CopilotCliProvider.kt — replace the stub scan() and enrich() methods

  @Serializable
  data class WorkspaceYaml(
      val id: String = "",
      val cwd: String = "",
      @SerialName("git_root") val gitRoot: String = "",
      val branch: String = "",
      val summary: String = "",
      @SerialName("created_at") val createdAt: String = "",
      @SerialName("updated_at") val updatedAt: String = "",
  )

  @Serializable
  data class VscodeMetadata(
      val workspaceFolder: WorkspaceFolder? = null,
      val firstUserMessage: String? = null,
  ) {
      @Serializable
      data class WorkspaceFolder(val folderPath: String = "", val timestamp: Long = 0)
  }

  override fun scan(processes: List<ProcessInfo>): List<Agent> {
      val sessionStateDir = home.resolve(".copilot/session-state")
      if (!sessionStateDir.exists()) return emptyList()

      val processPids = processes.associateBy { it.pid }
      val agents = mutableListOf<Agent>()

      sessionStateDir.listDirectoryEntries().filter { it.isDirectory() }.forEach { sessionDir ->
          // Find lock files: inuse.<PID>.lock
          val lockFiles = sessionDir.listDirectoryEntries("inuse.*.lock")
          for (lockFile in lockFiles) {
              val pid = lockFile.fileName.toString()
                  .removePrefix("inuse.").removeSuffix(".lock").toIntOrNull() ?: continue

              // Cross-reference: is this PID alive?
              val proc = processPids[pid]
              if (proc == null) continue // Stale lock — process is dead

              // Read workspace.yaml for session metadata
              val yamlFile = sessionDir.resolve("workspace.yaml")
              val workspace = SafeFileReader.readText(yamlFile)?.let { raw ->
                  try { Yaml.default.decodeFromString(WorkspaceYaml.serializer(), raw) }
                  catch (e: Exception) { null }
              }

              // Determine agent type: CLI, VS Code, or IntelliJ
              val agentType = when {
                  proc.commandLine.contains("github-copilot-intellij") -> AgentType.CopilotIntelliJ
                  sessionDir.resolve("vscode.metadata.json").exists() -> AgentType.CopilotVsCode
                  else -> AgentType.CopilotCli
              }

              // Build fleet workers (child processes of this Copilot PID)
              val children = processes.filter { it.parentPid == pid }.map { child ->
                  Agent.FleetWorker(
                      pid = child.pid,
                      name = child.name,
                      cpuPercent = child.cpuPercent,
                      memoryBytes = child.memoryBytes,
                  )
              }

              agents.add(Agent(
                  id = "${agentType.name}:${sessionDir.fileName}:$pid",
                  agentType = agentType,
                  status = AgentStatus.Running,
                  pid = pid,
                  sessionId = workspace?.id ?: sessionDir.fileName.toString(),
                  cwd = workspace?.cwd,
                  model = null, // Enriched in enrich()
                  summary = workspace?.summary,
                  startTime = proc.startTime,
                  cpuPercent = proc.cpuPercent,
                  memoryBytes = proc.memoryBytes,
                  children = children,
                  extra = mapOf(
                      "branch" to (workspace?.branch ?: ""),
                      "gitRoot" to (workspace?.gitRoot ?: ""),
                  ),
              ))
          }
      }
      return agents
  }
  ```

- [ ] **4.2 Implement CopilotCliProvider.enrich()**
    - Read `events.jsonl` (last 100 lines for performance): count event types, extract latest model from `session.model_change`, get shutdown metrics if available
    - Read `session.db` via `ReadOnlyDb` for additional metadata
    - Populate `extra` map with: resumeCount, compactionCount, eventCount, totalPremiumRequests, etc.
    - Estimate token usage: from `session.shutdown` events in events.jsonl, or count assistant messages × 1000 as fallback

  ```kotlin
  override fun enrich(agent: Agent): Agent {
      val sessionDir = home.resolve(".copilot/session-state/${agent.sessionId}")
      val eventsFile = sessionDir.resolve("events.jsonl")
      val extra = agent.extra.toMutableMap()
      var model = agent.model
      var tokenUsage: Long? = null

      // Read last 100 lines of events.jsonl for performance
      val recentEvents = SafeFileReader.readJsonlTail(eventsFile, 100)
      if (recentEvents.isNotEmpty()) {
          extra["eventCount"] = recentEvents.size.toString()

          for (event in recentEvents.reversed()) { // Newest first
              val type = event["type"]?.jsonPrimitive?.contentOrNull ?: continue
              when (type) {
                  "session.model_change" -> {
                      if (model == null) {
                          model = event["newModel"]?.jsonPrimitive?.contentOrNull
                              ?: event["model"]?.jsonPrimitive?.contentOrNull
                      }
                  }
                  "session.shutdown" -> {
                      event["totalPremiumRequests"]?.jsonPrimitive?.contentOrNull
                          ?.let { extra["totalPremiumRequests"] = it }
                      event["modelMetrics"]?.jsonObject?.let { metrics ->
                          val input = metrics["inputTokens"]?.jsonPrimitive?.longOrNull ?: 0
                          val output = metrics["outputTokens"]?.jsonPrimitive?.longOrNull ?: 0
                          tokenUsage = input + output
                      }
                  }
                  "session.resume" -> {
                      val count = (extra["resumeCount"]?.toIntOrNull() ?: 0) + 1
                      extra["resumeCount"] = count.toString()
                  }
                  "session.compaction_complete" -> {
                      val count = (extra["compactionCount"]?.toIntOrNull() ?: 0) + 1
                      extra["compactionCount"] = count.toString()
                  }
                  "session.start" -> {
                      if (model == null) {
                          model = event["selectedModel"]?.jsonPrimitive?.contentOrNull
                      }
                  }
              }
          }
      }

      // Fallback model from config.json
      if (model == null) {
          val configFile = home.resolve(".copilot/config.json")
          model = SafeFileReader.readText(configFile)?.let { raw ->
              try { Json.parseToJsonElement(raw).jsonObject["model"]?.jsonPrimitive?.contentOrNull }
              catch (e: Exception) { null }
          }
      }

      return agent.copy(
          model = model,
          tokenUsage = tokenUsage,
          extra = extra,
      )
  }
  ```

- [ ] **4.3 Verify detection**
    - Run `./gradlew run` with active Copilot CLI sessions
    - Terminal should show `[agent-pulse] Scan: N agents found`
    - Add temporary debug logging to verify agent fields are populated

- [ ] **4.4 Commit, push, and open PR**
