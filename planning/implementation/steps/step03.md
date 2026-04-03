# Step 3: detection — Process scanner + file watcher

> **⚠️ READ `shared-context.md` FIRST** — it contains all design principles, architecture,
> SQLite safety rules, connection hygiene, tech stack, and project structure that apply to this step.
> Path: `planning/implementation/shared-context.md`

---


**Goal**: Background detection engine that discovers agent processes via OSHI and watches FS changes via JBR's WatchService, flowing results into a `StateFlow<List<Agent>>`.

**Pre-check**: Step 2 PR is merged. `./gradlew build` passes.

**App state AFTER this step**: On launch, terminal shows `[agent-pulse] Watching: ~/.copilot/session-state/` (and other dirs that exist). Process scanner runs every 5 seconds. The terminal shows `[agent-pulse] Scan: 0 agents found` (stub providers return empty). UI still shows placeholder — agents display in Step 5.

**Files to create**:
- `src/main/kotlin/com/agentpulse/detection/ProcessScanner.kt`
- `src/main/kotlin/com/agentpulse/detection/FileWatcher.kt`
- `src/main/kotlin/com/agentpulse/detection/DetectionOrchestrator.kt`

- [ ] **3.1 Create ProcessScanner.kt**
    - Wraps OSHI's `SystemInfo().operatingSystem.getProcesses()`
    - `scan(targetNames: List<String>): List<ProcessInfo>` — refresh processes, filter by name match (case-insensitive contains), map to `ProcessInfo` data class
    - `getProcess(pid: Int): ProcessInfo?` — look up single process by PID (for lock file cross-referencing)
    - `buildProcessTree(processes: List<ProcessInfo>): Map<Int, List<ProcessInfo>>` — group by parentPid

  Key OSHI code pattern (read-only):
  ```kotlin
  val si = SystemInfo()
  val os = si.operatingSystem

  fun scan(targetNames: List<String>): List<ProcessInfo> {
      val allProcs = os.getProcesses(null, null) // Read-only OS call
      return allProcs.filter { proc ->
          targetNames.any { name ->
              proc.name.contains(name, ignoreCase = true) ||
              proc.commandLine.contains(name, ignoreCase = true)
          }
      }.map { proc ->
          ProcessInfo(
              pid = proc.processID,
              name = proc.name,
              commandLine = proc.commandLine,
              exePath = proc.path.takeIf { it.isNotBlank() },
              cpuPercent = proc.processCpuLoadCumulative * 100,
              memoryBytes = proc.residentSetSize,
              parentPid = proc.parentProcessID.takeIf { it > 0 },
              startTime = proc.startTime,
          )
      }
  }
  ```

- [ ] **3.2 Create FileWatcher.kt**
    - Wraps `java.nio.file.WatchService` (JBR native FSEvents on macOS)
    - `start(dirs: List<Path>, onEvent: () -> Unit)` — register each existing dir with ENTRY_CREATE, ENTRY_DELETE, ENTRY_MODIFY; poll in a coroutine loop
    - Use `com.sun.nio.file.ExtendedWatchEventModifier.FILE_TREE` for recursive watching (JBR supports this on macOS)
    - Print `[agent-pulse] Watching: {dir}` for each registered dir
    - Debounce: collapse events within 500ms window before calling `onEvent()`
    - **Read-only**: WatchService only observes filesystem events, never modifies files

  Key pattern:
  ```kotlin
  val watchService = FileSystems.getDefault().newWatchService()

  // JBR supports FILE_TREE for recursive watching on macOS
  val fileTree = try {
      Class.forName("com.sun.nio.file.ExtendedWatchEventModifier")
          .getField("FILE_TREE").get(null) as WatchEvent.Modifier
  } catch (e: Exception) { null }

  for (dir in dirs) {
      if (dir.exists()) {
          val modifiers = if (fileTree != null) arrayOf(fileTree) else emptyArray()
          dir.register(watchService,
              arrayOf(ENTRY_CREATE, ENTRY_DELETE, ENTRY_MODIFY),
              *modifiers)
          println("[agent-pulse] Watching: $dir")
      }
  }
  ```

- [ ] **3.3 Create DetectionOrchestrator.kt**
    - Holds `ProviderRegistry`, `ProcessScanner`, `FileWatcher`, `SearchIndexer`
    - Exposes `val agents: StateFlow<List<Agent>>`
    - `performScan()`: get process names from registry → scan with OSHI → pass to registry.scanAll() → update StateFlow → diff for indexer (add new, remove gone)
    - `start()`: start FileWatcher (on FS event → performScan()), start periodic coroutine (every 5 seconds → performScan())
    - Log: `[agent-pulse] Scan: N agents found`

  ```kotlin
  package com.agentpulse.detection

  import com.agentpulse.model.Agent
  import com.agentpulse.provider.ProviderRegistry
  import com.agentpulse.search.SearchIndexer
  import kotlinx.coroutines.*
  import kotlinx.coroutines.flow.MutableStateFlow
  import kotlinx.coroutines.flow.StateFlow
  import kotlinx.coroutines.flow.asStateFlow
  import java.nio.file.Path
  import kotlin.io.path.Path
  import kotlin.io.path.exists

  class DetectionOrchestrator(
      private val registry: ProviderRegistry,
      private val scanner: ProcessScanner,
      private val indexer: SearchIndexer,
      private val scanIntervalMs: Long = 5_000,
  ) {
      private val _agents = MutableStateFlow<List<Agent>>(emptyList())
      val agents: StateFlow<List<Agent>> = _agents.asStateFlow()

      private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
      private var watcher: FileWatcher? = null

      fun start() {
          // Start file watcher on known agent directories
          val watchDirs = listOf(
              Path(System.getProperty("user.home"), ".copilot", "session-state"),
              Path(System.getProperty("user.home"), ".claude"),
              Path(System.getProperty("user.home"), ".codex", "sessions"),
              Path(System.getProperty("user.home"), ".gemini", "tmp"),
          ).filter { it.exists() }

          watcher = FileWatcher().also { fw ->
              scope.launch(Dispatchers.IO) {
                  fw.start(watchDirs) { scope.launch { performScan() } }
              }
          }

          // Start periodic scan
          scope.launch {
              while (isActive) {
                  performScan()
                  delay(scanIntervalMs)
              }
          }
      }

      suspend fun performScan() {
          try {
              val targetNames = registry.allProcessNames()
              val processes = withContext(Dispatchers.IO) { scanner.scan(targetNames) }
              val detected = registry.scanAll(processes)
              val previous = _agents.value

              _agents.value = detected
              println("[agent-pulse] Scan: ${detected.size} agents found")

              // Diff for search indexer
              val previousIds = previous.map { it.id }.toSet()
              val currentIds = detected.map { it.id }.toSet()
              detected.filter { it.id !in previousIds }.forEach { indexer.add(it) }
              previousIds.minus(currentIds).forEach { indexer.remove(it) }
          } catch (e: Exception) {
              System.err.println("[agent-pulse] Scan error: ${e.message}")
          }
      }

      fun stop() {
          scope.cancel()
      }
  }
  ```

- [ ] **3.4 Wire into Main.kt**
    - Create `ProviderRegistry`, register all stub providers
    - Create `DetectionOrchestrator` with registry + `NoopIndexer`
    - Call `orchestrator.start()` in a `LaunchedEffect`
    - Pass `orchestrator.agents` to UI (for Step 5)

- [ ] **3.5 Verify**
    - Run `./gradlew run`
    - Terminal shows `[agent-pulse] Watching: ...` and `[agent-pulse] Scan: 0 agents found`
    - 0 is expected since providers are stubs

- [ ] **3.6 Commit, push, and open PR**
  ```bash
  git checkout -b step-3-detection
  # ... implement, then:
  git add -A && git commit -m "feat: process scanner and file watcher detection engine

  - ProcessScanner: OSHI process detection with tree building
  - FileWatcher: JBR WatchService with FILE_TREE recursive watching
  - DetectionOrchestrator: scan → update StateFlow → search indexer
  - 5-second periodic scan + FS-event-triggered scan
  - All agent data access is read-only

  Co-authored-by: Copilot <223556219+Copilot@users.noreply.github.com>"
  git push -u origin step-3-detection
  gh pr create --title "Step 3: Detection engine" \
    --body "OSHI process scanner, JBR WatchService file watcher, detection orchestrator with StateFlow." \
    --base main
  ```
