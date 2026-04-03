# agent-pulse — Final Implementation Plan

## Problem Statement

When running multiple AI coding agents (Copilot CLI, Claude Code, Codex, Cursor, Gemini) across terminals, IDEs, and remote environments, there's no single place to see what's running, their status, token usage, or session history. You have to flip between terminal tabs and IDE panels to piece together what's happening.

**agent-pulse** is a lightweight system tray app (like JetBrains Toolbox) that gives you a live dashboard of every AI agent running on your machine — and optionally remote ones too.

> **Note**: The tech stack is **Kotlin/Compose Desktop + JetBrains Runtime (JBR)**, as decided in `research-alternative.md`. Agent detection strategies are documented in `agent-research.md`.

---

## Critical Design Principles

### 🔒 Read-Only Access (MANDATORY)

**Everything agent-pulse touches on agents MUST be read-only.** We want to avoid any possibility of one agent corrupting another one, or agent-pulse interfering with any agent's operation.

This is a non-negotiable safety constraint that applies to every layer of the application:

| Layer | Rule | Implementation |
|---|---|---|
| **File system** | NEVER write, create, modify, or delete files in agent directories | Use `readText()`, `readLines()`, `readBytes()` only. Never `writeText()`, `createFile()`, `delete()` |
| **SQLite databases** | ALWAYS open in read-only mode | JDBC URL: `jdbc:sqlite:file:<path>?mode=ro`. Additionally set `PRAGMA query_only = ON` after connecting |
| **Process info** | NEVER signal, kill, or modify agent processes | OSHI read-only: `getProcesses()`, `getProcess()` only. No `kill()`, no signals |
| **File watching** | Watch ONLY — never create/modify/delete watched files | `WatchService.register()` with event kinds, never `Files.write()` on watched paths |
| **OTLP receiver** | RECEIVE data only — never push data to agents | Passive listener on localhost; agents push to us, we never push to them |
| **Agent config** | NEVER modify agent configuration files | Read `config.json`, `settings.json`, etc. but never write to them |

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

**Exception — OTLP auto-configuration**: The ONLY case where agent-pulse writes to an agent-related path is when the user explicitly opts in to Claude Code OTel auto-configuration, which writes to `/Library/Application Support/ClaudeCode/managed-settings.json`. This requires user confirmation AND admin privileges. This is clearly documented and opt-in.

### ⚠️ Safe Reading from Mid-Write Files (CRITICAL)

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
| **Runtime** | JetBrains Runtime (JBR) 25 LTS | Native FSEvents WatchService on macOS (see `research-alternative.md`) |
| **Process Scanning** | [OSHI](https://github.com/oshi/oshi) 6.6.x | Cross-platform process enumeration, no JNI |
| **File Watching** | `java.nio.file.WatchService` (JBR) | Native FSEvents on macOS via JBR, ~100ms latency |
| **SQLite** | [sqlite-jdbc](https://github.com/xerial/sqlite-jdbc) 3.46.x | Read-only access to agent databases (Copilot, Cursor) |
| **JSON** | kotlinx-serialization-json | Kotlin-native, fast, compile-time safe |
| **YAML** | [kaml](https://github.com/charleskorn/kaml) | Parse Copilot's `workspace.yaml` |
| **Coroutines** | kotlinx-coroutines | Background scanning, debouncing, state flow |
| **Global Hotkey** | [JNA](https://github.com/java-native-access/jna) 5.x + Carbon (macOS) | Same approach as JetBrains Toolbox — native `RegisterEventHotKey` via JNA |
| **Shortcut Conflicts** | [JBR API](https://jetbrains.github.io/JetBrainsRuntimeApi/) `SystemShortcuts` | Query existing OS shortcuts to avoid conflicts (JBR-specific) |
| **System Tray** | Compose `Tray` composable | Built-in tray support in Compose Desktop |
| **OTLP Receiver** | [Ktor](https://ktor.io/) HTTP server | Lightweight HTTP/JSON endpoint for OTel data |
| **Spotlight Bridge** | Swift CLI (bundled) | Core Spotlight integration on macOS |
| **Packaging** | Compose Gradle plugin (jpackage + jlink) | Bundles JBR automatically, produces `.dmg` |
| **Desktop Extras** | [JBR API](https://jetbrains.github.io/JetBrainsRuntimeApi/) | Custom title bars, rounded corners, HiDPI |

**Why not Tauri/Rust?** See `research-alternative.md` — the critical issue is macOS file watching. OpenJDK polls every 2-10 seconds; JBR uses native FSEvents. Since Compose Desktop bundles JBR automatically, the fix is zero-config.

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
│  │       4. Update StateFlow<List<Agent>>                            │ │
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
│  StateFlow<List<Agent>> → Compose UI reactively updates              │
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

Sourced from `agent-research.md` (comprehensive agent monitoring & extensibility research).

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
│   │   │   ├── Agent.kt
│   │   │   ├── AgentType.kt
│   │   │   ├── AgentStatus.kt
│   │   │   └── ProcessInfo.kt
│   │   ├── provider/
│   │   │   ├── AgentProvider.kt
│   │   │   ├── ProviderRegistry.kt
│   │   │   ├── CopilotCliProvider.kt       (Step 4)
│   │   │   ├── ClaudeCodeProvider.kt       (Step 6)
│   │   │   ├── CursorProvider.kt           (Step 7)
│   │   │   ├── CodexProvider.kt            (Step 8)
│   │   │   └── GeminiProvider.kt           (Step 8)
│   │   ├── detection/
│   │   │   ├── ProcessScanner.kt
│   │   │   ├── FileWatcher.kt
│   │   │   └── DetectionOrchestrator.kt
│   │   ├── otlp/
│   │   │   └── OtlpReceiver.kt            (Step 9)
│   │   ├── ui/
│   │   │   ├── App.kt
│   │   │   ├── Dashboard.kt
│   │   │   ├── AgentCard.kt
│   │   │   ├── Settings.kt
│   │   │   └── Theme.kt
│   │   ├── search/
│   │   │   ├── SearchIndexer.kt
│   │   │   ├── SpotlightIndexer.kt         (Step 11)
│   │   │   └── NoopIndexer.kt
│   │   └── util/
│   │       ├── ReadOnlyDb.kt
│   │       ├── SafeFileReader.kt
│   │       └── CachedFileState.kt
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

> ⚠️ **NEEDS REVISION**: This step was designed for process scanning + file reading. For hooks+FileWatch MVP, remove OSHI, SQLite, YAML deps. Replace `ProcessInfo` with `HookEvent`. Replace `AgentProvider.scan(processes)` with `AgentProvider.handleEvent(event)`. Remove `ReadOnlyDb`, `SafeFileReader`, `CachedFileState`. Add `HookEvent` data class parsed from filename+content.

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

---

### Step 3: detection — Hook event watcher

> ⚠️ **NEEDS REVISION**: This step was designed around OSHI process scanning + FileWatcher on agent dirs. For hooks+FileWatch MVP, replace entirely: single FileWatcher on `~/.agent-pulse/events/`, parse filename for metadata, read file for raw event JSON. No OSHI, no ProcessScanner, no process tree walking. Add HookDeployer for first-run hook installation.

**Goal**: Background detection engine that watches `~/.agent-pulse/events/` for hook event files, flowing results into a `StateFlow<List<Agent>>`.

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

---

### Step 4: copilot — Copilot CLI hook provider

> ⚠️ **NEEDS REVISION**: This step was designed around lock files + events.jsonl + session.db. For hooks+FileWatch MVP, replace with: Copilot CLI hook config deployment, sessionStart/postToolUse/sessionEnd event parsing, session ID resolution via PID→lock file lookup (in Kotlin). No events.jsonl reading, no workspace.yaml parsing, no session.db SQLite access.

**Goal**: Deploy Copilot CLI hook config, parse hook events, resolve session IDs, display Copilot CLI sessions on dashboard.

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

---

### Step 5: ui — System tray dashboard UI

**Goal**: Polished Compose Material 3 dashboard showing agents with status, fleet tree, and settings.

**Pre-check**: Step 4 PR is merged. `./gradlew run` detects Copilot agents.

**App state AFTER this step**: Dark-themed dashboard with agent cards. Each card shows: status dot, agent icon, name, model, PID, uptime, token bar, fleet children. Auto-updates when agents start/stop. Empty state when no agents. Settings panel for scan interval.

**Files to create**:
- `src/main/kotlin/com/agentpulse/ui/Theme.kt` — dark color scheme
- `src/main/kotlin/com/agentpulse/ui/AgentCard.kt` — per-agent card composable
- `src/main/kotlin/com/agentpulse/ui/Dashboard.kt` — agent list + header + empty state
- `src/main/kotlin/com/agentpulse/ui/Settings.kt` — scan interval, provider toggles
- `src/main/kotlin/com/agentpulse/ui/App.kt` — root composable with navigation

- [ ] **5.1 Create Theme.kt**
    - Custom dark color scheme matching a developer tool aesthetic
    - Colors: dark grays (background), accent blues/greens (status), muted text

  ```kotlin
  package com.agentpulse.ui

  import androidx.compose.material3.*
  import androidx.compose.runtime.Composable
  import androidx.compose.ui.graphics.Color

  val AgentPulseColors = darkColorScheme(
      primary = Color(0xFF64B5F6),        // Blue accent
      secondary = Color(0xFF81C784),       // Green accent
      background = Color(0xFF1A1A2E),      // Dark blue-gray
      surface = Color(0xFF16213E),         // Slightly lighter
      surfaceVariant = Color(0xFF1F2B47),  // Card background
      onPrimary = Color.White,
      onSecondary = Color.White,
      onBackground = Color(0xFFE0E0E0),
      onSurface = Color(0xFFE0E0E0),
      onSurfaceVariant = Color(0xFFA0A0B0), // Muted text
      error = Color(0xFFEF5350),
  )

  object StatusColors {
      val running = Color(0xFF4CAF50)
      val idle = Color(0xFFFFC107)
      val stopped = Color(0xFF9E9E9E)
      val error = Color(0xFFEF5350)
  }

  @Composable
  fun AgentPulseTheme(content: @Composable () -> Unit) {
      MaterialTheme(
          colorScheme = AgentPulseColors,
          content = content,
      )
  }
  ```

- [ ] **5.2 Create AgentCard.kt**
    - Status dot: green (Running), yellow (Idle), red (Stopped/Error)
    - Agent type icon from `AgentType.icon`
    - First line: icon + name (bold, truncate)
    - Second line: PID · session ID (first 8 chars) · uptime (formatted: "12m", "2h 5m")
    - Model line (if available)
    - CWD line (truncated)
    - Token bar: LinearProgressIndicator with label
    - Children: indented list with border-start

  ```kotlin
  package com.agentpulse.ui

  import androidx.compose.foundation.background
  import androidx.compose.foundation.layout.*
  import androidx.compose.foundation.shape.CircleShape
  import androidx.compose.material3.*
  import androidx.compose.runtime.Composable
  import androidx.compose.ui.Alignment
  import androidx.compose.ui.Modifier
  import androidx.compose.ui.draw.clip
  import androidx.compose.ui.text.font.FontWeight
  import androidx.compose.ui.text.style.TextOverflow
  import androidx.compose.ui.unit.dp
  import com.agentpulse.model.Agent
  import com.agentpulse.model.AgentStatus

  @Composable
  fun AgentCard(agent: Agent) {
      Card(
          modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp),
          colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
      ) {
          Column(modifier = Modifier.padding(12.dp)) {
              // Header row: status dot + icon + name
              Row(verticalAlignment = Alignment.CenterVertically) {
                  Box(
                      modifier = Modifier.size(10.dp).clip(CircleShape).background(
                          when (agent.status) {
                              AgentStatus.Running -> StatusColors.running
                              AgentStatus.Idle -> StatusColors.idle
                              AgentStatus.Stopped -> StatusColors.stopped
                              AgentStatus.Error -> StatusColors.error
                          }
                      )
                  )
                  Spacer(Modifier.width(8.dp))
                  Text(agent.agentType.icon, style = MaterialTheme.typography.titleMedium)
                  Spacer(Modifier.width(6.dp))
                  Text(
                      agent.summary ?: agent.agentType.displayName,
                      style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold),
                      maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f),
                  )
              }

              Spacer(Modifier.height(4.dp))

              // Metadata line: PID · session · uptime
              val uptimeStr = formatUptime(agent.startTime)
              Text(
                  "PID ${agent.pid} · ${agent.sessionId.take(8)} · $uptimeStr",
                  style = MaterialTheme.typography.bodySmall,
                  color = MaterialTheme.colorScheme.onSurfaceVariant,
              )

              // Model (if available)
              agent.model?.let { model ->
                  Text("Model: $model", style = MaterialTheme.typography.bodySmall,
                      color = MaterialTheme.colorScheme.onSurfaceVariant)
              }

              // CWD (truncated)
              agent.cwd?.let { cwd ->
                  Text(cwd.replace(System.getProperty("user.home"), "~"),
                      style = MaterialTheme.typography.bodySmall,
                      color = MaterialTheme.colorScheme.onSurfaceVariant,
                      maxLines = 1, overflow = TextOverflow.Ellipsis)
              }

              // Token bar (if available)
              agent.tokenUsage?.let { tokens ->
                  Spacer(Modifier.height(4.dp))
                  val maxTokens = 200_000L // Rough context window estimate
                  LinearProgressIndicator(
                      progress = { (tokens.toFloat() / maxTokens).coerceIn(0f, 1f) },
                      modifier = Modifier.fillMaxWidth().height(4.dp),
                  )
                  Text("${tokens / 1000}K tokens", style = MaterialTheme.typography.labelSmall,
                      color = MaterialTheme.colorScheme.onSurfaceVariant)
              }

              // Fleet children
              if (agent.children.isNotEmpty()) {
                  Spacer(Modifier.height(4.dp))
                  Column(modifier = Modifier.padding(start = 16.dp)) {
                      agent.children.forEach { child ->
                          Text("├─ ${child.name} (PID ${child.pid})",
                              style = MaterialTheme.typography.bodySmall,
                              color = MaterialTheme.colorScheme.onSurfaceVariant)
                      }
                  }
              }
          }
      }
  }

  private fun formatUptime(startTimeMs: Long): String {
      val elapsed = System.currentTimeMillis() - startTimeMs
      val minutes = elapsed / 60_000
      val hours = minutes / 60
      return when {
          hours > 0 -> "${hours}h ${minutes % 60}m"
          minutes > 0 -> "${minutes}m"
          else -> "<1m"
      }
  }
  ```

- [ ] **5.3 Create Dashboard.kt**
    - Takes `StateFlow<List<Agent>>` as parameter, collect as state
    - Header: "🫀 agent-pulse" + refresh button
    - Body: LazyColumn of AgentCards, or empty state
    - Footer: "N agents detected" + settings button

  ```kotlin
  package com.agentpulse.ui

  import androidx.compose.foundation.layout.*
  import androidx.compose.foundation.lazy.LazyColumn
  import androidx.compose.foundation.lazy.items
  import androidx.compose.material3.*
  import androidx.compose.runtime.Composable
  import androidx.compose.runtime.collectAsState
  import androidx.compose.runtime.getValue
  import androidx.compose.ui.Alignment
  import androidx.compose.ui.Modifier
  import androidx.compose.ui.unit.dp
  import com.agentpulse.model.Agent
  import kotlinx.coroutines.flow.StateFlow

  @Composable
  fun Dashboard(
      agentsFlow: StateFlow<List<Agent>>,
      onRefresh: () -> Unit,
      onOpenSettings: () -> Unit,
  ) {
      val agents by agentsFlow.collectAsState()

      Column(modifier = Modifier.fillMaxSize()) {
          // Header
          Row(
              modifier = Modifier.fillMaxWidth().padding(12.dp),
              verticalAlignment = Alignment.CenterVertically,
          ) {
              Text("🫀 agent-pulse", style = MaterialTheme.typography.titleLarge,
                  modifier = Modifier.weight(1f))
              IconButton(onClick = onRefresh) {
                  Text("⟳", style = MaterialTheme.typography.titleMedium)
              }
          }

          // Body: agent list or empty state
          if (agents.isEmpty()) {
              Box(modifier = Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                  Column(horizontalAlignment = Alignment.CenterHorizontally) {
                      Text("No agents detected", style = MaterialTheme.typography.bodyLarge,
                          color = MaterialTheme.colorScheme.onSurfaceVariant)
                      Text("Start a Copilot CLI, Claude Code, or Cursor session",
                          style = MaterialTheme.typography.bodySmall,
                          color = MaterialTheme.colorScheme.onSurfaceVariant)
                  }
              }
          } else {
              LazyColumn(modifier = Modifier.weight(1f)) {
                  items(agents, key = { it.id }) { agent -> AgentCard(agent) }
              }
          }

          // Footer
          Row(
              modifier = Modifier.fillMaxWidth().padding(12.dp),
              verticalAlignment = Alignment.CenterVertically,
          ) {
              Text("${agents.size} agent${if (agents.size != 1) "s" else ""} detected",
                  style = MaterialTheme.typography.bodySmall,
                  color = MaterialTheme.colorScheme.onSurfaceVariant,
                  modifier = Modifier.weight(1f))
              TextButton(onClick = onOpenSettings) { Text("Settings") }
          }
      }
  }
  ```

- [ ] **5.4 Create Settings.kt**
    - Scan interval selector (5s, 15s, 30s)
    - Provider enable/disable toggles
    - Global hotkey display (read-only for now)

  ```kotlin
  package com.agentpulse.ui

  import androidx.compose.foundation.layout.*
  import androidx.compose.material3.*
  import androidx.compose.runtime.*
  import androidx.compose.ui.Alignment
  import androidx.compose.ui.Modifier
  import androidx.compose.ui.unit.dp

  @Composable
  fun Settings(onBack: () -> Unit) {
      var scanInterval by remember { mutableStateOf(5) }

      Column(modifier = Modifier.fillMaxSize().padding(16.dp)) {
          Row(verticalAlignment = Alignment.CenterVertically) {
              TextButton(onClick = onBack) { Text("← Back") }
              Spacer(Modifier.width(8.dp))
              Text("Settings", style = MaterialTheme.typography.titleLarge)
          }

          Spacer(Modifier.height(16.dp))

          Text("Scan interval", style = MaterialTheme.typography.titleSmall)
          Row(verticalAlignment = Alignment.CenterVertically) {
              listOf(5, 15, 30).forEach { interval ->
                  FilterChip(
                      selected = scanInterval == interval,
                      onClick = { scanInterval = interval },
                      label = { Text("${interval}s") },
                      modifier = Modifier.padding(end = 8.dp),
                  )
              }
          }

          Spacer(Modifier.height(16.dp))
          Text("Global hotkey", style = MaterialTheme.typography.titleSmall)
          Text("Ctrl+Shift+` (configured in Step 11)",
              style = MaterialTheme.typography.bodySmall,
              color = MaterialTheme.colorScheme.onSurfaceVariant)
      }
  }
  ```

- [ ] **5.5 Create App.kt**
    - Navigation: Dashboard ↔ Settings
    - Wire to DetectionOrchestrator.agents StateFlow

  ```kotlin
  package com.agentpulse.ui

  import androidx.compose.runtime.*
  import com.agentpulse.detection.DetectionOrchestrator
  import kotlinx.coroutines.launch

  @Composable
  fun App(orchestrator: DetectionOrchestrator) {
      var screen by remember { mutableStateOf("dashboard") }
      val scope = rememberCoroutineScope()

      AgentPulseTheme {
          when (screen) {
              "dashboard" -> Dashboard(
                  agentsFlow = orchestrator.agents,
                  onRefresh = { scope.launch { orchestrator.performScan() } },
                  onOpenSettings = { screen = "settings" },
              )
              "settings" -> Settings(onBack = { screen = "dashboard" })
          }
      }
  }
  ```

- [ ] **5.6 Update Main.kt**
    - Replace placeholder UI with `App(orchestrator)` composable

- [ ] **5.7 Verify with real data**
    - Run with active Copilot sessions
    - Agent cards show correct data
    - Auto-refresh works when agents start/stop

- [ ] **5.8 Commit, push, and open PR**

---

### Step 6: claude — Claude Code hook provider

> ⚠️ **NEEDS REVISION**: Replace process scanning + file reading with hook-based monitoring. Deploy Claude Code hooks (merge into ~/.claude/settings.json). Parse PostToolUse/SessionStart events. Session ID from hook payload or session-health lookup.

**Goal**: Deploy Claude Code hook config, parse hook events, display Claude Code sessions on dashboard.

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

---

### Step 7: cursor — Cursor hook provider

> ⚠️ **NEEDS REVISION**: Replace process scanning + SQLite reading with hook-based monitoring. Deploy Cursor hooks (.cursor/hooks.json). Parse sessionStart/afterFileEdit/postToolUse events. Session ID from `conversation_id` in payload. No SQLite access.

**Goal**: Deploy Cursor hook config, parse hook events, display Cursor sessions on dashboard.

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

---

### Step 8: codex-gemini — Codex + Gemini hook providers

> ⚠️ **NEEDS REVISION**: Replace process scanning + file reading with hook-based monitoring. Codex: set `notify` command. Gemini: deploy hooks via settings.json merge. Parse respective event payloads.

**Goal**: Deploy Codex notify config and Gemini hook config, parse events, display sessions on dashboard.

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

---

### Step 9: otlp — Embedded OTLP receiver [POST-MVP]

> ℹ️ **DEFERRED TO POST-MVP**: OTLP receiver adds enrichment (token counts, cost) but is not needed for the hooks+FileWatch MVP. Keep in plan for future.

**Goal**: A lightweight HTTP/JSON OTLP endpoint on localhost that receives Claude Code telemetry and Cursor hook data.

**Pre-check**: Step 8 PR is merged.

**Why**: Without OTLP, Claude Code monitoring is "Basic" (process + MEMORY.md only). With it, Claude Code jumps to "Excellent" — tokens, cost, tool calls, session IDs, everything. See `agent-research.md` §Architectural Implication.

**App state AFTER**: Claude Code sessions (when OTel-configured) show full metrics in the dashboard. A setup wizard guides users through enabling telemetry.

- [ ] **9.1 Add Ktor dependency to build.gradle.kts**
  ```kotlin
  implementation("io.ktor:ktor-server-netty:2.3.12")
  implementation("io.ktor:ktor-server-content-negotiation:2.3.12")
  implementation("io.ktor:ktor-serialization-kotlinx-json:2.3.12")
  ```

- [ ] **9.2 Create OtlpReceiver.kt**
    - Start Ktor embedded server on `localhost:4318` (OTLP HTTP/JSON standard port)
    - `POST /v1/metrics` — parse OTLP metrics JSON, extract Claude Code metrics:
        - `claude_code.session.count`, `claude_code.token.usage`, `claude_code.cost.usage`
        - `claude_code.lines_of_code.count`, `claude_code.active_time.total`
    - `POST /v1/logs` — parse OTLP log events JSON, extract:
        - `claude_code.user_prompt`, `claude_code.tool_result`, `claude_code.api_request`
    - Route parsed data to the ClaudeCodeProvider for state updates
    - Handle port conflicts: if 4318 is taken, try 4320, log the chosen port
    - **This is a RECEIVE-only server — it NEVER pushes data to agents**

- [ ] **9.3 Create setup wizard UI**
    - One-time prompt: "Enable rich Claude Code monitoring?"
    - Display the env vars the user needs to set:
      ```
      export CLAUDE_CODE_ENABLE_TELEMETRY=1
      export OTEL_METRICS_EXPORTER=otlp
      export OTEL_LOGS_EXPORTER=otlp
      export OTEL_EXPORTER_OTLP_PROTOCOL=http/json
      export OTEL_EXPORTER_OTLP_ENDPOINT=http://localhost:4318
      ```
    - Offer to copy to clipboard
    - **Optional** (with explicit user consent): write managed settings to `/Library/Application Support/ClaudeCode/managed-settings.json` — this is the ONLY exception to the read-only rule and requires admin privileges

- [ ] **9.4 Wire OtlpReceiver into DetectionOrchestrator**
    - Start OTLP receiver on app launch
    - Route incoming metrics/events to ClaudeCodeProvider.updateFromOtel()

- [ ] **9.5 Verify** — configure Claude Code with OTel env vars, verify metrics appear in dashboard

- [ ] **9.6 Commit, push, and open PR**

---

### Step 10: packaging — Build, CI, README

**Goal**: .dmg build, GitHub Actions CI, comprehensive README.

**Pre-check**: Step 9 PR is merged.

- [ ] **10.1 Verify .dmg build**
  ```bash
  ./gradlew packageDmg
  ```
  Check `build/compose/binaries/main/dmg/` for output. Install and test.

- [ ] **10.2 Create .github/workflows/build.yml**
  ```yaml
  name: Build
  on:
    push:
      branches: [main]
    pull_request:
      branches: [main]

  jobs:
    build-macos:
      runs-on: macos-latest
      steps:
        - uses: actions/checkout@v4
        - uses: actions/setup-java@v4
          with:
            distribution: 'jetbrains'
            java-version: '25'
        - uses: gradle/actions/setup-gradle@v4
        - run: ./gradlew build
        - run: ./gradlew packageDmg
        - uses: actions/upload-artifact@v4
          with:
            name: agent-pulse-macos
            path: build/compose/binaries/main/dmg/*.dmg
  ```

- [ ] **10.3 Write README.md**
    - Title, tagline, feature list, supported agents table, install instructions, build from source, architecture overview, adding a provider guide, license (MIT)

- [ ] **10.4 Create GitHub repo and push**
  ```bash
  gh repo create agent-pulse --public --description "🫀 Universal AI Agent Dashboard" --source .
  git push -u origin main
  ```

- [ ] **10.5 Commit, push, and open PR**

---

### Step 11: hotkey — Global hotkey (Ctrl+Shift+Backtick)

**Goal**: Register a system-wide keyboard shortcut to toggle the agent-pulse window, matching JetBrains Toolbox's approach.

**Pre-check**: Step 10 PR is merged.

- [ ] **11.1 Add JNA and JBR API dependencies to build.gradle.kts**
  ```kotlin
  // Global hotkey via JNA + Carbon (same approach as JetBrains Toolbox)
  implementation("net.java.dev.jna:jna:5.15.0")
  implementation("net.java.dev.jna:jna-platform:5.15.0")

  // JBR API — SystemShortcuts for conflict detection, desktop extras
  implementation("org.jetbrains.runtime:jbr-api:1.10.1")
  ```

- [ ] **11.2 Create src/main/kotlin/com/agentpulse/GlobalHotKey.kt**
  This uses JNA to call macOS Carbon `RegisterEventHotKey` — the same approach JetBrains Toolbox uses.
  Carbon is technically deprecated by Apple but still functional in current macOS and used by all JetBrains products, Alfred, Raycast, and many other system tray apps.
  ```kotlin
  package com.agentpulse

  import com.sun.jna.*
  import com.sun.jna.ptr.PointerByReference

  /**
   * Global hotkey registration via JNA + macOS Carbon.
   * Same approach as JetBrains Toolbox for system-wide shortcuts.
   *
   * Why not JNativeHook? JNativeHook uses CGEventTap which requires
   * Accessibility permissions. Carbon RegisterEventHotKey does not.
   * JetBrains Toolbox uses this Carbon approach for the same reason.
   *
   * Why not JBR API? JBR API's SystemShortcuts is read-only (queries
   * existing OS shortcuts). It doesn't register new global hotkeys.
   * We use it for conflict detection, not for registration.
   */
  class GlobalHotKey(private val onTrigger: () -> Unit) {

      @Suppress("FunctionName")
      interface CarbonLib : Library {
          companion object {
              val INSTANCE: CarbonLib = Native.load("Carbon", CarbonLib::class.java)
          }
          fun GetApplicationEventTarget(): Pointer
          fun RegisterEventHotKey(
              keyCode: Int, modifiers: Int, id: EventHotKeyID.ByValue,
              target: Pointer, options: Int, outRef: PointerByReference
          ): Int
          fun UnregisterEventHotKey(hotKeyRef: Pointer): Int
          fun InstallEventHandler(
              target: Pointer, handler: Pointer, numTypes: Int,
              list: Pointer, userData: Pointer?, outRef: PointerByReference?
          ): Int
      }

      @Structure.FieldOrder("signature", "id")
      open class EventHotKeyID : Structure() {
          @JvmField var signature: Int = 0
          @JvmField var id: Int = 0
          class ByValue : EventHotKeyID(), Structure.ByValue
      }

      private var hotKeyRef: Pointer? = null

      fun register() {
          try {
              val carbon = CarbonLib.INSTANCE
              val id = EventHotKeyID.ByValue().apply {
                  signature = 0x4150  // 'AP' for agent-pulse
                  id = 1
              }
              val outRef = PointerByReference()
              // Ctrl=0x1000, Shift=0x0200, Backtick keycode=50 on macOS
              val result = carbon.RegisterEventHotKey(
                  50, 0x1000 or 0x0200, id,
                  carbon.GetApplicationEventTarget(), 0, outRef
              )
              if (result == 0) {
                  hotKeyRef = outRef.value
                  println("[agent-pulse] Global hotkey registered: Ctrl+Shift+Backtick")
              } else {
                  System.err.println("[agent-pulse] Hotkey registration failed (code $result)")
              }
          } catch (e: Exception) {
              System.err.println("[agent-pulse] Global hotkey unavailable: ${e.message}")
          }
      }

      fun unregister() {
          hotKeyRef?.let {
              try { CarbonLib.INSTANCE.UnregisterEventHotKey(it) } catch (_: Exception) {}
          }
      }
  }
  ```
  > **Note**: The event handler wiring (InstallEventHandler + callback) needs additional JNA callback plumbing. The above is the registration skeleton — the implementation agent should complete the Carbon event loop integration. If Carbon proves too complex, fall back to JNativeHook as a simpler alternative (but note it requires Accessibility permissions).

- [ ] **11.3 Wire GlobalHotKey into Main.kt**
  Add to the `application` block in Main.kt:
  ```kotlin
  val hotkey = remember { GlobalHotKey { isVisible = !isVisible } }
  LaunchedEffect(Unit) { hotkey.register() }
  ```
  Update the Quit menu item to call `hotkey.unregister()` before `exitApplication()`.

- [ ] **11.4 Verify** — `Ctrl+Shift+Backtick` toggles the window (may need macOS accessibility permission)

- [ ] **11.5 Commit, push, and open PR**
  ```bash
  git commit -m "feat: add global hotkey (Ctrl+Shift+Backtick) via JNA+Carbon

  - JNA + macOS Carbon RegisterEventHotKey (same approach as JetBrains Toolbox)
  - No Accessibility permission needed (unlike CGEventTap/JNativeHook)
  - JBR API SystemShortcuts for future conflict detection

  Co-authored-by: Copilot <223556219+Copilot@users.noreply.github.com>"
  ```

---

### Step 12: spotlight — macOS Spotlight integration

**Goal**: Running agents appear in macOS Spotlight with rich metadata and deep links.

**Pre-check**: Step 11 PR is merged.

- [ ] **12.1 Create spotlight-bridge Swift CLI**
    - `spotlight-bridge/Package.swift` — swift-tools-version 5.9, platforms macOS 12+, CoreSpotlight + CoreServices
    - `spotlight-bridge/Sources/main.swift` — read JSON from stdin, dispatch to CSSearchableIndex
    - Build: `cd spotlight-bridge && swift build -c release`

  ```swift
  // Package.swift
  // swift-tools-version: 5.9
  import PackageDescription
  let package = Package(
      name: "spotlight-bridge",
      platforms: [.macOS(.v12)],
      targets: [
          .executableTarget(name: "spotlight-bridge", path: "Sources"),
      ]
  )
  ```

  ```swift
  // Sources/main.swift — reads JSON commands from stdin, indexes to Spotlight
  import CoreSpotlight
  import Foundation

  struct IndexCommand: Codable {
      let action: String  // "add" or "remove"
      let id: String
      let title: String?
      let description: String?
      let agentType: String?
  }

  let index = CSSearchableIndex.default()

  while let line = readLine() {
      guard let data = line.data(using: .utf8),
            let cmd = try? JSONDecoder().decode(IndexCommand.self, from: data) else { continue }

      switch cmd.action {
      case "add":
          let attrs = CSSearchableItemAttributeSet(contentType: .item)
          attrs.title = cmd.title
          attrs.contentDescription = cmd.description
          attrs.keywords = [cmd.agentType ?? "agent", "ai", "coding"]
          let item = CSSearchableItem(uniqueIdentifier: cmd.id,
                                       domainIdentifier: "com.agentpulse",
                                       attributeSet: attrs)
          index.indexSearchableItems([item]) { error in
              if let error { fputs("Index error: \(error)\n", stderr) }
          }
      case "remove":
          index.deleteSearchableItems(withIdentifiers: [cmd.id]) { _ in }
      default:
          fputs("Unknown action: \(cmd.action)\n", stderr)
      }
  }
  ```

- [ ] **12.2 Create SpotlightIndexer.kt**
    - Implements `SearchIndexer`
    - Spawns `spotlight-bridge` CLI, pipes JSON to stdin (read-only: we index our own data, not agent data)
    - Rich descriptions for semantic search

  ```kotlin
  class SpotlightIndexer : SearchIndexer {
      private var process: Process? = null
      private var writer: java.io.BufferedWriter? = null

      fun start() {
          val bridgePath = // Resolve bundled spotlight-bridge binary
              Path.of(System.getProperty("compose.application.resources.dir") ?: ".")
                  .resolve("spotlight-bridge")
          if (!bridgePath.exists()) return

          process = ProcessBuilder(bridgePath.toString())
              .redirectErrorStream(false).start()
          writer = process!!.outputStream.bufferedWriter()
      }

      override fun add(agent: Agent) {
          val cmd = buildJsonObject {
              put("action", "add")
              put("id", agent.id)
              put("title", "${agent.agentType.icon} ${agent.summary ?: agent.agentType.displayName}")
              put("description", buildString {
                  append("${agent.agentType.displayName} agent")
                  agent.model?.let { append(" · $it") }
                  agent.cwd?.let { append(" · $it") }
              })
              put("agentType", agent.agentType.name)
          }.toString()
          writer?.write(cmd)
          writer?.newLine()
          writer?.flush()
      }

      override fun remove(id: String) {
          val cmd = """{"action":"remove","id":"$id"}"""
          writer?.write(cmd)
          writer?.newLine()
          writer?.flush()
      }

      fun stop() { writer?.close(); process?.destroy() }
  }
  ```

- [ ] **12.3 Wire into DetectionOrchestrator**
    - Replace `NoopIndexer` with `SpotlightIndexer` on macOS

- [ ] **12.4 Test end-to-end**
    - Build app, start agents, Cmd+Space → type "copilot" → see results

- [ ] **12.5 Commit, push, and open PR**

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
| `research-alternative.md` | Tech stack decision: why JBR over Tauri/Rust/FFM | 258 |
| `agent-research.md` | Agent hooks, safety analysis, delivery architecture, per-agent analysis, three-layer architecture | ~1,800 |

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
