# Step 6: claude — Claude Code provider (Tier 1: file-based)

> **⚠️ READ `shared-context.md` FIRST** — it contains all design principles, architecture,
> SQLite safety rules, connection hygiene, tech stack, and project structure that apply to this step.
> Path: `planning/implementation/shared-context.md`

---


**Goal**: Detect running Claude Code sessions via process scanning and file-based metadata.

**Pre-check**: Step 5 PR is merged.

**Data sources** (all read-only):

| File | Format | Key Data |
|---|---|---|
| Process: `claude` | OSHI | PID, CWD, CPU/memory |
| `~/.claude/projects/<hash>/memory/MEMORY.md` | Markdown | Project context/memory |
| `~/.claude/settings.json` | JSON | Configuration |
| `~/.claude/CLAUDE.md` | Markdown | Global instructions |
| `<project>/.claude/settings.json` | JSON | Project permissions, tool config |
| `~/.claude/debug/<uuid>.txt` | Text | Debug logs |

- [ ] **6.1 Implement ClaudeCodeProvider**
    - Process detection: scan for `claude` by name
    - For each matched process, extract CWD
    - Compute project hash from CWD to find `~/.claude/projects/<hash>/`
    - Read MEMORY.md for project description (first 200 chars as summary)
    - Read debug logs for activity timestamps
    - Detect VS Code parentage via process tree (parent = `Code Helper (Plugin)`)

  ```kotlin
  class ClaudeCodeProvider : AgentProvider {
      private val home = Path.of(System.getProperty("user.home"))

      override val name = "Claude Code"
      override val agentType = AgentType.ClaudeCode
      override val watchDirs = listOf(home.resolve(".claude"))
      override val processNames = listOf("claude", "claude-code")

      override fun scan(processes: List<ProcessInfo>): List<Agent> {
          return processes.filter { proc ->
              processNames.any { proc.name.contains(it, ignoreCase = true) }
          }.map { proc ->
              // Determine if this is a VS Code extension or standalone CLI
              val isVsCode = processes.any { parent ->
                  parent.pid == proc.parentPid &&
                  parent.name.contains("Code Helper", ignoreCase = true)
              }
              val type = if (isVsCode) AgentType.ClaudeCodeVsCode else AgentType.ClaudeCode

              // Try to find project directory via CWD
              val projectDir = findProjectDir(proc)
              val memory = projectDir?.resolve("memory/MEMORY.md")
                  ?.let { SafeFileReader.readText(it)?.take(200) }

              Agent(
                  id = "${type.name}:${proc.pid}",
                  agentType = type,
                  status = AgentStatus.Running,
                  pid = proc.pid,
                  sessionId = proc.pid.toString(),
                  cwd = null, // Claude doesn't expose CWD easily; use project dir if found
                  model = null, // Not available from files (available via OTel in Step 9)
                  summary = memory?.lines()?.firstOrNull()?.removePrefix("# ")?.trim(),
                  startTime = proc.startTime,
                  cpuPercent = proc.cpuPercent,
                  memoryBytes = proc.memoryBytes,
              )
          }
      }

      override fun enrich(agent: Agent): Agent {
          // Read latest debug log for activity timestamp
          val debugDir = home.resolve(".claude/debug")
          val latestDebug = SafeFileReader.listDirectory(debugDir)
              ?.maxByOrNull { it.fileName.toString() }
          val lastActivity = latestDebug?.let {
              try { java.nio.file.Files.getLastModifiedTime(it).toMillis() }
              catch (e: Exception) { null }
          }
          return if (lastActivity != null) {
              agent.copy(extra = agent.extra + ("lastActivity" to lastActivity.toString()))
          } else agent
      }

      private fun findProjectDir(proc: ProcessInfo): Path? {
          // Claude stores projects at ~/.claude/projects/<hash>/
          // The hash is derived from the project path; we scan all and match by recency
          val projectsDir = home.resolve(".claude/projects")
          if (!projectsDir.exists()) return null
          return try {
              projectsDir.listDirectoryEntries()
                  .filter { it.isDirectory() && it.resolve("memory/MEMORY.md").exists() }
                  .maxByOrNull { java.nio.file.Files.getLastModifiedTime(it).toMillis() }
          } catch (e: Exception) { null }
      }
  }
  ```

- [ ] **6.2 Verify** — Run with Claude Code active, verify detection

- [ ] **6.3 Commit, push, and open PR**
