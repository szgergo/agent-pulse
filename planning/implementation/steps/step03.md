# Step 3: watcher — Hook event watcher + deployer

> **⚠️ READ `shared-context.md` FIRST** — it contains all design principles, architecture,
> and project structure that apply to this step.
> Path: `planning/implementation/shared-context.md`
>
> 📚 **Further reading**: Hook safety analysis, delivery mechanism comparison, and per-agent hook schemas in [`research/agent-research.md`](../../research/agent-research.md)

---

**Goal**: Background event watcher that monitors `~/.agent-pulse/events/` for hook event files, parses them into `HookEvent` objects, and feeds them to `AgentStateManager`. Plus a first-run hook deployer that creates the shared hook infrastructure (`report.sh`, events directory).

**Pre-check**: Step 2 PR is merged. `./gradlew build` passes.

**App state AFTER this step**: On launch, terminal shows `[agent-pulse] Watching: ~/.agent-pulse/events/`. Hook deployer creates `~/.agent-pulse/hooks/report.sh` and the events directory. When a hooked agent fires events, terminal shows `[agent-pulse] Event: copilot-cli/sessionStart (PID 12345)`. UI still shows placeholder — agent display comes in Step 5.

**Files to create**:
- `src/main/kotlin/com/agentpulse/watcher/HookEventWatcher.kt`
- `src/main/kotlin/com/agentpulse/deploy/HookDeployer.kt`

**Data flow** (for reference):
```
Agent hook fires → report.sh writes file to ~/.agent-pulse/events/
  → HookEventWatcher detects ENTRY_CREATE via WatchService
  → Parses filename: <timestamp>-<agent>-<eventType>-<ppid>.json
  → Reads file content and deserializes to typed HookPayload based on agent
  → Creates HookEvent(agent, eventType, pid, timestamp, payload)
  → AgentStateManager.onEvent(event)
  → Routes to provider → updates StateFlow → UI reacts
```

---

- [ ] **3.1 Create HookEventWatcher.kt**

  `src/main/kotlin/com/agentpulse/watcher/HookEventWatcher.kt`

  Responsibilities:
  - Uses `java.nio.file.WatchService` (JBR = native FSEvents on macOS)
  - Watches `~/.agent-pulse/events/` for `ENTRY_CREATE`
  - On new file: parse filename `<timestamp>-<agent>-<eventType>-<ppid>.json`
  - Read file content and deserialize to typed `HookPayload` via `kotlinx.serialization.json.Json`
  - Create `HookEvent(agent, eventType, pid, timestamp, payload)`
  - Call `AgentStateManager.onEvent(event)`
  - Delete processed file (cleanup)
  - Ignore files starting with `.tmp.` (still being written by `report.sh`'s atomic rename pattern)
  - Debounce: collapse events within 200ms window
  - Startup scan: on launch, process any existing files in `events/` dir (recovery after restart)
  - Periodic PID validation: every 30s, check if PIDs of Running agents are still alive via `ProcessHandle.of(pid).isPresent`; if dead, fire synthetic `sessionEnd` event
  - Log: `[agent-pulse] Watching: ~/.agent-pulse/events/`
  - Log: `[agent-pulse] Event: <agent>/<eventType> (PID <pid>)`

  Core class structure:
  ```kotlin
  package com.agentpulse.watcher

  import com.agentpulse.model.AgentType
  import com.agentpulse.model.HookEvent
  import com.agentpulse.model.HookPayload
  import com.agentpulse.model.CopilotPayload
  import com.agentpulse.model.ClaudePayload
  import com.agentpulse.model.CursorPayload
  import com.agentpulse.model.CodexPayload
  import com.agentpulse.model.GeminiPayload
  import com.agentpulse.provider.AgentStateManager
  import kotlinx.coroutines.*
  import kotlinx.serialization.json.Json
  import java.nio.file.*
  import kotlin.io.path.*

  class HookEventWatcher(
      private val stateManager: AgentStateManager,
      private val eventsDir: Path = Path.of(System.getProperty("user.home"), ".agent-pulse", "events"),
  ) {
      private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

      fun start() {
          Files.createDirectories(eventsDir)
          processExistingFiles()  // Recovery after restart
          startWatching()
          startPidValidation()
          println("[agent-pulse] Watching: $eventsDir")
      }

      private fun processExistingFiles() {
          eventsDir.listDirectoryEntries("*.json")
              .filter { !it.name.startsWith(".tmp.") }
              .sortedBy { it.name }
              .forEach { processFile(it) }
      }

      private fun startWatching() {
          scope.launch {
              val watchService = FileSystems.getDefault().newWatchService()
              eventsDir.register(watchService, StandardWatchEventKinds.ENTRY_CREATE)

              while (isActive) {
                  val key = watchService.poll(200, java.util.concurrent.TimeUnit.MILLISECONDS)
                  if (key != null) {
                      // Debounce: collect events within 200ms window
                      delay(200)
                      for (event in key.pollEvents()) {
                          val filename = event.context() as? Path ?: continue
                          val file = eventsDir.resolve(filename)
                          if (file.name.endsWith(".json") && !file.name.startsWith(".tmp.")) {
                              processFile(file)
                          }
                      }
                      key.reset()
                  }
              }
          }
      }

      private fun processFile(file: Path) {
          try {
              if (!file.exists()) return
              val parts = file.nameWithoutExtension.split("-", limit = 4)
              if (parts.size != 4) return

              val (timestampStr, agentName, eventType, pidStr) = parts
              val agent = parseAgentType(agentName) ?: return
              val pid = pidStr.toIntOrNull() ?: return
              val epochSeconds = timestampStr.toLongOrNull() ?: return

              val content = file.readText()
              val payload = parsePayload(agent, content)

              val hookEvent = HookEvent(
                  agent = agent,
                  eventType = HookEventType.fromRaw(eventType),
                  pid = pid,
                  timestamp = Instant.ofEpochSecond(epochSeconds),
                  payload = payload,
              )

              stateManager.onEvent(hookEvent)
              println("[agent-pulse] Event: $agentName/$eventType (PID $pid)")

              file.deleteIfExists()  // Cleanup after processing
          } catch (e: Exception) {
              System.err.println("[agent-pulse] Failed to process ${file.name}: ${e.message}")
          }
      }

      private fun startPidValidation() {
          scope.launch {
              while (isActive) {
                  delay(30_000)
                  stateManager.agents.value
                      .filter { it.status == com.agentpulse.model.AgentStatus.Running }
                      .forEach { agent ->
                          val alive = ProcessHandle.of(agent.pid.toLong()).isPresent
                          if (!alive) {
                              // Fire synthetic sessionEnd event
                              val syntheticEvent = HookEvent(
                                  agent = agent.agentType,
                                  eventType = "sessionEnd",
                                  pid = agent.pid,
                                  timestamp = System.currentTimeMillis() / 1000,
                                  payload = when (agent.agentType) {
                                      AgentType.CopilotCli, AgentType.CopilotVsCode, AgentType.CopilotIntelliJ ->
                                          CopilotPayload()
                                      AgentType.ClaudeCode -> ClaudePayload()
                                      AgentType.CursorIde -> CursorPayload()
                                      AgentType.CodexCli -> CodexPayload()
                                      AgentType.GeminiCli -> GeminiPayload()
                                  },
                              )
                              stateManager.onEvent(syntheticEvent)
                              println("[agent-pulse] PID ${agent.pid} dead → synthetic sessionEnd")
                          }
                      }
              }
          }
      }

      private fun parseAgentType(name: String): AgentType? = when (name) {
          "copilot-cli" -> AgentType.CopilotCli
          "claude-code" -> AgentType.ClaudeCode
          "cursor" -> AgentType.CursorIde
          "codex-cli" -> AgentType.CodexCli
          "gemini-cli" -> AgentType.GeminiCli
          else -> null
      }

      private fun parsePayload(agent: AgentType, content: String): HookPayload {
          val json = Json { ignoreUnknownKeys = true }
          return when (agent) {
              AgentType.CopilotCli, AgentType.CopilotVsCode, AgentType.CopilotIntelliJ ->
                  json.decodeFromString<CopilotPayload>(content)
              AgentType.ClaudeCode -> json.decodeFromString<ClaudePayload>(content)
              AgentType.CursorIde -> json.decodeFromString<CursorPayload>(content)
              AgentType.CodexCli -> json.decodeFromString<CodexPayload>(content)
              AgentType.GeminiCli -> json.decodeFromString<GeminiPayload>(content)
          }
      }

      fun stop() {
          scope.cancel()
      }
  }
  ```

- [ ] **3.2 Create HookDeployer.kt**

  `src/main/kotlin/com/agentpulse/deploy/HookDeployer.kt`

  Responsibilities:
  - On first run (with user consent), deploy shared hook infrastructure:
    - Create `~/.agent-pulse/hooks/report.sh` (the POSIX shell script)
    - Make it executable (`chmod +x` via `File.setExecutable`)
    - Create `~/.agent-pulse/events/` directory
  - Store deployment state in `~/.agent-pulse/config.json` (`"hooksDeployed": true`)
  - **NOTE**: Agent-specific hook config deployment (Copilot CLI, Claude Code, Cursor, etc.) is handled in their respective steps (Steps 4, 6, 7, 8). This step only creates the shared infrastructure.
  - Log: `[agent-pulse] Hook infrastructure deployed`

  The `report.sh` script (~30ms, zero deps, POSIX shell):
  ```sh
  #!/bin/sh
  mkdir -p "$HOME/.agent-pulse/events"
  T=$(mktemp "$HOME/.agent-pulse/events/.tmp.XXXXXX")
  cat > "$T"
  mv "$T" "$HOME/.agent-pulse/events/$(date +%s)-$2-$1-$PPID.json"
  ```
  Arguments: `$1` = event_type, `$2` = agent_name. Stdin = JSON payload.
  Uses atomic `mktemp` + `mv` so the watcher never reads a half-written file.

  ```kotlin
  package com.agentpulse.deploy

  import kotlinx.serialization.json.*
  import java.nio.file.Files
  import java.nio.file.Path
  import kotlin.io.path.*

  class HookDeployer(
      private val baseDir: Path = Path.of(System.getProperty("user.home"), ".agent-pulse"),
  ) {
      private val configFile = baseDir.resolve("config.json")
      private val hooksDir = baseDir.resolve("hooks")
      private val eventsDir = baseDir.resolve("events")
      private val json = Json { prettyPrint = true; ignoreUnknownKeys = true }

      private val reportShContent = """
          |#!/bin/sh
          |mkdir -p "${'$'}HOME/.agent-pulse/events"
          |T=${'$'}(mktemp "${'$'}HOME/.agent-pulse/events/.tmp.XXXXXX")
          |cat > "${'$'}T"
          |mv "${'$'}T" "${'$'}HOME/.agent-pulse/events/${'$'}(date +%s)-${'$'}2-${'$'}1-${'$'}PPID.json"
      """.trimMargin() + "\n"

      fun deployIfNeeded() {
          if (isDeployed()) return
          deploy()
      }

      private fun isDeployed(): Boolean {
          if (!configFile.exists()) return false
          return try {
              val config = json.decodeFromString<JsonObject>(configFile.readText())
              config["hooksDeployed"]?.let {
                  (it as? JsonPrimitive)?.booleanOrNull == true
              } ?: false
          } catch (e: Exception) { false }
      }

      private fun deploy() {
          Files.createDirectories(hooksDir)
          Files.createDirectories(eventsDir)

          // Write report.sh
          val reportSh = hooksDir.resolve("report.sh")
          reportSh.writeText(reportShContent)
          reportSh.toFile().setExecutable(true)

          // Mark as deployed
          val config = if (configFile.exists()) {
              try { json.decodeFromString<JsonObject>(configFile.readText()) }
              catch (e: Exception) { JsonObject(emptyMap()) }
          } else {
              JsonObject(emptyMap())
          }

          val updated = JsonObject(config.toMutableMap().apply {
              put("hooksDeployed", JsonPrimitive(true))
          })
          configFile.writeText(json.encodeToString(JsonObject.serializer(), updated))

          println("[agent-pulse] Hook infrastructure deployed")
      }
  }
  ```

- [ ] **3.3 Wire into Main.kt**
  - Create `AgentStateManager` with all stub providers from Step 2
  - Create `HookDeployer()` — call `deployIfNeeded()` on first launch
  - Create `HookEventWatcher(stateManager)` — call `start()` in a `LaunchedEffect`
  - Pass `stateManager.agents` to UI (for Step 5)

  Key wiring in `Main.kt`:
  ```kotlin
  val providers = listOf(
      CopilotCliProvider(),
      ClaudeCodeProvider(),
      CursorProvider(),
      CodexProvider(),
      GeminiProvider(),
  )
  val stateManager = AgentStateManager(providers)

  // Deploy hook infrastructure on first run
  HookDeployer().deployIfNeeded()

  // Start event watcher
  val watcher = HookEventWatcher(stateManager)

  // Inside Compose application:
  LaunchedEffect(Unit) {
      watcher.start()
  }

  // Pass stateManager.agents to UI composables (for Step 5)
  ```

- [ ] **3.4 Verify**
  - Run `./gradlew run`
  - Terminal shows `[agent-pulse] Watching: ~/.agent-pulse/events/`
  - Manually create a test event file:
    ```bash
    echo '{"test":true}' > ~/.agent-pulse/events/$(date +%s)-copilot-cli-sessionStart-$$.json
    ```
  - Terminal shows `[agent-pulse] Event: copilot-cli/sessionStart (PID <your-pid>)`
  - File is deleted after processing
  - Verify `~/.agent-pulse/hooks/report.sh` exists and is executable
  - Verify `~/.agent-pulse/config.json` contains `"hooksDeployed": true`

- [ ] **3.5 Commit, push, and open PR**
  ```bash
  git checkout -b step-3-watcher
  # ... implement, then:
  git add -A && git commit -m "feat: hook event watcher and deployer

  - HookEventWatcher: JBR WatchService on ~/.agent-pulse/events/
  - Filename parsing: <timestamp>-<agent>-<eventType>-<ppid>.json
  - Startup recovery scan for existing event files
  - PID validation every 30s with synthetic sessionEnd
  - 200ms debounce, .tmp. file exclusion, cleanup after processing
  - HookDeployer: report.sh creation and events directory setup
  - Deployment state tracked in ~/.agent-pulse/config.json
  - Wired into Main.kt with AgentStateManager

  Co-authored-by: Copilot <223556219+Copilot@users.noreply.github.com>"
  git push -u origin step-3-watcher
  gh pr create --title "Step 3: Hook event watcher + deployer" \
    --body "HookEventWatcher (JBR FileWatch, filename parsing, startup recovery, PID liveness), HookDeployer (report.sh, events dir, config.json)." \
    --base main
  ```
