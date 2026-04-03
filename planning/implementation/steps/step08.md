# Step 8: codex-gemini — Codex + Gemini hook providers

> **⚠️ NEEDS REVISION FOR HOOKS+FILEWATCH MVP**
> Replace process scanning + file reading with hook-based monitoring:
> - Codex: set `notify` command in config to invoke report.sh. Parse thread-id from payload.
> - Gemini: deploy hooks via settings.json merge. Parse SessionStart/AfterTool events.
> - Remove rollout JSONL reading — deferred
> - Remove Gemini chat file reading — deferred

> **⚠️ READ `shared-context.md` FIRST** — it contains all design principles, architecture,
> SQLite safety rules, connection hygiene, tech stack, and project structure that apply to this step.
> Path: `planning/implementation/shared-context.md`

---


**Goal**: Detect Codex CLI and Gemini CLI sessions.

**Pre-check**: Step 7 PR is merged.

**Codex data sources** (read-only):

| File | Key Data |
|---|---|
| `~/.codex/sessions/YYYY/MM/DD/rollout-*.jsonl` | Session transcript |
| `~/.codex/history.jsonl` | Global history |
| `~/.codex/config.toml` | Model, sandbox policy |

**Gemini data sources** (read-only):

| File | Key Data |
|---|---|
| `~/.gemini/tmp/<project_hash>/chats/` | Chat histories |
| `~/.gemini/settings.json` | Model, MCP servers |

- [ ] **8.1 Implement CodexProvider** — process scan for `codex`/`codex-tui`, read rollout files, parse config.toml

  ```kotlin
  class CodexProvider : AgentProvider {
      private val home = Path.of(System.getProperty("user.home"))

      override val name = "Codex"
      override val agentType = AgentType.Codex
      override val watchDirs = listOf(home.resolve(".codex/sessions"))
      override val processNames = listOf("codex", "codex-tui", "codex-cli")

      override fun scan(processes: List<ProcessInfo>): List<Agent> {
          return processes.filter { proc ->
              processNames.any { proc.name.contains(it, ignoreCase = true) }
          }.map { proc ->
              Agent(
                  id = "Codex:${proc.pid}",
                  agentType = AgentType.Codex,
                  status = AgentStatus.Running,
                  pid = proc.pid,
                  sessionId = proc.pid.toString(),
                  startTime = proc.startTime,
                  cpuPercent = proc.cpuPercent,
                  memoryBytes = proc.memoryBytes,
              )
          }
      }

      override fun enrich(agent: Agent): Agent {
          val extra = agent.extra.toMutableMap()
          var model: String? = null

          // Read config.toml for model
          val configFile = home.resolve(".codex/config.toml")
          SafeFileReader.readText(configFile)?.lines()?.forEach { line ->
              if (line.trimStart().startsWith("model")) {
                  model = line.substringAfter("=").trim().removeSurrounding("\"")
              }
          }

          // Find latest rollout file for session data
          val sessionsDir = home.resolve(".codex/sessions")
          if (sessionsDir.exists()) {
              val latestRollout = sessionsDir.toFile().walkTopDown()
                  .filter { it.name.startsWith("rollout-") && it.name.endsWith(".jsonl") }
                  .maxByOrNull { it.lastModified() }
              latestRollout?.let { file ->
                  val lines = SafeFileReader.readJsonlTail(file.toPath(), 10)
                  extra["rolloutEvents"] = lines.size.toString()
              }
          }

          return agent.copy(model = model ?: agent.model, extra = extra)
      }
  }
  ```

- [ ] **8.2 Implement GeminiProvider** — process scan for `gemini`, read chat directories, parse settings.json

  ```kotlin
  class GeminiProvider : AgentProvider {
      private val home = Path.of(System.getProperty("user.home"))

      override val name = "Gemini"
      override val agentType = AgentType.Gemini
      override val watchDirs = listOf(home.resolve(".gemini/tmp"))
      override val processNames = listOf("gemini")

      override fun scan(processes: List<ProcessInfo>): List<Agent> {
          return processes.filter { proc ->
              proc.name.contains("gemini", ignoreCase = true)
          }.map { proc ->
              Agent(
                  id = "Gemini:${proc.pid}",
                  agentType = AgentType.Gemini,
                  status = AgentStatus.Running,
                  pid = proc.pid,
                  sessionId = proc.pid.toString(),
                  startTime = proc.startTime,
                  cpuPercent = proc.cpuPercent,
                  memoryBytes = proc.memoryBytes,
              )
          }
      }

      override fun enrich(agent: Agent): Agent {
          var model: String? = null

          // Read settings.json for model
          val settingsFile = home.resolve(".gemini/settings.json")
          SafeFileReader.readText(settingsFile)?.let { raw ->
              try {
                  val json = Json.parseToJsonElement(raw).jsonObject
                  model = json["model"]?.jsonPrimitive?.contentOrNull
              } catch (e: Exception) { /* ignore */ }
          }

          return agent.copy(model = model ?: agent.model)
      }
  }
  ```

- [ ] **8.3 Verify both**
- [ ] **8.4 Commit, push, and open PR**
