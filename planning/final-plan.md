# agent-pulse — Final Implementation Plan

## Problem Statement

When running multiple AI coding agents (Copilot CLI, Claude Code, Codex, Cursor, Gemini) across terminals, IDEs, and remote environments, there's no single place to see what's running, their status, token usage, or session history. You have to flip between terminal tabs and IDE panels to piece together what's happening.

**agent-pulse** is a lightweight system tray app (like JetBrains Toolbox) that gives you a live dashboard of every AI agent running on your machine — and optionally remote ones too.

> **Note**: The tech stack is **Kotlin/Compose Desktop + JetBrains Runtime (JBR)**, as decided in `research/research-alternative.md`. Agent detection strategies are documented in `research/agent-research.md`.

---

## Critical Design Principles

### 🔒 Read-Only Access (MANDATORY)

**Everything agent-pulse touches on agents MUST be read-only.** We want to avoid any possibility of one agent corrupting another one, or agent-pulse interfering with any agent's operation.

This is a non-negotiable safety constraint that applies to every layer of the application:

| Layer | Rule | Implementation |
|---|---|---|
| **File system** | NEVER write, create, modify, or delete files in agent directories | Use `readText()`, `readLines()`, `readBytes()` only. Never `writeText()`, `createFile()`, `delete()` |
| **SQLite databases** | ALWAYS open in read-only mode | JDBC URL: `jdbc:sqlite:file:<path>?mode=ro`. Additionally set `PRAGMA query_only = ON` after connecting |
| **Process info** | NEVER signal, kill, or modify agent processes | `ProcessHandle.of(pid)` for liveness checks only. No `kill()`, no signals |
| **File watching** | Watch ONLY — never create/modify/delete watched files | `WatchService.register()` with event kinds, never `Files.write()` on watched paths |
| **OTLP receiver** | RECEIVE data only — never push data to agents | **POST-MVP.** Passive listener on localhost; agents push to us, we never push to them |
| **Agent config** | Hook deployment writes hook configs to agent directories | Writes `~/.copilot/hooks/agent-pulse.json`, merges into `~/.claude/settings.json`, etc. These are hook registration files, not agent configuration. See Step 3. |

**SQLite read-only checklist** (applies to ALL database access):
```kotlin
// ✅ CORRECT — read-only connection
val url = "jdbc:sqlite:file:${dbPath.toAbsolutePath()}?mode=ro"
val conn = DriverManager.getConnection(url)
conn.createStatement().execute("PRAGMA query_only = ON")

// ❌ FORBIDDEN — writable connection
val conn = DriverManager.getConnection("jdbc:sqlite:${dbPath}")
```

**File access checklist**:
```kotlin
// ✅ CORRECT — read only
val content = path.readText()
val lines = path.readLines()
val bytes = path.readBytes()

// ❌ FORBIDDEN — writes
path.writeText(content)
path.createFile()
path.deleteIfExists()
Files.write(path, bytes)
```

**agent-pulse writes ONLY to its own data directory**: `~/.agent-pulse/` for configuration, cache, and internal state. It NEVER writes to `~/.copilot/`, `~/.claude/`, `~/.cursor/`, `~/.codex/`, `~/.gemini/`, or any agent workspace directory.

**Exception — Hook deployment**: The ONLY case where agent-pulse writes to agent-related paths is during first-run hook deployment. This writes hook config files (e.g., `~/.copilot/hooks/agent-pulse.json`, merge into `~/.claude/settings.json`) so that agents send events to `~/.agent-pulse/events/`. Hook deployment is automated on first run and can be re-triggered from Settings. See Step 3 for details.

### ⚠️ Safe Reading from Mid-Write Files (CRITICAL)

> **📦 POST-MVP**: This section documents safe reading strategies for the enrichment layer (reading agent files for token counts, session data, etc.). The MVP uses hooks+FileWatch only and does not read agent files directly. Keep this section for reference when implementing post-MVP enrichment.

Agent files are actively being written to by the agents while agent-pulse reads them. This is the central reliability challenge of the entire application. **If we don't handle this correctly, the app will show corrupt data, crash, or silently miss agents.**

#### The Problem

When agent-pulse reads a file, the owning agent process may be:
- Mid-write of a JSON object to an append-only log (events.jsonl)
- Holding an exclusive SQLite lock during a transaction (session.db)
- Atomically replacing a config file (config.json via temp-file + rename)
- Appending to a large text file (MEMORY.md)

We cannot control when agents write. We must handle every possible state of every file we read.

#### SQLite Journal Modes Explained

SQLite has two main journal modes that determine how it handles concurrent access. This matters because agent-pulse reads agent databases while the agents are actively writing to them.

**`delete` journal mode (the default):** When a write transaction begins, SQLite copies the original database pages to a `-journal` file as a backup. The writer then modifies the main database file directly. While writing, SQLite holds an **EXCLUSIVE lock** on the entire database — no other process can read OR write during this time. Once the transaction commits, the journal file is deleted (hence the name). If the writer crashes mid-transaction, the journal file is used on next open to roll back the incomplete changes, restoring the database to a consistent state. **The problem for us**: any read attempt while the writer holds that exclusive lock immediately gets `SQLITE_BUSY`.

**`wal` (Write-Ahead Logging) mode:** Instead of modifying the main database file, writes go to a separate `-wal` (write-ahead log) file. Readers continue reading from the main database file (plus already-committed WAL entries), getting a **consistent snapshot** as of their transaction start. Writers and readers **never block each other** — this is the key advantage. The WAL is periodically merged back into the main database via "checkpointing." **The benefit for us**: we can read at any time without SQLITE_BUSY, regardless of whether the agent is writing.

- Source: [SQLite Write-Ahead Logging](https://sqlite.org/wal.html)
- Source: [SQLite Locking in Rollback Mode](https://www.sqlite.org/lockingv3.html)

#### Evidence from Actual Agent Files (Verified on This Machine)

| File | Journal/Write Mode | Risk Level | Evidence |
|---|---|---|---|
| Copilot `session.db` | SQLite **`delete`** journal mode | **MEDIUM** — reads blocked during writes (`SQLITE_BUSY`) | `sqlite3 session.db "PRAGMA journal_mode;"` → `delete`. No `-wal`/`-shm` files present. |
| Cursor workspace `state.vscdb` | SQLite **`delete`** journal mode | **MEDIUM** — same blocking risk | `PRAGMA journal_mode;` → `delete`. Has `.backup` file alongside. |
| Cursor global `state.vscdb` | SQLite **`wal`** mode | **LOW** — concurrent reads safe (snapshot isolation) | `.options.json` contains `{"useWAL": true}`. `-wal` and `-shm` files present. |
| Copilot `events.jsonl` | Append-only, newline-terminated | **LOW** — partial last line only | File ends with `0x0a` (newline). Each line is a complete JSON object. 8.3MB file actively written. |
| Copilot `workspace.yaml` | Small file, likely atomic write (temp+rename) | **LOW** — either old version or new version | 10-line YAML. Node.js apps typically use atomic write patterns. |
| Copilot `config.json` | Small file, likely atomic write | **LOW** | ~500 bytes. Standard Node.js `fs.writeFile` with rename. |
| Copilot `inuse.*.lock` | Tiny file (5-6 bytes, contains PID as text) | **VERY LOW** — file either exists or doesn't | Content is just the PID number (e.g., `84295`). Presence = session active. |
| Claude `MEMORY.md` | Text file, written by agent | **LOW** — partial reads acceptable | Informational only. Partial content still useful. |
| Cursor `agent-transcripts/*.jsonl` | Append-only JSONL | **LOW** — same as events.jsonl | Same pattern: one JSON object per line. |
| Codex `history.jsonl` / Gemini chat files | Append-only JSONL / JSON files | **LOW** | Same append-only or atomic-write patterns. |

**Key finding**: The two highest-risk files (Copilot `session.db` and Cursor workspace `state.vscdb`) use SQLite **`delete`** journal mode, NOT WAL. This means the agent holds an exclusive lock during writes and our reads get `SQLITE_BUSY`. We MUST handle this with `busy_timeout` and retry.

#### Strategy per File Category

##### 1. SQLite Databases (MEDIUM risk)

**Problem**: With `delete` journal mode, a write transaction holds an EXCLUSIVE lock on the entire database. Any read attempt during this window gets `SQLITE_BUSY` — the database is literally locked by the writing agent. With `wal` mode, readers and writers don't block each other (readers get snapshot isolation), but `SQLITE_BUSY` can still occur during checkpointing.

**Strategy**:
- Set `busy_timeout=3000` (3 seconds) in the JDBC URL — SQLite will retry internally before returning SQLITE_BUSY
- Wrap every query in try-catch, handle `SQLITE_BUSY` with one manual retry after 500ms backoff
- Keep connections extremely short-lived: open → single query → close. Never hold connections across scan cycles
- Cache last successful query result per database. If current read fails, return cached data (stale but valid)
- Log the failure at DEBUG level (not ERROR — this is expected under load)

```kotlin
// JDBC URL with busy_timeout
val url = "jdbc:sqlite:file:${dbPath.toAbsolutePath()}?mode=ro&busy_timeout=3000"
```

**Why busy_timeout works**: SQLite's internal busy handler uses exponential backoff with jitter. A 3-second timeout is enough for agent write transactions which are typically sub-100ms. The handler retries automatically within that window.
- Source: [SQLite busy_timeout pragma](https://www.sqlite.org/pragma.html#pragma_busy_timeout)
- Source: [SQLite WAL documentation — concurrent readers never blocked in WAL mode](https://sqlite.org/wal.html)

##### 2. JSONL Files — Append-Only Logs (LOW risk)

**Problem**: The writer appends one line at a time. If we read at the exact moment a line is being written, the last line may be incomplete (truncated JSON).

**Strategy**:
- Read all lines using `BufferedReader.readLine()` — this only returns complete lines (lines terminated with `\n`). An incomplete last line is NOT returned by `readLine()`. This is a JVM guarantee.
- Additionally, validate each line as JSON before using it. Skip any line that fails `Json.parseToJsonElement(line)`.
- This double protection (line-termination + JSON validation) makes JSONL reading effectively bulletproof.

```kotlin
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
```

**Why this works**: On POSIX (macOS/Linux), `BufferedReader.readLine()` reads until `\n` is found. If the writer is mid-write (the trailing `\n` hasn't been flushed yet), `readLine()` returns `null` at EOF without returning the partial line. The partial line simply isn't visible to us.
- Source: [Java BufferedReader.readLine() spec](https://docs.oracle.com/en/java/javase/25/docs/api/java.base/java/io/BufferedReader.html#readLine())
- Source: [StackOverflow — concurrent append safety](https://stackoverflow.com/questions/46297233/how-safe-is-it-reading-copying-a-file-which-is-being-appended-to)

##### 3. JSON/YAML Config Files (LOW risk)

**Problem**: Config files are typically small (<1KB) and written atomically (temp file + `rename()`). The POSIX `rename()` syscall is atomic — readers either see the old file or the new file, never a partial file. However, not all applications use atomic writes.

**Strategy**:
- Try to parse the file. If parsing succeeds, use the result.
- If parsing fails (malformed JSON/YAML), it means we caught a non-atomic write mid-flight. Wait 200ms and retry ONCE.
- If retry also fails, use the last known good cached value.
- Cache the last successful parse result per file path.

```kotlin
fun <T> readJsonWithRetry(path: Path, parser: (String) -> T, cache: MutableMap<Path, T>): T? {
    repeat(2) { attempt ->
        val content = safeReadText(path) ?: return cache[path]
        try {
            val result = parser(content)
            cache[path] = result
            return result
        } catch (e: Exception) {
            if (attempt == 0) Thread.sleep(200) // retry once after brief delay
        }
    }
    return cache[path] // fall back to cached
}
```

**Why this works**: Node.js (used by Copilot CLI and Cursor) typically writes config files atomically via `fs.writeFile()` which uses `open() + write() + rename()` under the hood. The 200ms retry handles the rare case where an application doesn't use atomic writes.
- Source: [POSIX rename() atomicity guarantee](https://pubs.opengroup.org/onlinepubs/9699919799/functions/rename.html)

##### 4. Lock Files (VERY LOW risk)

**Problem**: Lock files are tiny (5-6 bytes, containing just the PID as text). They're created atomically by the OS and their presence/absence is the primary signal.

**Strategy**: No special handling needed beyond basic try-catch.
- `path.exists()` → session is active
- `path.readText().trim().toIntOrNull()` → PID

##### 5. Text Files — MEMORY.md, plan.md (LOW risk)

**Problem**: These can be mid-write, but they're informational. A partial read still provides useful data.

**Strategy**: Read as-is with `safeReadText()`. Partial content is acceptable. No retry needed.

#### Last-Known-Good Cache Pattern

The most important pattern across all file types is the **last-known-good cache**. Every provider maintains an in-memory cache of the last successful read result per file:

```kotlin
class CachedFileState {
    private val textCache = ConcurrentHashMap<Path, String>()
    private val jsonCache = ConcurrentHashMap<Path, JsonElement>()
    private val dbCache = ConcurrentHashMap<Path, List<Map<String, String>>>()

    fun getText(path: Path): String? {
        val result = SafeFileReader.readText(path)
        if (result != null) textCache[path] = result
        return result ?: textCache[path]
    }

    fun getDbRows(dbPath: Path, sql: String): List<Map<String, String>> {
        val result = ReadOnlyDb.queryMap(dbPath, sql)
        if (result.isNotEmpty()) dbCache[dbPath] = result
        return result.ifEmpty { dbCache[dbPath] ?: emptyList() }
    }
}
```

**This means**: Even if a file is temporarily unreadable (locked, mid-write, permissions changed), the dashboard shows the last known state rather than blank data. The agent card appears with slightly stale data rather than disappearing entirely.

#### Defensive Error Handling

Every read operation MUST be wrapped in try-catch. If a file read fails, the agent is still shown in the dashboard with whatever data was successfully obtained — **never crash, never skip the agent entirely**.

```kotlin
fun safeReadText(path: Path): String? = try {
    if (path.exists() && path.isReadable()) path.readText() else null
} catch (e: IOException) { null }

suspend fun safeReadDb(dbPath: Path, sql: String): List<Map<String, String>> = try {
    if (!dbPath.exists()) return emptyList()
    ReadOnlyDb.queryMap(dbPath, sql)
} catch (e: Exception) { emptyList() }
```

### Connection Hygiene for SQLite

> **📦 POST-MVP**: This section applies to the enrichment layer (reading agent SQLite databases). The MVP uses hooks+FileWatch only and does not open agent databases. Keep for reference.

Agent databases (Cursor's `state.vscdb`, Copilot's `session.db`) are actively being written to by the agents. agent-pulse must:
1. **Open → read → close immediately.** Never hold connections open. The `DriverManager.getConnection().use { }` pattern ensures this. Connection overhead (~1-5ms) is negligible at our 2-5s poll frequency.
2. **Use `mode=ro` only — NEVER add `immutable=1`.** `mode=ro` takes SHARED locks that detect external changes. `immutable=1` disables all file locking and change detection — if the agent writes while we have `immutable=1`, SQLite may return **incorrect query results or report database corruption**. ([Source](https://sqlite.org/uri.html))
3. **`mode=ro` prevents side-file creation** — it won't create `-wal`, `-shm`, or `-journal` files in the agent's directory. Important: if a WAL database's `-wal`/`-shm` files don't exist, `mode=ro` will fail to open (it can't create them). This is handled by our existing exception → cached data fallback.
4. **Set `busy_timeout=3000`** in the JDBC URL for automatic internal retry. Agent write transactions are fast (10-50ms for `session.db`, 5-20ms for `state.vscdb`), so 3s is more than adequate.
5. **Handle `SQLITE_BUSY` gracefully** — if busy_timeout expires, retry once manually with 500ms backoff, then return cached data.
6. **No connection pooling.** At our 2-5s poll frequency, pooling (HikariCP, etc.) adds lifecycle management complexity with zero meaningful performance gain. Each query being independent simplifies error handling.
7. **Keep `autoCommit=true` (JDBC default).** Each query runs in its own implicit transaction; SHARED locks release immediately after query completion. **Important clarification: SQLite locks are tied to transactions, not connections.** An idle connection with `autoCommit=true` holds NO lock and NO WAL snapshot. WAL checkpoint starvation only happens with long-held transactions (`autoCommit=false` without explicit commits), which we never do.
8. **Explicit layered timeouts** (defense-in-depth against holding WAL snapshots open):
    - `DriverManager.setLoginTimeout(5)` — 5s max to open connection
    - `statement.queryTimeout = 5` — 5s max per query (JDBC driver cancellation)
    - `withTimeout(10_000)` — coroutine cancellation as outer safety net
    - `connection.use {}` — guarantees `close()` even on exceptions/cancellation
      Together, these guarantee no connection is ever held open for more than ~10s, making WAL checkpoint starvation impossible in practice.
9. **Never call `VACUUM`, `PRAGMA journal_mode=`, or any DDL** on agent databases.
10. **Cache last successful query result** per database + query. Return cached data on any failure.

#### 🔮 Future Optimization: `PRAGMA data_version`

> **Not for initial implementation** — add only if polling performance is a concern.

`PRAGMA data_version` returns an integer that changes whenever any other connection commits. It can gate actual queries: only run the full SELECT when `data_version` changes since the last poll. This is a microsecond operation vs running actual queries every cycle.

**Tradeoff:** Requires keeping a long-lived connection open per database (to compare values across polls). With `autoCommit=true`, an idle connection holds no locks, so this is safe — but it adds connection lifecycle management complexity. Our current "WatchService triggers query" approach is simpler and good enough for human-timescale agent monitoring.

**If needed later**, the pattern is:
```kotlin
// Long-lived connection per database, autoCommit=true (holds no lock when idle)
val version = stmt.executeQuery("PRAGMA data_version").use { rs -> rs.getInt(1) }
if (version != lastVersion) { /* run actual queries */ }
```

---

## Build Model

Implementation agents use **`claude-haiku-4.5`** — fast and cheap. The detailed todos with exact code snippets make this viable; the model just needs to follow instructions precisely, not reason deeply about architecture.

---

## Tech Stack

| Layer | Technology | Why |
|---|---|---|
| **Language** | Kotlin/JVM | Single language for entire app, strong typing, coroutines |
| **UI Framework** | Compose for Desktop (Material 3) | Declarative UI, JetBrains-maintained, reactive state |
| **Runtime** | JetBrains Runtime (JBR) 25 LTS | Native FSEvents WatchService on macOS (see `research/research-alternative.md`) |
| **Process Scanning** | [OSHI](https://github.com/oshi/oshi) 6.6.x | **POST-MVP.** Cross-platform process enumeration fallback for agents without hooks |
| **File Watching** | `java.nio.file.WatchService` (JBR) | Native FSEvents on macOS via JBR, ~100ms latency |
| **SQLite** | [sqlite-jdbc](https://github.com/xerial/sqlite-jdbc) 3.46.x | **POST-MVP.** Read-only access to agent databases for enrichment layer |
| **JSON** | kotlinx-serialization-json | Kotlin-native, fast, compile-time safe |
| **YAML** | [kaml](https://github.com/charleskorn/kaml) | **POST-MVP.** Parse Copilot's `workspace.yaml` for enrichment |
| **Coroutines** | kotlinx-coroutines | Background scanning, debouncing, state flow |
| **Global Hotkey** | [JNA](https://github.com/java-native-access/jna) 5.x + Carbon (macOS) | Same approach as JetBrains Toolbox — native `RegisterEventHotKey` via JNA |
| **Shortcut Conflicts** | [JBR API](https://jetbrains.github.io/JetBrainsRuntimeApi/) `SystemShortcuts` | Query existing OS shortcuts to avoid conflicts (JBR-specific) |
| **System Tray** | Compose `Tray` composable | Built-in tray support in Compose Desktop |
| **OTLP Receiver** | [Ktor](https://ktor.io/) HTTP server | **POST-MVP.** Lightweight HTTP/JSON endpoint for OTel data |
| **Spotlight Bridge** | Swift CLI (bundled) | Core Spotlight integration on macOS |
| **Packaging** | Compose Gradle plugin (jpackage + jlink) | Bundles JBR automatically, produces `.dmg` |
| **Desktop Extras** | [JBR API](https://jetbrains.github.io/JetBrainsRuntimeApi/) | Custom title bars, rounded corners, HiDPI |

**Why not Tauri/Rust?** See `research/research-alternative.md` — the critical issue is macOS file watching. OpenJDK polls every 2-10 seconds; JBR uses native FSEvents. Since Compose Desktop bundles JBR automatically, the fix is zero-config.

---

## Prerequisites

| Tool | Version | Install (macOS) | Verify |
|---|---|---|---|
| **JBRSDK** | 25+ | Download from [JBR GitHub Releases](https://github.com/JetBrains/JetBrainsRuntime/releases) (`jbrsdk-25.x.x-osx-aarch64-*.tar.gz`) | `java --version` (should show "JBR-25.x") |
| **Gradle** | 8.x | Gradle wrapper included | `./gradlew --version` |
| **Swift** | 5.9+ (Step 11 only) | Comes with Xcode CLT | `swift --version` |
| **gh CLI** | any | `brew install gh` | `gh --version` |

Gradle wrapper is committed to the repo — no global Gradle install needed. JBR is bundled by the Compose plugin at build time. **For development, use JBRSDK** (not OpenJDK) so the app runs on the same runtime during development as in production — this ensures JBR-specific features like native FSEvents WatchService and `SystemShortcuts` API work identically in dev and prod.

---

## Execution Model

**This plan is executed ONE STEP AT A TIME, not all at once.**

When an implementation agent is given this plan:
1. **Read final-plan.md** to find the next pending step (the first step NOT marked `[DONE]`)
2. **Execute ONLY that one step** — follow all its sub-tasks, verify, create the branch, commit, push, and open the PR
3. **Mark the step as done** by changing `### Step N:` to `### Step N: [DONE]`
4. **STOP.** Do not proceed to the next step.
5. The user will explicitly say when to start the next step.

**CRITICAL — Merge gate**: Before starting any step, run `gh pr list --state open`. If ANY step PR is still open, DO NOT start the next step.

**The plan is the single source of truth.** If PR review changes anything, update final-plan.md to match reality.

---

## How to Resume

1. Open terminal in the project root directory
2. Check what's done: `git --no-pager log --oneline main`
3. Check final-plan.md: steps marked `[DONE]` are complete
4. Start the next pending step
5. Steps are sequential — don't skip ahead

---

## Git Workflow

Every step is implemented on a **separate feature branch** and merged via **pull request**:

1. `git checkout main && git pull && git checkout -b step-N-<name>`
2. Implement, commit along the way
3. `git push -u origin step-N-<name>`
4. `gh pr create --title "Step N: <title>" --body "<description>" --base main`
5. Review → merge → next step

Branch naming: `step-1-scaffold`, `step-2-data-model`, `step-3-detection`, `step-4-copilot`, `step-5-ui`, `step-6-claude`, `step-7-cursor`, `step-8-codex-gemini`, `step-9-otlp`, `step-10-packaging`, `step-11-hotkey`, `step-12-spotlight`

---

## Architecture

**MVP: Hooks + FileWatch only.** No process scanning, no agent file reading.

```
┌─ System Tray ───────────────────────────────────────────────────────┐
│  🫀 agent-pulse                        [Ctrl+Shift+` to toggle]    │
│                                                                     │
│  ┌─ Compose Desktop Window ──────────────────────────────────────┐  │
│  │                                                                │  │
│  │  🟢 Copilot CLI — session a1b2c3d4 — /Users/me/myproject     │  │
│  │     PID 3251 · 3 tool calls · 1 prompt                       │  │
│  │                                                                │  │
│  │  🟢 Claude Code — session e5f6g7h8 — ~/Projects/webapp       │  │
│  │     PID 9921 · 7 tool calls · 2 prompts                      │  │
│  │                                                                │  │
│  │  🟢 Cursor — session conv_abc123 — ~/Projects/frontend       │  │
│  │     PID 4501 · 2 file edits                                   │  │
│  │                                                                │  │
│  └────────────────────────────────────────────────────────────────┘  │
└─────────────────────────────────────────────────────────────────────┘

┌─ Kotlin Backend (single process) ─────────────────────────────────────┐
│                                                                       │
│  ┌─ HookEventWatcher ───────────────────────────────────────────────┐ │
│  │                                                                   │ │
│  │  FileWatcher (JBR WatchService / native FSEvents)                │ │
│  │  └─ ~/.agent-pulse/events/                                       │ │
│  │     Watches for: ENTRY_CREATE                                    │ │
│  │     On new file:                                                  │ │
│  │       1. Parse filename → agent, event, PID, timestamp            │ │
│  │       2. Read file → raw event JSON                               │ │
│  │       3. Resolve session ID (per-agent logic in Kotlin)           │ │
│  │       4. Update StateFlow<List<AgentState>>                       │ │
│  │       5. Delete processed file                                    │ │
│  │                                                                   │ │
│  │  On startup: scan events/ dir for queued files (recovery)        │ │
│  │  Periodic: validate active PIDs (kill -0), cleanup stale files   │ │
│  └───────────────────────────────────────────────────────────────────┘ │
│                                                                       │
│  ┌─ Hook Deployer ──────────────────────────────────────────────────┐ │
│  │  First-run setup: detect installed agents, deploy hook configs    │ │
│  │  Creates: ~/.agent-pulse/hooks/report.sh (3-line POSIX sh)       │ │
│  │  Copilot CLI: ~/.copilot/hooks/agent-pulse.json                  │ │
│  │  Claude Code: merge into ~/.claude/settings.json                 │ │
│  │  Cursor: ~/.cursor/hooks.json                                    │ │
│  │  Gemini: merge into settings.json                                │ │
│  │  Codex: set notify command                                       │ │
│  └───────────────────────────────────────────────────────────────────┘ │
│                                                                       │
│  ┌─ Search Indexer ─────────────────────────────────────────────────┐ │
│  │  SpotlightIndexer (macOS)  |  NoopIndexer (default)              │ │
│  └───────────────────────────────────────────────────────────────────┘ │
│                                                                       │
│  StateFlow<List<AgentState>> → Compose UI reactively updates         │
│                                                                       │
└───────────────────────────────────────────────────────────────────────┘

Hook flow:
  Agent starts → hook fires → report.sh writes file → FileWatch detects
  → parse filename+content → resolve session ID → update dashboard
```

**Post-MVP additions** (deferred):
- Enrichment layer: read agent files for token counts, model, cost
- OTLP receiver: embedded OTel endpoint for Claude Code/Cursor metrics
- Process scanning: OSHI-based fallback for agents without hooks

---

## Agent Support Matrix

Sourced from `research/agent-research.md` (comprehensive agent monitoring & extensibility research).

**MVP: Hook-based monitoring only.** File-based enrichment deferred to post-MVP.

| Agent | Hook Events | Event Data | Session ID Source | MVP? |
|---|---|---|---|---|
| **Copilot CLI** | 8 (sessionStart, postToolUse, etc.) | Tool calls, prompts, errors, cwd | Lock file → PID lookup | ✅ Step 4 |
| **Claude Code** | 9 (SessionStart, PostToolUse, etc.) | Tool calls, prompts, errors | Hook payload or session-health | ✅ Step 6 |
| **Cursor** | 11+ (sessionStart, afterFileEdit, etc.) | conversation_id, tool use, edits | `conversation_id` in payload | ✅ Step 7 |
| **Gemini CLI** | 6 (SessionStart, AfterTool, etc.) | Tool calls, prompts | Best-effort cwd+timing | ✅ Step 8 |
| **Codex CLI** | 1 (notify per turn) | thread-id, messages, cwd | `thread-id` in payload | ✅ Step 8 |

**Post-MVP enrichment** (deferred): events.jsonl token counts, SQLite session data, OTel metrics, MEMORY.md, workspace.yaml.

---

## Project Structure

```
agent-pulse/
├── build.gradle.kts
├── settings.gradle.kts
├── gradle.properties
├── gradle/wrapper/
├── src/main/
│   ├── kotlin/com/agentpulse/
│   │   ├── Main.kt
│   │   ├── GlobalHotKey.kt              (Step 11)
│   │   ├── model/
│   │   │   ├── AgentState.kt
│   │   │   ├── AgentType.kt
│   │   │   ├── AgentStatus.kt
│   │   │   ├── HookPayload.kt
│   │   │   └── HookEvent.kt
│   │   ├── provider/
│   │   │   ├── AgentProvider.kt
│   │   │   ├── AgentStateManager.kt
│   │   │   ├── CopilotCliProvider.kt       (Step 4)
│   │   │   ├── ClaudeCodeProvider.kt       (Step 6)
│   │   │   ├── CursorProvider.kt           (Step 7)
│   │   │   ├── CodexProvider.kt            (Step 8)
│   │   │   └── GeminiProvider.kt           (Step 8)
│   │   ├── watcher/
│   │   │   └── HookEventWatcher.kt         (Step 3)
│   │   ├── deploy/
│   │   │   └── HookDeployer.kt             (Step 3)
│   │   ├── otlp/
│   │   │   └── OtlpReceiver.kt            (Step 9, post-MVP)
│   │   ├── ui/
│   │   │   ├── App.kt
│   │   │   ├── Dashboard.kt
│   │   │   ├── AgentCard.kt
│   │   │   ├── Settings.kt
│   │   │   └── Theme.kt
│   │   └── search/
│   │       ├── SearchIndexer.kt
│   │       ├── SpotlightIndexer.kt         (Step 12)
│   │       └── NoopIndexer.kt
│   ├── composeResources/
│   │   └── drawable/
│   │       └── tray_icon.png
│   └── resources/
├── spotlight-bridge/                        (Step 11)
│   ├── Package.swift
│   └── Sources/main.swift
├── .github/workflows/sonarcloud.yml         (Done — SonarCloud CI)
├── .github/workflows/build.yml              (Step 10)
└── README.md                                (Done — dev instructions + SonarCloud; full rewrite in Step 10)
```

---

## Todos

Each step is a separate branch + PR. Within each step, sub-tasks are sequential.

**Project root**: the repository root (wherever you cloned `agent-pulse`)

---

### Step 1: scaffold — Compose Desktop tray app [DONE]

**Goal**: A working Compose Desktop tray scaffold with popup UI.

**Status**: Completed and merged to `main`.

**App state AFTER this step**:
- Running `./gradlew run` starts a tray-only app. On macOS, local runs additionally use macOS-only `-Dapple.awt.UIElement=true` to suppress the Dock icon; packaged app bundles rely on `LSUIElement`.
- Tray click action toggles an undecorated popup near the tray icon.
- Popup contains placeholder agent cards and an in-window `Quit` button.
- Closing the popup hides it (does not quit the app).

- [x] **1.1 Create Gradle wrapper and project structure**
- [x] **1.2 Create settings.gradle.kts**
- [x] **1.3 Create gradle.properties**
- [x] **1.4 Create build.gradle.kts**
    - Added Sonar plugin/config (`org.sonarqube`, `sonar.projectKey`, `sonar.organization`)
    - SonarCloud now uses the real identifiers: org `agent-pulse`, project `szgergo_agent-pulse`
    - Added Kotlin JVM toolchain (Java 21, JetBrains vendor)
    - Added conditional macOS JVM arg `-Dapple.awt.UIElement=true` for local `./gradlew run`
    - Added Compose resources dependency
    - Set native distribution `packageVersion = "1.0.0"`
- [x] **1.5 Create tray icon**
    - Icon is in Compose resources: `src/main/composeResources/drawable/tray_icon.png`
- [x] **1.6 Create src/main/kotlin/com/agentpulse/Main.kt**
    - Uses `Tray(onAction = ...)` popup toggle
    - Uses Compose resources (`Res.drawable.tray_icon`)
    - Includes popup UI theme and dummy agent cards
- [x] **1.7 Verify everything works**
    - `./gradlew build` passes
    - SonarCloud analysis works in CI on `main`
    - Tray icon + popup behavior works locally
- [x] **1.8 Commit, push, and open PR**
    - Implemented on `step-1-scaffold` and merged via PR #2

---

### Step 2: data-model — Core data model + provider system

**Goal**: Define all Kotlin data classes, interfaces, and type-safe enums. Establish the push-based provider API.

**Key deliverables**:
- `AgentState` data class — immutable snapshot of an agent session (id, name, agentType, status, pid, sessionId, cwd, model, eventCount, lastActivity, uptimeSecs, tokenUsage, extra)
- `HookEvent` data class — parsed from hook event files (agent, eventType, pid, timestamp, payload: HookPayload)
- `HookPayload` sealed interface — per-agent typed payload classes (CopilotPayload, ClaudePayload, CursorPayload, CodexPayload, GeminiPayload) with @Serializable + @SerialName annotations
- `AgentType` enum, `AgentStatus` enum
- `AgentProvider` interface — single method: `fun processEvent(event: HookEvent, currentState: AgentState?): AgentState`
- `AgentStateManager` — routes events to providers, manages `StateFlow<List<AgentState>>`
- `SearchIndexer` interface + `NoopIndexer`
- Stub providers for all 5 agent types

**Architecture**: Push-based. No process scanning, no file reading, no OSHI, no SQLite utilities.

> 📄 **Full implementation details**: See [`steps/step02.md`](implementation/steps/step02.md)

---

### Step 3: watcher — Hook event watcher + deployer

**Goal**: Background file watcher on `~/.agent-pulse/events/` + first-run hook deployer.

**Key deliverables**:
- `HookEventWatcher` — WatchService (JBR native FSEvents on macOS), watches for ENTRY_CREATE, parses filename+content into `HookEvent`, feeds to `AgentStateManager`, deletes processed files
- `HookDeployer` — creates `~/.agent-pulse/hooks/report.sh` (3-line POSIX sh, ~30ms), creates events directory, tracks deployment state in `config.json`
- Startup recovery: processes any queued files on launch
- PID liveness: periodic check for stale sessions

> 📄 **Full implementation details**: See [`steps/step03.md`](implementation/steps/step03.md)

---

### Step 4: copilot — Copilot CLI hook provider

**Goal**: Deploy Copilot CLI hook config and implement `CopilotCliProvider.processEvent()`.

**Key deliverables**:
- Hook config: `~/.copilot/hooks/agent-pulse.json` mapping sessionStart, sessionEnd, postToolUse, userPromptSubmitted
- `CopilotCliProvider.processEvent()` — handles 4 event types, creates/updates `AgentState`
- Session ID resolution: PID → scan for `inuse.<PID>.lock` → parent dir UUID
- `HookDeployer.deployCopilotCliHooks()` integration

> 📄 **Full implementation details**: See [`steps/step04.md`](implementation/steps/step04.md)

---

### Step 5: ui — System tray dashboard UI

**Goal**: Replace placeholder cards with live `AgentState` data from `AgentStateManager.agents` StateFlow.

**Key deliverables**:
- `Dashboard.kt` — observes `StateFlow<List<AgentState>>`, auto-updates on state changes
- `AgentCard.kt` — displays agent name, status indicator, PID, session ID, event count, last activity, uptime
- `Settings.kt` — hook deployment status per agent, re-deploy button, event directory path
- Status-based color indicators: 🟢 Running, 🟡 Idle, 🔴 Error, ⚪ Stopped
- Empty state: "No agents detected. Start an AI coding agent to see it here."

> 📄 **Full implementation details**: See [`steps/step05.md`](implementation/steps/step05.md)

---

### Step 6: claude — Claude Code hook provider

**Goal**: Deploy Claude Code hook config and implement `ClaudeCodeProvider.processEvent()`.

**Key deliverables**:
- Hook config: merge `PostToolUse` entry into `~/.claude/settings.json` (preserving existing settings)
- `ClaudeCodeProvider.processEvent()` — handles PostToolUse events, synthetic session start on first event per PID
- Session ID: PID-based (no explicit session ID in Claude Code hooks)
- `HookDeployer.deployClaudeCodeHooks()` — safe JSON merge logic

> 📄 **Full implementation details**: See [`steps/step06.md`](implementation/steps/step06.md)

---

### Step 7: cursor — Cursor hook provider

**Goal**: Deploy Cursor hook config and implement `CursorProvider.processEvent()`.

**Key deliverables**:
- Hook config: `~/.cursor/hooks.json` (Cursor v1.7+ first-class hooks support)
- `CursorProvider.processEvent()` — handles sessionStart, afterFileEdit, postToolUse events
- Session ID: `conversation_id` from hook payload
- `HookDeployer.deployCursorHooks()` integration

> 📄 **Full implementation details**: See [`steps/step07.md`](implementation/steps/step07.md)

---

### Step 8: codex-gemini — Codex + Gemini hook providers

**Goal**: Deploy hook configs and implement providers for both Codex CLI and Gemini CLI.

**Key deliverables**:
- Codex: `notify` command in `~/.codex/config.toml`, `CodexProvider.processEvent()`, session ID from `thread-id`
- Gemini: merge into `~/.gemini/settings.json`, `GeminiProvider.processEvent()`, PID-based session ID
- `HookDeployer.deployCodexHooks()` and `HookDeployer.deployGeminiHooks()`

> 📄 **Full implementation details**: See [`steps/step08.md`](implementation/steps/step08.md)

---

### Step 9: otlp — Embedded OTLP receiver [POST-MVP]

> **📦 POST-MVP**: OTLP receiver adds enrichment (token counts, cost) but is not needed for the hooks+FileWatch MVP.

**Goal**: Lightweight HTTP/JSON OTLP endpoint on localhost that receives Claude Code telemetry and enriches `AgentState` with token counts and cost data.

> 📄 **Full implementation details**: See [`steps/step09.md`](implementation/steps/step09.md)

---

### Step 10: packaging — Build, CI, README

**Goal**: Native `.dmg` packaging for macOS, GitHub Actions CI/CD pipeline, comprehensive README.

**Key deliverables**:
- `./gradlew packageDmg` produces signed `.dmg`
- GitHub Actions: build on push, test, SonarCloud analysis, release on tag
- README with installation, usage, architecture overview

> 📄 **Full implementation details**: See [`steps/step10.md`](implementation/steps/step10.md)

---

### Step 11: hotkey — Global hotkey (Ctrl+Shift+Backtick)

**Goal**: System-wide keyboard shortcut to toggle the agent-pulse window (same approach as JetBrains Toolbox).

**Key deliverables**:
- JNA + macOS Carbon `RegisterEventHotKey` (no Accessibility permission needed)
- Configurable shortcut in Settings
- Conflict detection via JBR `SystemShortcuts` API

> 📄 **Full implementation details**: See [`steps/step11.md`](implementation/steps/step11.md)

---

### Step 12: spotlight — macOS Spotlight integration

**Goal**: Index active `AgentState` sessions for macOS Spotlight search (e.g., "copilot myproject").

**Key deliverables**:
- Swift helper `spotlight-bridge` using `CSSearchableIndex`
- `SpotlightIndexer` Kotlin class calls Swift binary via `ProcessBuilder`
- Indexes agent name, session ID, cwd, status as Spotlight attributes
- Updates index on `AgentState` changes, removes on session end

> 📄 **Full implementation details**: See [`steps/step12.md`](implementation/steps/step12.md)

---

## MVP Scope

**v0.1 (Steps 1-5)** — Core functional dashboard (hooks + FileWatch):
- System tray app with popup panel
- Hook deployment for detected agents (Copilot CLI first)
- FileWatch on `~/.agent-pulse/events/` for real-time event detection
- Hook event parsing and session tracking
- Dashboard UI showing active agent sessions

**v0.2 (Steps 6-8)** — Multi-agent hooks:
- Claude Code hook deployment and event parsing
- Cursor hook deployment and event parsing
- Codex and Gemini hook deployment and event parsing

**v0.3 (Steps 9-10)** — Polish + release:
- .dmg build, CI, README
- Global hotkey (Ctrl+Shift+Backtick)
- macOS Spotlight integration

**Post-MVP (future enhancements):**
- Enrichment layer: read agent files for token counts, model, cost
- Embedded OTLP receiver for Claude Code/Cursor metrics
- Process scanning (OSHI) as fallback for agents without hooks
- Remote agent monitoring (agent-pulse HTTP API)
- Windows/Linux builds
- Historical session browser
- Notification system (alert on finish/error)

---

## Research References

This plan is informed by extensive research documented in companion files:

| Document | Content | Lines |
|---|---|---|
| `research/research-alternative.md` | Tech stack decision: why JBR over Tauri/Rust/FFM | 258 |
| `research/agent-research.md` | Agent hooks, safety analysis, delivery architecture, per-agent analysis, three-layer architecture | ~1,800 |

Key research findings that shaped this plan:
1. **Hooks are a stable API** — agents publish and maintain hook schemas. File system paths are internal implementation details with no API contract.
2. **Hook safety analysis** — hooks are synchronous/blocking in Copilot CLI, Claude Code, and Gemini. Our hook script must execute in <50ms. Disk-only design (no network) achieves ~30ms.
3. **Delivery mechanism comparison** — disk files + FileWatch is the only approach that is durable (survives restarts), has zero dependencies, and poses zero risk to agents. Named pipes block, stdout corrupts, HTTP adds latency.
4. **JBR FileWatch** — standard OpenJDK uses polling (2-10s) on macOS; JBR uses native FSEvents (~100-500ms). This drove the Kotlin/JBR choice.
5. **Read-only principle** — agent-pulse never writes to agent directories. Only writes to its own `~/.agent-pulse/` directory.

---

## Notes

- Hook script is 3 lines of POSIX sh with zero external dependencies
- Filename encodes metadata: `<timestamp>-<agent>-<event>-<ppid>.json`
- Session ID resolution happens in Kotlin, not in the hook script
- On first run, user must restart agent sessions after hook deployment
- Global hotkey uses JNA + Carbon `RegisterEventHotKey` (same as JetBrains Toolbox) — no Accessibility permission needed
- Post-MVP enrichment: Copilot CLI events.jsonl (2,517+ events/session), Claude Code OTel, Cursor SQLite, etc.
- Compose Desktop's `Tray` composable handles system tray natively
- JBR is bundled automatically by the Compose Gradle plugin — zero configuration
- The `LSUIElement` plist key makes the app a background app (no dock icon)
- Gradle wrapper is committed to the repo — no global Gradle install needed
- All 5 providers follow the same interface pattern — adding a new agent type is straightforward
