# Step 3: watcher — Hook event watcher + deployer

> **⚠️ READ `shared-context.md` FIRST** — it contains all design principles, architecture,
> and project structure that apply to this step.
> Path: `planning/implementation/shared-context.md`
>
> 📚 **Further reading**: Hook safety analysis, delivery mechanism comparison, and per-agent hook schemas in [`research/agent-research.md`](../../research/agent-research.md)
>
> 🔬 **JBR WatchService research**: Deep analysis of JBR's native FSEvents WatchService vs OpenJDK polling vs IntelliJ fsnotifier in [`research/jbr-watchservice-research.md`](../../research/jbr-watchservice-research.md)

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

> **⚠️ JBR WatchService — critical implementation notes** (see [`research/jbr-watchservice-research.md`](../../research/jbr-watchservice-research.md)):
>
> JBR replaces OpenJDK's `PollingWatchService` with a native `MacOSXWatchService` backed by macOS FSEvents via JNI. This is the **default** — no system properties needed. The escape hatch `-Dwatch.service.polling=true` would downgrade to polling (never use this).
>
> Three rules for correct JBR WatchService usage:
> 1. **Use `take()` not `poll()`** — `take()` blocks on a `LinkedBlockingDeque` with zero CPU. `poll()` causes unnecessary thread wakeups.
> 2. **Use `SensitivityWatchEventModifier.HIGH`** — sets FSEvents latency to 100ms (default MEDIUM = 500ms).
> 3. **No debounce needed** — FSEvents already coalesces events at the kernel level. Adding `delay()` just adds latency.
>
> Source: [JBR `BsdFileSystem.java`](https://github.com/JetBrains/JetBrainsRuntime/blob/b0b9c793dfe51ab56cf02131350de7e4c349539c/src/java.base/macosx/classes/sun/nio/fs/BsdFileSystem.java), [JBR `MacOSXWatchService.java`](https://github.com/JetBrains/JetBrainsRuntime/blob/b0b9c793dfe51ab56cf02131350de7e4c349539c/src/java.base/macosx/classes/sun/nio/fs/MacOSXWatchService.java)

---

- [ ] **3.1 Create HookEventWatcher.kt**

  `src/main/kotlin/com/agentpulse/watcher/HookEventWatcher.kt`

  Responsibilities:
  - Uses `java.nio.file.WatchService` (JBR = native FSEvents on macOS, zero-config default — see [research](../../research/jbr-watchservice-research.md))
  - Watches `~/.agent-pulse/events/` for `ENTRY_CREATE` with `SensitivityWatchEventModifier.HIGH` (100ms FSEvents latency)
  - Uses blocking `take()` (not `poll()`) — blocks natively on `LinkedBlockingDeque`, zero CPU when idle
  - Wrapped in `runInterruptible(Dispatchers.IO)` for clean coroutine cancellation
  - No debounce needed — FSEvents coalesces events at kernel level
  - On new file: parse filename `<timestamp>-<agent>-<eventType>-<ppid>.json`
  - Read file content and deserialize to typed `HookPayload` via `kotlinx.serialization.json.Json`
  - Create `HookEvent(agent, eventType, pid, timestamp, payload)`
  - Call `AgentStateManager.onEvent(event)`
  - Delete processed file (cleanup)
  - Ignore files starting with `.tmp.` (still being written by `report.sh`'s atomic rename pattern)
  - Startup scan: on launch, process any existing files in `events/` dir (recovery after restart)
    - Groups files by `(agent, pid)`, keeps only the latest file per group, deletes stale ones
    - Prevents processing hundreds of stale events accumulated while agent-pulse was offline
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

      private fun Path.isProcessableEvent(): Boolean =
          name.endsWith(".json") && !name.startsWith(".tmp.")

      fun start() {
          Files.createDirectories(eventsDir)
          processExistingFiles()  // Recovery after restart
          startWatching()
          startPidValidation()
          println("[agent-pulse] Watching: $eventsDir")
      }

      private fun processExistingFiles() {
          // On startup, there may be many queued events (e.g., 200-300 files
          // accumulated while agent-pulse was offline). We only care about the
          // latest state per (agent, pid) — older events are stale.
          // Group by (agent, pid), keep only the last file per group, delete the rest.
          val processable = eventsDir.listDirectoryEntries()
              .filter { it.isProcessableEvent() }
              .sortedBy { it.name }  // Chronological — timestamp is first in filename

          val grouped = processable.groupBy { file ->
              val parts = file.nameWithoutExtension.split("-", limit = 4)
              if (parts.size == 4) "${parts[1]}-${parts[3]}" else "unknown"  // "agent-pid"
          }

          for ((_, files) in grouped) {
              // Delete all but the last (most recent) file in each group
              val stale = files.dropLast(1)
              stale.forEach { it.deleteIfExists() }
              if (stale.isNotEmpty()) {
                  println("[agent-pulse] Startup: skipped ${stale.size} stale events for group")
              }
              // Process only the latest event per (agent, pid)
              files.lastOrNull()?.let { processFile(it) }
          }
      }

      private fun startWatching() {
          scope.launch {
              val watchService = FileSystems.getDefault().newWatchService()
              eventsDir.register(
                  watchService,
                  arrayOf(StandardWatchEventKinds.ENTRY_CREATE),
                  com.sun.nio.file.SensitivityWatchEventModifier.HIGH, // 100ms FSEvents latency on JBR
              )

              while (isActive) {
                  // take() blocks natively on JBR's LinkedBlockingDeque — zero CPU when idle
                  // On OpenJDK this would poll, but we always run on JBR
                  val key = kotlinx.coroutines.runInterruptible(Dispatchers.IO) {
                      watchService.take()
                  }
                  for (event in key.pollEvents()) {
                      val filename = event.context() as? Path ?: continue
                      val file = eventsDir.resolve(filename)
                      if (file.isProcessableEvent()) {
                          processFile(file)
                      }
                  }
                  key.reset()
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
  # MONITORING HOOK — MUST ALWAYS EXIT 0
  # A non-zero exit blocks agent Bash operations (hooks-observability #30).
  # Never use `set -e`. Every command guarded with `|| true` / `|| exit 0`.
  trap 'exit 0' ERR
  EVENTS_DIR="$HOME/.agent-pulse/events"
  mkdir -p "$EVENTS_DIR" || exit 0
  [ "$(find "$EVENTS_DIR" -name '*.json' -maxdepth 1 | head -1001 | wc -l)" -gt 1000 ] && exit 0
  T=$(mktemp "$EVENTS_DIR/.tmp.XXXXXX") || exit 0
  cat > "$T" || { rm -f "$T" 2>/dev/null; exit 0; }
  mv "$T" "$EVENTS_DIR/$(date +%s)-$2-$1-$PPID.json" || { rm -f "$T" 2>/dev/null; exit 0; }
  ```
  Arguments: `$1` = event_type, `$2` = agent_name. Stdin = JSON payload.
  Uses atomic `mktemp` + `mv` so the watcher never reads a half-written file.
  
  > **⚠️ Hook exit code safety**: `trap 'exit 0' ERR` ensures the script ALWAYS exits 0, even on
  > unexpected failures. This is CRITICAL — a monitoring hook that exits non-zero blocks ALL agent
  > Bash operations. See [hooks-observability #30](https://github.com/nicobailey/hooks-observability/issues/30)
  > and shared-context.md "Lessons from Competitor Research" for full details.

  > **Disk space safety**: Each event file is ~225 bytes of content but occupies one 4 KB
  > APFS block. The 1000-file cap limits worst-case accumulation to ~4 MB. At normal usage
  > (~708 events/day), the cap is hit after ~1.4 days of agent-pulse being offline.
  > When agent-pulse IS running, events are processed and deleted within ~100ms (steady
  > state: 2-3 files in flight). Users should disable hooks if permanently uninstalling
  > agent-pulse.

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
          |# MONITORING HOOK — MUST ALWAYS EXIT 0
          |# A non-zero exit blocks agent Bash operations (hooks-observability #30).
          |trap 'exit 0' ERR
          |EVENTS_DIR="${'$'}HOME/.agent-pulse/events"
          |mkdir -p "${'$'}EVENTS_DIR" || exit 0
          |[ "${'$'}(find "${'$'}EVENTS_DIR" -name '*.json' -maxdepth 1 | head -1001 | wc -l)" -gt 1000 ] && exit 0
          |T=${'$'}(mktemp "${'$'}EVENTS_DIR/.tmp.XXXXXX") || exit 0
          |cat > "${'$'}T" || { rm -f "${'$'}T" 2>/dev/null; exit 0; }
          |mv "${'$'}T" "${'$'}EVENTS_DIR/${'$'}(date +%s)-${'$'}2-${'$'}1-${'$'}PPID.json" || { rm -f "${'$'}T" 2>/dev/null; exit 0; }
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

  - HookEventWatcher: JBR native FSEvents WatchService on ~/.agent-pulse/events/
  - Uses take() (blocking, zero CPU) + SensitivityWatchEventModifier.HIGH (100ms)
  - No polling, no debounce — FSEvents coalesces at kernel level
  - Filename parsing: <timestamp>-<agent>-<eventType>-<ppid>.json
  - Startup recovery: group by (agent, pid), process only latest, delete stale
  - PID validation every 30s with synthetic sessionEnd
  - .tmp. file exclusion, cleanup after processing
  - HookDeployer: report.sh with self-cleaning 1000-file cap (~4 MB max)
  - Deployment state tracked in ~/.agent-pulse/config.json
  - Wired into Main.kt with AgentStateManager

  Co-authored-by: Copilot <223556219+Copilot@users.noreply.github.com>"
  git push -u origin step-3-watcher
  gh pr create --title "Step 3: Hook event watcher + deployer" \
    --body "HookEventWatcher (JBR FileWatch, filename parsing, startup recovery, PID liveness), HookDeployer (report.sh, events dir, config.json)." \
    --base main
  ```
