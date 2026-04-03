# Agent Monitoring & Extensibility Research

## Purpose

This is the single comprehensive research document for agent-pulse's agent detection, monitoring, and data collection strategy. It covers:

- **Detection**: How to discover running AI agent sessions via process scanning and file watching
- **Hooks**: Push-based lifecycle events from each agent's native hook system
- **OTel**: OpenTelemetry integration for rich token/cost/tool telemetry
- **Per-agent analysis**: Detailed breakdown of every supported agent's artifacts, process signatures, and extractable data
- **Architecture**: The three-layer model (Discovery → Live Events → Enrichment)

**Key finding: ALL 5 agents support push-based lifecycle hooks. Copilot CLI has the richest passive file data; Claude Code has the richest OTel data; Cursor has centralized SQLite + hooks. The architecture uses a three-layer model: Discovery (files) → Live Events (hooks) → Enrichment (OTel + files).**

---

## Table of Contents

1. [Three-Layer Architecture](#three-layer-architecture)
2. [Layer 1: Discovery](#layer-1-discovery--what-sessions-exist-right-now)
   - [Process Scanning with OSHI](#process-scanning-with-oshi)
   - [File System Watching](#file-system-watching)
   - [Per-Agent Discovery Signals](#per-agent-discovery-signals)
   - [Combining Detection Layers](#combining-detection-layers)
3. [Layer 2: Live Events](#layer-2-live-events--whats-happening-in-session-x)
   - [Per-Agent Hook Systems](#per-agent-hook-systems)
   - [PPID + Session ID Correlation](#ppid--session-id-correlation)
   - [Hook Merging Behavior](#hook-merging-behavior)
4. [Layer 3: Enrichment](#layer-3-enrichment--tokens-model-full-history)
   - [Per-Agent Metadata](#per-agent-metadata)
   - [Token Usage & Cost Data](#token-usage--cost-data)
   - [Embedded OTLP Receiver](#embedded-otlp-receiver)
5. [Agent-by-Agent Deep Dives](#agent-by-agent-deep-dives)
   - [GitHub Copilot CLI](#github-copilot-cli)
   - [GitHub Copilot in VS Code](#github-copilot-in-vs-code)
   - [GitHub Copilot in IntelliJ](#github-copilot-in-intellij)
   - [Claude Code CLI](#claude-code-cli)
   - [Claude Code in VS Code](#claude-code-in-vs-code)
   - [OpenAI Codex CLI](#openai-codex-cli)
   - [Google Gemini CLI](#google-gemini-cli)
   - [Cursor IDE & CLI](#cursor-ide--cli)
6. [Extractable Information Summary](#extractable-information-summary)
7. [MCP as Universal Connection](#mcp-as-universal-connection)
8. [Hook Deployment Strategy](#hook-deployment-strategy)
9. [Product Positioning](#product-positioning)
10. [Sources](#sources)

---

## Three-Layer Architecture

### Core Requirement

**"See every session of every agent that is running."**

The dashboard must show a complete, always-accurate list of all active AI agent sessions, regardless of when agent-pulse started or restarted.

### Why Hooks Alone Are Not Enough

1. **Missed starts:** If agent-pulse launches after agents are already running, their `sessionStart` hooks already fired. Those sessions would be invisible.
2. **Restarts:** If agent-pulse restarts, all in-memory hook state is lost. Running sessions vanish from the dashboard until their next hook event fires.
3. **Fallback:** Some agents may not have hooks configured yet. Those sessions would be completely invisible without file scanning.

### The Three Layers

```
Layer 1: DISCOVERY     → "What sessions exist right now?"
Layer 2: LIVE EVENTS   → "What's happening in session X?"
Layer 3: ENRICHMENT    → "Tokens? Model? Full history?"
```

**Layer 1 — Discovery (file scanning + process validation):**
Source of truth for the dashboard. Scans lock files, session directories, and validates PIDs on startup and via FileWatcher.

| Agent | Discovery Signal |
|---|---|
| **Copilot CLI** | `~/.copilot/session-state/*/inuse.<PID>.lock` — PID in filename, UUID = parent dir |
| **Claude Code** | `~/.claude/session-health/{session-id}.lock` — JSON with PID, version, heartbeat |
| **Cursor** | `chatSessions/*.json` with `status` field (InProgress/Completed) + `code.lock` for app PID |
| **Codex** | Rollout JSONL in `~/.codex/sessions/*/` — first line = `session_meta` |
| **Gemini** | `~/.gemini/sessions/*.json` — `lastUpdated` recency heuristic |

**Layer 2 — Live Events (hooks POST to localhost:9876):**
Real-time feed for sessions already known from Layer 1. Makes the dashboard live.

| Agent | Hook Events |
|---|---|
| **Copilot CLI** | 8 events: sessionStart/End, userPromptSubmitted, pre/postToolUse, agentStop, subagentStop, errorOccurred |
| **Claude Code** | 9 events: SessionStart/End, PreToolUse/PostToolUse, Stop, SubagentStop, UserPromptSubmit, PreCompact, Notification |
| **Gemini** | 6 events: SessionStart/End, BeforeTool/AfterTool, BeforeModel/AfterModel |
| **Codex** | 1 event: notify per turn (with thread-id, messages, cwd) |
| **Cursor** | 11+ events: sessionStart/End, preToolUse/postToolUse, beforeShellExecution, afterFileEdit, beforeSubmitPrompt, stop, beforeMCPExecution/afterMCPExecution, preCompact |

**Layer 3 — Enrichment (OTel + file reading):**
Deep data for metrics, charts, cost tracking.

| Agent | Enrichment Source | What It Adds |
|---|---|---|
| **Copilot CLI** | `events.jsonl` + OTel | Token counts, model, tool history, session summary |
| **Claude Code** | OTLP export | Per-request tokens, cost_usd, cache metrics |
| **Cursor** | `state.vscdb` SQLite | Timing, context %, conversation history |
| **Codex** | Rollout JSONL | Tool calls, message history |
| **Gemini** | Session JSON | Interaction count, lastUpdated |

### How They Work Together

```
[Agent hooks] ──POST──→ [HTTP Receiver :9876] ──→ [Live Events Layer 2]
                                                         │
[FileWatcher] ──events──→ [Discovery Layer 1] ──────────→ [Dashboard State]
                                                         │
[File Reader / OTel] ──→ [Enrichment Layer 3] ──────────→ [Metrics/Details]
```

**Startup:** Discovery scans all paths → finds all sessions → populates dashboard
**Steady-state:** FileWatcher + hooks → real-time updates
**Restart recovery:** Discovery rescans → rediscovers all running sessions from lock files

---

## Layer 1: Discovery — "What Sessions Exist Right Now?"

agent-pulse uses three complementary detection mechanisms within the discovery layer to identify running AI agents:

| Mechanism | What It Detects | Latency |
|---|---|---|
| **Process Scanning** (OSHI) | Running agent processes by name/path/args | ~50-200ms per scan |
| **File System Watching** (JBR FSEvents) | Lock files appearing/disappearing, session state changes | ~100-500ms (FSEvents coalescing) |
| **Metadata Parsing** | Session details: workspace, first message, timestamps, working directory | On-demand after detection |

**Why all three?**

- **Process scanning alone** can tell you "a copilot process is running" but not which project it's working on or what session it belongs to.
- **File watching alone** can tell you "a new lock file appeared in session X" but not whether the process is still alive (a crash might leave orphaned lock files).
- **Metadata parsing** enriches detections with context: the workspace folder, session name, conversation history, and token usage.

The combination gives agent-pulse high-confidence, real-time, context-rich agent monitoring.

---

### Process Scanning with OSHI

#### What We Scan For

Each AI agent runs as one or more OS processes with distinctive names, paths, or command-line arguments. The table below lists confirmed process signatures observed on a real macOS system and corroborated with documentation:

| Agent | Process Name(s) | Distinctive Command-Line Pattern | Source |
|---|---|---|---|
| **Copilot CLI** | `copilot` | `/opt/homebrew/Caskroom/copilot-cli/*/copilot` | Live `ps aux` observation |
| **Copilot CLI (ACP mode)** | `copilot` | `copilot --acp --stdio` | Live `ps aux` observation |
| **Copilot CLI (VS Code shim)** | `Code Helper (Plugin)` | `copilotCli/copilotCLIShim.js` in args | Live `ps aux` observation |
| **Copilot IntelliJ** | `copilot-language-server` | Path contains `github-copilot-intellij/copilot-agent/native/` | Live `ps aux` observation |
| **Copilot IntelliJ (ACP)** | `copilot-language-server` | `copilot-language-server --acp` | Live `ps aux` observation |
| **Claude Code CLI** | `claude` | Binary path or `claude` command | [Claude Code docs](https://code.claude.com/docs/en/claude-directory) |
| **Codex CLI** | `codex` | Binary path or `codex` / `codex-tui` | [OpenAI Codex docs](https://developers.openai.com/codex/cli) |
| **Gemini CLI** | `gemini` | Binary path or `gemini` command | [Gemini CLI docs](https://github.com/google-gemini/gemini-cli) |
| **Cursor IDE** | `Cursor`, `Cursor Helper` | Electron app bundle | [Cursor docs](https://cursor.com/docs/agent/overview) |

#### Real-World Process Evidence

The following was captured from a live macOS system running multiple Copilot CLI sessions:

```
# Three separate Copilot CLI sessions running simultaneously:
user  3251   /opt/homebrew/Caskroom/copilot-cli/0.0.420/copilot
user  94074  /opt/homebrew/Caskroom/copilot-cli/0.0.420/copilot
user  84295  /opt/homebrew/Caskroom/copilot-cli/0.0.420/copilot

# VS Code shim processes launching copilot:
user  3233   Code Helper (Plugin) .../copilotCli/copilotCLIShim.js
user  3232   /bin/sh .../copilotCli/copilot

# IntelliJ's native copilot agent:
user  92968  .../github-copilot-intellij/copilot-agent/native/darwin-arm64/copilot-language-server --stdio

# IntelliJ ACP integration:
user  93078  /opt/homebrew/bin/copilot --acp --stdio
user  92984  node .../copilot-language-server --acp
```

**Key observations:**
1. Each Copilot CLI terminal session spawns **two processes**: a wrapper `copilot` (thin shell) and the actual `/opt/homebrew/Caskroom/copilot-cli/*/copilot` (the Node.js runtime).
2. VS Code launches Copilot CLI through its extension host (`Code Helper (Plugin)`) via a shim script.
3. IntelliJ uses a **different binary** (`copilot-language-server`) bundled with the plugin, not the Homebrew-installed CLI.
4. ACP mode is identifiable by the `--acp` flag in command-line arguments.

#### OSHI API for Process Detection

[OSHI](https://github.com/oshi/oshi) (Operating System and Hardware Information) is a pure-Java library that provides cross-platform access to OS-level process information without JNI or native dependencies.

```kotlin
import oshi.SystemInfo
import oshi.software.os.OSProcess

val si = SystemInfo()
val os = si.operatingSystem

// Get all processes
val allProcesses: List<OSProcess> = os.getProcesses(null, null)

// For each process, we can extract:
for (proc in allProcesses) {
    proc.processID          // PID (Int)
    proc.parentProcessID    // Parent PID (for process tree walking)
    proc.name               // Process name (e.g., "copilot")
    proc.path               // Full executable path
    proc.commandLine        // Full command line with arguments
    proc.arguments          // Parsed argument list
    proc.user               // Owner username
    proc.startTime          // Start time (epoch millis)
    proc.state              // Process state (RUNNING, SLEEPING, etc.)
    proc.residentSetSize    // Memory usage (bytes)
    proc.processCpuLoadCumulative  // CPU load
}

// Get a specific process by PID (useful for lock file cross-referencing)
val proc: OSProcess? = os.getProcess(3251)
```

agent-pulse matches processes against known signatures using a combination of:
- **Process name** (fast initial filter)
- **Executable path** (distinguishes Homebrew copilot from IntelliJ copilot-language-server)
- **Command-line arguments** (identifies ACP mode, session flags, etc.)

#### Detection Algorithm

```
EVERY 2 SECONDS (or on file watch trigger):
  1. Get all processes from OSHI
  2. For each process, check against known agent signatures:
     - Match process name against known names (copilot, claude, codex, gemini, cursor, ...)
     - If name matches, inspect path and command-line for confirmation
     - Walk parent process tree to identify IDE context (VS Code, IntelliJ, Cursor, ...)
  3. For each matched agent process:
     - Check if we already know about this PID
     - If new: create agent session entry, read associated metadata
     - If known: update resource usage (CPU, memory)
  4. For previously known PIDs no longer in process list:
     - Mark session as ended
     - Check for orphaned lock files
```

Source: [OSHI GitHub](https://github.com/oshi/oshi), [OSHI OSProcess API](https://www.oshi.ooo/oshi-core-java11/apidocs/com.github.oshi/oshi/software/os/OSProcess.html)

---

### File System Watching

#### Directories to Watch

agent-pulse registers the following directories with JBR's native FSEvents WatchService, using the `FILE_TREE` modifier for recursive watching:

| Directory | Agent | Watch Mode | Rationale |
|---|---|---|---|
| `~/.copilot/session-state/` | Copilot CLI, Copilot VS Code | Recursive (FILE_TREE) | Lock files in UUID subdirectories |
| `~/.claude/` | Claude Code (CLI + VS Code) | Recursive (FILE_TREE) | Project memory, debug logs, session-health |
| `~/.codex/sessions/` | Codex CLI | Recursive (FILE_TREE) | Rollout files by date |
| `~/.gemini/tmp/` | Gemini CLI | Recursive (FILE_TREE) | Chat files by project hash |

#### What Events We Care About

| Event | Meaning | Action |
|---|---|---|
| `ENTRY_CREATE` in `session-state/<UUID>/` | New lock file or session file | New session detected; parse metadata |
| `ENTRY_DELETE` in `session-state/<UUID>/` | Lock file removed | Session ended; update status |
| `ENTRY_MODIFY` in `session-state/<UUID>/` | events.jsonl updated | Session is active; update activity timestamp |
| `ENTRY_CREATE` in `sessions/YYYY/MM/DD/` | New Codex rollout | New Codex session detected |
| `ENTRY_CREATE` in `tmp/<hash>/chats/` | New Gemini chat | New Gemini session detected |

#### The Lock File Pattern (Copilot CLI Deep Dive)

Copilot CLI's lock file mechanism is the most reliable detection signal we've found. Here's exactly how it works, verified from live file system observation:

**Directory structure of a Copilot CLI session:**

```
~/.copilot/session-state/<UUID>/
    events.jsonl              # Full conversation log (every interaction, tool call, file edit)
    session.db                # SQLite database with session state
    workspace.yaml            # Working directory and git context
    plan.md                   # Session plan (if using planning mode)
    vscode.metadata.json      # VS Code integration metadata
    vscode.requests.metadata.json
    inuse.<PID>.lock          # Lock file — THE detection signal
    checkpoints/              # Session compaction checkpoints
    files/                    # Persistent session artifacts
    research/                 # Research artifacts
```

**Lock file format** (confirmed from live system):
- **Filename**: `inuse.<PID>.lock` where `<PID>` is the OS process ID
- **Contents**: Just the PID number as plain text (e.g., `3251`)
- **Lifecycle**:
  1. When a Copilot CLI process (PID 3251) opens a session, it creates `inuse.3251.lock`
  2. The lock file persists for the lifetime of the session
  3. When the session ends (or the process crashes), the lock file is removed
  4. Multiple processes can share a session: we observed `inuse.3251.lock` AND `inuse.84295.lock` in the same session directory, indicating two Copilot CLI processes accessing the same session

**Cross-referencing lock files with processes:**

This is the critical insight: by reading the PID from the lock filename, agent-pulse can:
1. Verify the process is still alive (via OSHI or `/proc/<PID>` equivalent)
2. Get the process details (command line, CPU usage, memory, start time)
3. Detect orphaned locks (process dead but lock file remains) for cleanup/crash detection

**Real example from the observed system:**

```
Session: a1b2c3d4-e5f6-7890-abcd-ef1234567890
Lock files:
  inuse.3251.lock   -> PID 3251 (copilot CLI in terminal s005)
  inuse.84295.lock  -> PID 84295 (copilot CLI in terminal s000)
Metadata:
  workspace: /home/user/Projects
  firstMessage: "create a directory and git init that..."
```

#### What about `vscode.metadata.json`?

This file provides rich context for sessions connected to VS Code:

```json
{
  "writtenToDisc": true,
  "workspaceFolder": {
    "folderPath": "/home/user/Projects",
    "timestamp": 1775082111325
  },
  "firstUserMessage": "create a directory and git init that..."
}
```

This tells agent-pulse:
- Which workspace folder the session is attached to
- The first user message (useful for session identification in the UI)
- When the workspace was connected

#### Why Not Watch Project-Level Directories?

Cursor (`.cursor/`) stores state in project directories rather than a centralized home-directory location. Watching these would require knowing all active project paths upfront. Instead, agent-pulse:

1. **Discovers projects via process scanning**: When a Cursor process is found, extract the working directory from the process command line or `/proc/<PID>/cwd` equivalent
2. **Dynamically registers watches**: Add the discovered project's `.cursor/` directory to the watch list
3. **Removes watches**: When the process exits, unregister the project-level watch

This hybrid approach avoids watching the entire filesystem while still catching project-local state changes.

---

### Per-Agent Discovery Signals

#### What Each Agent Writes to Disk

| Signal | Copilot CLI | Claude Code | Cursor | Codex | Gemini |
|---|---|---|---|---|---|
| Per-session lock file | ✅ `inuse.<PID>.lock` | ✅ `~/.claude/session-health/{id}.lock` (JSON w/ PID, version, heartbeat) | ❌ App-level `code.lock` only | ❌ | ❌ |
| Session start signal | Lock created | Lock created | chatSession JSON created | Rollout JSONL with `session_meta` | Session JSON created |
| Session end signal | Lock deleted + shutdown event | Lock deleted | `status=Completed` | ❌ Nothing | ❌ Nothing |
| Last activity | JSONL append time | Lock `lastHeartbeat` | `timing.lastRequestEnded` | Rollout file mtime | `lastUpdated` field |

#### Honest Assessment

- **Copilot CLI + Claude Code**: Definitive lock-file-based lifecycle ✅
- **Cursor**: App-level detection + per-session files with status field ⚠️
- **Codex + Gemini**: Start signal only, no end marker — timestamp heuristic ⚠️

---

### Combining Detection Layers

#### The Full Detection Pipeline

```
                    ┌──────────────────────┐
                    │   File System Watch   │
                    │  (JBR FSEvents)       │
                    │                       │
                    │ ~/.copilot/session-state/
                    │ ~/.claude/            │
                    │ ~/.codex/sessions/    │
                    │ ~/.gemini/tmp/        │
                    └────────┬─────────────┘
                             │ Lock file / session file events
                             ▼
                    ┌──────────────────────┐
                    │   Event Processor    │
                    │                       │
                    │ 1. Parse event type   │
                    │ 2. Identify agent     │
                    │ 3. Extract PID from   │
                    │    lock filename      │
                    │ 4. Cross-reference    │
                    │    with process scan  │
                    └────────┬─────────────┘
                             │
                             ▼
    ┌──────────────────────────────────────────────┐
    │              Agent Registry                   │
    │                                               │
    │  Session UUID │ Agent Type │ PIDs │ Status    │
    │  ─────────────┼────────────┼──────┼────────── │
    │  a1b2c3d4...  │ Copilot CLI│ 3251 │ Active   │
    │  cb556cf1...  │ Copilot CLI│94074 │ Active   │
    │  <hash>       │ Claude Code│ 5678 │ Active   │
    │  ...          │ ...        │ ...  │ ...      │
    └────────┬──────────────────────────────────────┘
             │
             ▼
    ┌──────────────────────────────────────────────┐
    │            Metadata Enrichment                │
    │                                               │
    │ For each session, read:                       │
    │ - vscode.metadata.json (workspace, 1st msg)   │
    │ - workspace.yaml (git context)                │
    │ - events.jsonl (activity timeline)            │
    │ - MEMORY.md (Claude project memory)           │
    │ - state.vscdb (Cursor session metadata)       │
    │ - agent-transcripts/*.jsonl (Cursor history)  │
    └────────┬──────────────────────────────────────┘
             │
             ▼
    ┌──────────────────────────────────────────────┐
    │         Process Scanner (OSHI)                │
    │         (runs every 2-5 seconds)              │
    │                                               │
    │ - Confirms known PIDs are still alive         │
    │ - Discovers new agents (Claude, Codex, etc.)  │
    │ - Detects IntelliJ Copilot (no file state)    │
    │ - Updates CPU/memory metrics                  │
    │ - Walks process trees for IDE context          │
    └──────────────────────────────────────────────┘
```

#### Detection Confidence by Agent

| Agent | Process Detection | File Detection | Combined Confidence |
|---|---|---|---|
| **Copilot CLI** | High (distinctive process name) | Very High (lock files with PID) | **Very High** |
| **Copilot VS Code** | High (shim process) | Very High (lock files + vscode.metadata.json) | **Very High** |
| **Copilot IntelliJ** | High (copilot-language-server) | None (no file state) | **High** |
| **Claude Code CLI** | High (distinctive process name) | Medium (no lock files) | **High** |
| **Claude Code VS Code** | High (same as CLI) | Medium (same as CLI) | **High** |
| **Codex CLI** | High (distinctive process name) | Medium (rollout files, no locks) | **High** |
| **Gemini CLI** | High (distinctive process name) | Medium (chat files) | **High** |
| **Cursor IDE** | High (distinctive app name) | Very High (centralized SQLite + agent transcripts) | **Very High** |

---

## Layer 2: Live Events — "What's Happening in Session X?"

### Per-Agent Hook Systems

#### Claude Code — Hooks System ⭐⭐⭐

**Source**: `.claude/settings.json` or `.claude-plugin/` directory

**Available hook events:**
- `SessionStart` — fires when session begins
- `SessionEnd` — fires when session ends
- `PreToolUse` / `PostToolUse` — before/after tool execution
- `Stop` — when agent considers stopping
- `SubagentStop` — when subagents complete
- `UserPromptSubmit` — on user input
- `PreCompact` — before context compaction
- `Notification` — on user notifications

**Hook types:**
1. `command` — execute bash script (deterministic, fast)
2. `prompt` — LLM-driven reasoning

**Configuration:**
```json
{
  "hooks": {
    "SessionStart": [{
      "matcher": "",
      "hooks": [{
        "type": "command",
        "command": "~/.agent-pulse/hooks/report.sh"
      }]
    }],
    "SessionEnd": [{
      "matcher": "",
      "hooks": [{
        "type": "command",
        "command": "~/.agent-pulse/hooks/report.sh"
      }]
    }]
  }
}
```

**Also supports:** Full plugin system (`.claude-plugin/plugin.json`), MCP servers, custom slash commands, OTLP export.

**OTLP export** (richest data including tokens):
```bash
export CLAUDE_CODE_ENABLE_TELEMETRY=1
export OTEL_EXPORTER_OTLP_ENDPOINT=http://localhost:4317
```

---

#### Gemini CLI — Hooks System ⭐⭐⭐

**Source**: `~/.gemini/settings.json`

**Available hook events:**
- `SessionStart` — fires when session begins
- `SessionEnd` — fires when session ends
- `BeforeTool` / `AfterTool` — tool execution lifecycle
- `BeforeAgent` / `AfterAgent` — agent lifecycle
- `BeforeModel` / `AfterModel` — LLM request lifecycle

**Configuration:**
```json
{
  "hooks": {
    "SessionStart": [{
      "matcher": "*",
      "hooks": [{
        "type": "command",
        "command": "~/.agent-pulse/hooks/report.sh"
      }]
    }],
    "SessionEnd": [{
      "matcher": "*",
      "hooks": [{
        "type": "command",
        "command": "~/.agent-pulse/hooks/report.sh"
      }]
    }]
  }
}
```

Gemini pipes JSON to stdin with session context — richer than just env vars.

**Also supports:** Extension system, MCP servers, OTel export.

---

#### Codex CLI — Notify Hook ⭐⭐

**Source**: `~/.codex/config.toml`

**Single hook mechanism:** `notify` — fires on every turn completion.

**Configuration (one line!):**
```toml
notify = ["~/.agent-pulse/hooks/report.sh"]
```

**Payload (JSON on stdin):**
```json
{
  "type": "agent-turn-complete",
  "thread-id": "uuid",
  "turn-id": "uuid",
  "input-messages": ["user prompt text"],
  "last-assistant-message": "response text",
  "cwd": "/path/to/project",
  "client": "codex-tui"
}
```

**Limitations:**
- No `SessionStart` / `SessionEnd` events — only turn completion
- Session start inferred from first turn notification
- Session end inferred from lack of notifications + process check

**Also supports:** OTel module (`codex-rs/otel/`), MCP tool servers (but no lifecycle events via MCP).

**Key source files:** `codex-rs/hooks/src/legacy_notify.rs`, `codex-rs/hooks/src/types.rs`

---

#### Cursor — Hooks System ⭐⭐⭐ (v1.7+)

**Source**: `.cursor/hooks.json` (project-level) or `~/.cursor/hooks.json` (global)

**Available hook events:**
- `sessionStart` / `sessionEnd` — session lifecycle
- `preToolUse` / `postToolUse` — tool execution lifecycle
- `beforeShellExecution` / `afterShellExecution` — shell commands (only in CLI agent)
- `afterFileEdit` — after file modifications
- `beforeSubmitPrompt` — before prompt submission
- `stop` — when agent response completes
- `beforeMCPExecution` / `afterMCPExecution` — MCP tool calls
- `preCompact` — before context compaction

Each hook receives JSON payloads with `conversation_id`, `generation_id`, prompt content, attachments, and workspace roots.

**Third-party OTel support via hooks:** The [cursor-otel-hook](https://github.com/LangGuard-AI/cursor-otel-hook) project uses Cursor's hooks system to export structured OTel traces covering session lifecycle, tool usage, shell commands, MCP calls, file operations, prompts, and subagent activities. agent-pulse could either:
1. Bundle a similar hook that exports to its local OTLP receiver, or
2. Read the hook artifacts directly

Sources: [Cursor docs: Hooks](https://cursor.com/docs/hooks), [cursor-otel-hook](https://github.com/LangGuard-AI/cursor-otel-hook), [GitButler: Deep Dive into Cursor Hooks](https://blog.gitbutler.com/cursor-hooks-deep-dive)

---

#### Copilot CLI — Full Hooks + OTel Support ⭐⭐⭐

**Hooks (8 event types):** `sessionStart`, `sessionEnd`, `userPromptSubmitted`, `preToolUse`, `postToolUse`, `agentStop`, `subagentStop`, `errorOccurred`

**Config locations (hierarchical):**
- Global: `~/.copilot/hooks/*.json` — applies to all sessions
- Per-repo: `.github/hooks/*.json` — overrides global

**Hook input JSON includes:** timestamp, cwd, source (new/resume), initialPrompt, toolName, toolArgs, toolResult, error details, session end reason (complete/error/abort/timeout/user_exit)

**OTel export (verified in SDK types + runtime JS):**
- `OTEL_EXPORTER_OTLP_ENDPOINT` — OTLP HTTP endpoint for traces/metrics
- `COPILOT_OTEL_FILE_EXPORTER_PATH` — JSON-lines trace output to file
- `COPILOT_OTEL_EXPORTER_TYPE` — "otlp-http" or "file"
- `OTEL_INSTRUMENTATION_GENAI_CAPTURE_MESSAGE_CONTENT` — capture prompts/responses

**Also supports:** Full plugin system (plugin.json manifest, marketplaces), Node.js extensions (`~/.copilot/extensions/`), MCP servers.

**Strategy:** Use hooks for lifecycle + tool events, OTel for token metrics. File reading (`events.jsonl`, lock files) becomes fallback/enrichment only.

---

#### Hook Summary Table

| Agent | Mechanism | Effort | Session Start | Session End | Per-Turn Data | Token Data |
|---|---|---|---|---|---|---|
| **Copilot CLI** | Hooks (8 events) + OTel | ~2h deploy script | ✅ `sessionStart` | ✅ `sessionEnd` (with reason) | ✅ `pre/postToolUse` | ✅ Via OTel |
| **Claude Code** | Hooks + OTLP | ~2h config | ✅ `SessionStart` | ✅ `SessionEnd` | ✅ `PostToolUse` | ✅ Via OTLP |
| **Gemini** | Hooks + OTel | ~2h config | ✅ `SessionStart` | ✅ `SessionEnd` | ✅ `AfterTool` | ⚠️ Via OTel |
| **Codex** | Notify hook + OTel | ~1h config | ⚠️ First turn | ⚠️ Inferred | ✅ Every turn | ⚠️ Via OTel |
| **Cursor** | Hooks (11+ events) | ~2h config | ✅ `sessionStart` | ✅ `sessionEnd` | ✅ `postToolUse` | ⚠️ Via cursor-otel-hook |

---

### PPID + Session ID Correlation

#### The Problem

Hook scripts receive event data (timestamp, cwd, toolName, etc.) but **no session ID**. With 3 Copilot sessions open in different terminals, how do we know which hook event belongs to which session?

#### The Solution: Hook Scripts Resolve Both at Source

Hook scripts run as child processes of the agent. The shell variable `$PPID` gives the parent PID — the agent process that spawned the hook. The hook script uses `$PPID` to look up the session ID from the agent's own lock/session files, then sends **both** to agent-pulse with every message.

#### Per-Agent Session ID Resolution (Inside the Hook Script)

| Agent | How Hook Script Gets Session ID |
|---|---|
| **Copilot CLI** | `find ~/.copilot/session-state -name "inuse.$PPID.lock"` → parent dir name = session UUID |
| **Claude Code** | `grep -l "\"pid\":$PPID" ~/.claude/session-health/*.lock` → filename sans `.lock` = session ID |
| **Codex** | `notify` JSON payload already includes `thread-id` directly ✅ |
| **Gemini** | Best-effort: find session JSON whose `lastUpdated` is most recent + match cwd |
| **Cursor** | Hook payload includes `conversation_id` directly ✅ |

#### Shared Hook Script Design

All agents call one shared script at `~/.agent-pulse/hooks/report.sh`. The script:
1. Reads the hook's JSON payload from stdin
2. Resolves `$PPID` → session ID via lock file lookup
3. POSTs both `pid` + `sessionId` + event data to agent-pulse
4. Fails silently if agent-pulse isn't running (`|| true`)

**Example `~/.agent-pulse/hooks/report.sh`:**
```bash
#!/bin/bash
INPUT=$(cat)
EVENT_TYPE="$1"   # e.g. "sessionStart", "postToolUse"
AGENT_TYPE="$2"   # e.g. "copilot-cli", "claude-code"

# Resolve session ID from agent-specific lock files
case "$AGENT_TYPE" in
  copilot-cli)
    LOCK=$(find ~/.copilot/session-state -name "inuse.$PPID.lock" -print -quit 2>/dev/null)
    SESSION_ID=$([ -n "$LOCK" ] && basename "$(dirname "$LOCK")" || echo "unknown")
    ;;
  claude-code)
    LOCK=$(grep -l "\"pid\":$PPID" ~/.claude/session-health/*.lock 2>/dev/null | head -1)
    SESSION_ID=$([ -n "$LOCK" ] && basename "$LOCK" .lock || echo "unknown")
    ;;
  codex)
    SESSION_ID=$(echo "$INPUT" | jq -r '."thread-id" // "unknown"')
    ;;
  gemini)
    SESSION_ID="unknown"  # best-effort via cwd+timing on agent-pulse side
    ;;
  cursor)
    SESSION_ID=$(echo "$INPUT" | jq -r '.conversation_id // "unknown"')
    ;;
esac

# POST to agent-pulse with both PID and session ID
curl -s --max-time 2 -X POST http://localhost:9876/events \
  -H "Content-Type: application/json" \
  -d "$(echo "$INPUT" | jq -c \
    --arg agent "$AGENT_TYPE" \
    --arg pid "$PPID" \
    --arg sid "$SESSION_ID" \
    --arg event "$EVENT_TYPE" \
    '{agent:$agent, pid:($pid|tonumber), sessionId:$sid, event:$event, data:.}')" \
  2>/dev/null || true
```

#### Per-Agent Hook Config That Calls the Shared Script

**Copilot CLI** (`~/.copilot/hooks/agent-pulse.json`):
```json
{
  "version": 1,
  "hooks": {
    "sessionStart": [{ "type": "command", "bash": "~/.agent-pulse/hooks/report.sh sessionStart copilot-cli", "timeoutSec": 5 }],
    "sessionEnd": [{ "type": "command", "bash": "~/.agent-pulse/hooks/report.sh sessionEnd copilot-cli", "timeoutSec": 5 }],
    "userPromptSubmitted": [{ "type": "command", "bash": "~/.agent-pulse/hooks/report.sh userPromptSubmitted copilot-cli", "timeoutSec": 5 }],
    "postToolUse": [{ "type": "command", "bash": "~/.agent-pulse/hooks/report.sh postToolUse copilot-cli", "timeoutSec": 5 }],
    "errorOccurred": [{ "type": "command", "bash": "~/.agent-pulse/hooks/report.sh errorOccurred copilot-cli", "timeoutSec": 5 }]
  }
}
```

**Claude Code** (merged into `~/.claude/settings.json`):
```json
{
  "hooks": {
    "SessionStart": [{ "matcher": "", "hooks": [{ "type": "command", "command": "~/.agent-pulse/hooks/report.sh SessionStart claude-code" }] }],
    "SessionEnd": [{ "matcher": "", "hooks": [{ "type": "command", "command": "~/.agent-pulse/hooks/report.sh SessionEnd claude-code" }] }],
    "PostToolUse": [{ "matcher": "", "hooks": [{ "type": "command", "command": "~/.agent-pulse/hooks/report.sh PostToolUse claude-code" }] }]
  }
}
```

**Codex** (`~/.codex/config.toml`):
```toml
notify = ["~/.agent-pulse/hooks/report.sh notify codex"]
```

**Gemini** (merged into `~/.gemini/settings.json`):
```json
{
  "hooks": {
    "SessionStart": [{ "matcher": "*", "hooks": [{ "type": "command", "command": "~/.agent-pulse/hooks/report.sh SessionStart gemini" }] }],
    "SessionEnd": [{ "matcher": "*", "hooks": [{ "type": "command", "command": "~/.agent-pulse/hooks/report.sh SessionEnd gemini" }] }]
  }
}
```

**Cursor** (`~/.cursor/hooks.json`):
```json
{
  "hooks": {
    "sessionStart": [{ "command": "~/.agent-pulse/hooks/report.sh sessionStart cursor" }],
    "sessionEnd": [{ "command": "~/.agent-pulse/hooks/report.sh sessionEnd cursor" }],
    "postToolUse": [{ "command": "~/.agent-pulse/hooks/report.sh postToolUse cursor" }],
    "stop": [{ "command": "~/.agent-pulse/hooks/report.sh stop cursor" }]
  }
}
```

#### What agent-pulse Receives (Unified Message Format)

```json
{
  "agent": "copilot-cli",
  "pid": 3251,
  "sessionId": "b8c53b29-32f8-4f6d-a06b-81fa66c68072",
  "event": "postToolUse",
  "data": {
    "timestamp": 1704614700000,
    "cwd": "/Users/user/project",
    "toolName": "bash",
    "toolArgs": "{\"command\":\"npm test\"}",
    "toolResult": { "resultType": "success", "textResultForLlm": "All tests passed" }
  }
}
```

#### How agent-pulse Uses Both Keys

- **`sessionId`** = stable key for the dashboard card. Survives agent process restarts (e.g., Copilot CLI resume). Used to match with discovery layer's session data.
- **`pid`** = live process check. `kill -0 $pid` verifies the agent is still running. Used to detect dead sessions when no `sessionEnd` hook fires.
- If `sessionId` is `"unknown"` (lock file not yet created at `sessionStart` time), agent-pulse uses `pid+cwd` as a temporary key, then updates when the session ID becomes available from a subsequent hook event or from the discovery layer.

---

### Hook Merging Behavior

**Copilot CLI** (confirmed from changelog): _"Extension hooks from multiple sources now **merge** instead of overwriting."_
This means `~/.copilot/hooks/agent-pulse.json` coexists with any repo-level `.github/hooks/security.json` — both sets of hooks run for every event. No conflicts.

**Claude Code / Gemini**: Hooks arrays in `settings.json` are additive — multiple entries in the same event array all execute.

**Codex**: `notify` is a single config key (array of commands). If the user already has a notifier, agent-pulse wraps it or appends to the array.

**Cursor**: Hooks in `.cursor/hooks.json` are arrays per event — additive. Global and project-level hooks both execute.

---

## Layer 3: Enrichment — "Tokens? Model? Full History?"

### Per-Agent Metadata

Once a session is detected (via lock file or process scan), agent-pulse can parse session metadata to enrich the dashboard display.

#### Copilot CLI Metadata

| File | Format | Key Fields | Purpose |
|---|---|---|---|
| `events.jsonl` | JSON Lines | Each line is a conversation event (user message, assistant response, tool call, file edit) | Full session history, token counting, activity timeline |
| `session.db` | SQLite | Session state tables | Structured query of session data |
| `workspace.yaml` | YAML | Working directory, git branch, git remote | Project context |
| `vscode.metadata.json` | JSON | Workspace folder, first message | VS Code integration context |
| `checkpoints/` | Directory of checkpoint files | Compacted history, summaries | Long session management |

#### Claude Code Metadata

| File | Format | Key Fields | Purpose |
|---|---|---|---|
| `~/.claude/projects/<hash>/memory/MEMORY.md` | Markdown | Per-project persistent memory | Project context |
| `<project>/.claude/settings.json` | JSON | Permissions, tool config | Project configuration |
| `~/.claude/debug/` | Directory | Debug logs | Session diagnostics |

Claude Code also emits **OpenTelemetry (OTLP)** structured events for all session activity, including tool executions, prompts, and API requests.

Source: [Claude Code detection engineering](https://www.monad.com/blog/detection-engineering-for-claude-code-part-1), [Claude .claude directory docs](https://code.claude.com/docs/en/claude-directory)

#### Codex CLI Metadata

| File | Format | Key Fields | Purpose |
|---|---|---|---|
| `~/.codex/sessions/YYYY/MM/DD/rollout-*.jsonl` | JSON Lines | Full session transcript (input, model response, file changes, command output) | Session history |
| `~/.codex/history.jsonl` | JSON Lines | Command and session records | Global history |
| `~/.codex/config.toml` | TOML | Model, sandboxing policy, approval settings | Configuration |

Source: [OpenAI Codex CLI docs](https://developers.openai.com/codex/cli), [Codex config docs](https://developers.openai.com/codex/config-basic)

#### Gemini CLI Metadata

| File | Format | Key Fields | Purpose |
|---|---|---|---|
| `~/.gemini/tmp/<project_hash>/chats/` | Chat log files | Conversation, tool calls, token stats | Per-project session history |
| `~/.gemini/settings.json` | JSON | Theme, model, MCP servers | User configuration |
| `~/.gemini/shell_history` | Text | CLI input history | Command recall |

Source: [Gemini CLI session management](https://geminicli.com/docs/cli/session-management/), [Gemini CLI config](https://github.com/google-gemini/gemini-cli/blob/main/docs/reference/configuration.md)

---

### Token Usage & Cost Data

| Metric | Copilot CLI | Claude Code | Cursor | Codex | Gemini |
|---|---|---|---|---|---|
| Input tokens | ✅ Session total | ✅ Per-request (OTLP) | ❌ | ⚠️ Variable (rollout) | ⚠️ Unclear |
| Output tokens | ✅ Session total | ✅ Per-request (OTLP) | ❌ | ⚠️ Variable | ⚠️ Unclear |
| Cache tokens | ❌ | ✅ `cache_read` + `cache_creation` | ❌ | ❌ | ❌ |
| Cost (USD) | ❌ | ✅ `cost_usd` per request | ❌ | ❌ | ❌ |
| Context window % | ✅ Token breakdown | ❌ | ✅ `contextUsagePercent` | ❌ | ❌ |
| Model name | ✅ | ✅ | ✅ | ✅ | ✅ |
| Per-turn granularity | ❌ Session total | ✅ Every API call | ❌ | ⚠️ If in rollout | ⚠️ If in messages |

**Cost calculation strategy:**
- Claude Code provides `cost_usd` directly via OTLP
- For others: token count × model pricing table (we maintain a lookup table of public API prices)

---

### Embedded OTLP Receiver

To unlock "Excellent" monitoring for Claude Code (and potentially other OTel-emitting agents), agent-pulse needs an **embedded OTLP receiver** — a local gRPC/HTTP server that accepts standard OpenTelemetry data.

#### Why This Matters

Without an OTLP receiver, agent-pulse is limited to file-watching and process-scanning. That works well for Copilot CLI (which writes rich `events.jsonl` files) but leaves Claude Code at "Basic" level — just process detection and project memory files. With an OTLP receiver, Claude Code jumps to "Excellent" — matching Copilot CLI's richness with tokens, cost, tool calls, code impact, and more.

This also future-proofs agent-pulse. Any tool that adopts OpenTelemetry (and the trend is clear — Claude Code, LangChain, LlamaIndex all support it) can feed data into agent-pulse without needing a custom provider.

#### Implementation Approach

**agent-pulse acts as a lightweight, embedded OTLP collector on localhost.** No separate Collector process, no Docker, no Prometheus stack — just a gRPC or HTTP endpoint built into the app.

Two viable approaches for the Kotlin/JVM stack:

##### Option 1: gRPC Server (Recommended)

Implement the OTLP gRPC services directly using `grpc-java` + `opentelemetry-proto`:

```kotlin
// Conceptual — Kotlin gRPC server receiving Claude Code telemetry
val server = ServerBuilder.forPort(4317)
    .addService(object : MetricsServiceGrpc.MetricsServiceImplBase() {
        override fun export(request: ExportMetricsServiceRequest, 
                          responseObserver: StreamObserver<ExportMetricsServiceResponse>) {
            // Parse metrics: token.usage, cost.usage, session.count, etc.
            request.resourceMetricsList.forEach { processMetrics(it) }
            responseObserver.onNext(ExportMetricsServiceResponse.getDefaultInstance())
            responseObserver.onCompleted()
        }
    })
    .addService(object : LogsServiceGrpc.LogsServiceImplBase() {
        override fun export(request: ExportLogsServiceRequest,
                          responseObserver: StreamObserver<ExportLogsServiceResponse>) {
            // Parse events: user_prompt, tool_result, api_request, etc.
            request.resourceLogsList.forEach { processLogs(it) }
            responseObserver.onNext(ExportLogsServiceResponse.getDefaultInstance())
            responseObserver.onCompleted()
        }
    })
    .build()
```

Dependencies:
- `io.grpc:grpc-netty-shaded` — gRPC server runtime
- `io.opentelemetry.proto:opentelemetry-proto` — OTLP protobuf definitions
- These are well-maintained, standard Java libraries

Pros: Standard OTLP port (4317), Claude Code's default protocol, high performance
Cons: Adds ~5MB to bundle, protobuf compilation step

##### Option 2: HTTP/JSON Endpoint

Simpler alternative — implement OTLP HTTP/JSON receiver using Ktor or a lightweight HTTP server:

```kotlin
// OTLP HTTP/JSON on port 4318
embeddedServer(Netty, port = 4318) {
    routing {
        post("/v1/metrics") {
            val body = call.receiveText()
            val metrics = Json.decodeFromString<OtlpMetricsPayload>(body)
            processMetrics(metrics)
            call.respond(HttpStatusCode.OK)
        }
        post("/v1/logs") {
            val body = call.receiveText()
            val logs = Json.decodeFromString<OtlpLogsPayload>(body)
            processLogs(logs)
            call.respond(HttpStatusCode.OK)
        }
    }
}.start()
```

Pros: No protobuf dependency, simpler code, Ktor is already a natural Kotlin fit
Cons: Requires `OTEL_EXPORTER_OTLP_PROTOCOL=http/json` config (not the default `grpc`), slightly higher overhead

#### Claude Code OTel Details

**Metrics available via OTel** (from official docs):

| Metric Name | Description | Unit | Key Attributes |
|---|---|---|---|
| `claude_code.session.count` | CLI sessions started | count | session.id, user.account_uuid |
| `claude_code.token.usage` | Tokens used | tokens | type (input/output/cacheRead/cacheCreation), model |
| `claude_code.cost.usage` | Session cost | USD | model |
| `claude_code.lines_of_code.count` | Lines modified | count | type (added/removed) |
| `claude_code.commit.count` | Git commits created | count | — |
| `claude_code.pull_request.count` | PRs created | count | — |
| `claude_code.code_edit_tool.decision` | Tool permission decisions | count | tool_name, decision (accept/reject), source |
| `claude_code.active_time.total` | Active usage time | seconds | type (user/cli) |

**Events available via OTel** (from official docs):

| Event Name | Description | Key Attributes |
|---|---|---|
| `claude_code.user_prompt` | User submits a prompt | prompt_length, prompt (if OTEL_LOG_USER_PROMPTS=1) |
| `claude_code.tool_result` | Tool completes execution | tool_name, success, duration_ms, decision_type, decision_source |
| `claude_code.api_request` | API request to Claude | model, cost_usd, duration_ms, input_tokens, output_tokens, cache_read_tokens |
| `claude_code.api_error` | API request fails | model, error, status_code, attempt |
| `claude_code.tool_decision` | Tool permission decision | tool_name, decision (accept/reject), source |

**Standard attributes on ALL metrics/events:**
- `session.id` — unique session identifier
- `organization.id` — org UUID (when authenticated)
- `user.account_uuid` — account UUID
- `user.email` — user email (when OAuth authenticated)
- `terminal.type` — terminal type (iTerm, vscode, cursor, tmux)
- `prompt.id` — UUID correlating events to the user prompt that triggered them

**Privacy controls:**
- Telemetry is **opt-in** (disabled by default)
- Prompt content is **redacted by default** (only `prompt_length` logged)
- Tool inputs/parameters are **redacted by default**
- Both can be enabled with `OTEL_LOG_USER_PROMPTS=1` and `OTEL_LOG_TOOL_DETAILS=1`

Sources: [Official Claude Code Monitoring Docs](https://code.claude.com/docs/en/monitoring-usage), [BindPlane: Per-Session Cost and Token Tracking](https://bindplane.com/blog/claude-code-opentelemetry-per-session-cost-and-token-tracking)

#### User Experience Flow

1. User installs agent-pulse → OTLP receiver starts automatically on localhost
2. agent-pulse shows a one-time setup prompt: "Enable rich Claude Code monitoring?"
3. If yes, agent-pulse writes the managed settings file:
   ```
   /Library/Application Support/ClaudeCode/managed-settings.json
   ```
   ```json
   {
     "env": {
       "CLAUDE_CODE_ENABLE_TELEMETRY": "1",
       "OTEL_METRICS_EXPORTER": "otlp",
       "OTEL_LOGS_EXPORTER": "otlp",
       "OTEL_EXPORTER_OTLP_PROTOCOL": "grpc",
       "OTEL_EXPORTER_OTLP_ENDPOINT": "http://localhost:4317"
     }
   }
   ```
4. Next time Claude Code starts, it automatically exports telemetry to agent-pulse
5. Claude Code sessions in the dashboard now show full metrics, cost, tool calls, etc.

**Note:** The managed settings path requires write access to `/Library/Application Support/` (may need admin privileges on macOS). Alternative: agent-pulse can guide the user to add env vars to their shell profile, or write to `~/.claude/settings.json` instead.

#### Port Conflict Handling

If another OTLP collector is already running on port 4317 (e.g., Grafana Alloy, otel-collector), agent-pulse should:
1. Detect the conflict on startup
2. Offer to use an alternative port (e.g., 4320)
3. Update the Claude Code configuration accordingly
4. Or offer "passthrough" mode: receive data and forward to the existing collector

---

## Agent-by-Agent Deep Dives

### GitHub Copilot CLI

**Environment**: Terminal (standalone or VS Code integrated terminal)

**Detection method**: File watching (primary) + process scanning (secondary) + hooks (real-time)

**Step-by-step detection:**

1. Watch `~/.copilot/session-state/` with FILE_TREE recursive watching
2. When a new directory appears (UUID format), a new session was created
3. When `inuse.<PID>.lock` appears inside, a process has claimed the session
4. Read the PID from the filename and cross-reference with OSHI process data
5. Parse `vscode.metadata.json` (if exists) for workspace and first message
6. When `inuse.<PID>.lock` disappears, the session was released by that process
7. When the UUID directory itself is empty of lock files, the session is idle/ended

**Process signature:**
- Name: `copilot`
- Path: `/opt/homebrew/Caskroom/copilot-cli/*/copilot` (macOS Homebrew)
- Distinctive: Two processes per session (wrapper + actual runtime)

**Hooks:** 8 events via `~/.copilot/hooks/agent-pulse.json` (sessionStart, sessionEnd, userPromptSubmitted, preToolUse, postToolUse, agentStop, subagentStop, errorOccurred)

**OTel:** `OTEL_EXPORTER_OTLP_ENDPOINT`, `COPILOT_OTEL_FILE_EXPORTER_PATH`, `COPILOT_OTEL_EXPORTER_TYPE`

**Confidence level**: Very High — lock files are an explicit, documented session mechanism, plus hooks for real-time.

**Richest data source of all agents.** Copilot CLI writes a structured `events.jsonl` log with every interaction, plus YAML and JSON metadata files. This was verified from a live system with 18 sessions.

#### Extractable Information

| Data Point | Source File | How to Extract | Example Value |
|---|---|---|---|
| Session ID | `workspace.yaml` | `id` field | `a1b2c3d4-e5f6-7890-abcd-ef1234567890` |
| Working directory | `workspace.yaml` | `cwd` field | `/home/user/Projects/agent-pulse` |
| Git root | `workspace.yaml` | `git_root` field | `/home/user/Projects/agent-pulse` |
| Git branch | `workspace.yaml` | `branch` field | `main` |
| Session summary | `workspace.yaml` | `summary` field (auto-generated) | `"Initialize Gh Copilot CLI IntelliJ Repo"` |
| Session created at | `workspace.yaml` | `created_at` ISO timestamp | `2026-04-01T20:59:41.335Z` |
| Session updated at | `workspace.yaml` | `updated_at` ISO timestamp | `2026-04-02T11:31:53.332Z` |
| Active PIDs | `inuse.<PID>.lock` | Filename pattern | `3251`, `84295` |
| VS Code workspace | `vscode.metadata.json` | `workspaceFolder.folderPath` | `/home/user/Projects` |
| First user message | `vscode.metadata.json` | `firstUserMessage` | `"create a directory and git init that..."` |
| Copilot version | `events.jsonl` | `session.start` → `copilotVersion` | `1.0.14` |
| Current model | `events.jsonl` | `session.model_change` → `newModel` | `claude-opus-4.6` |
| Reasoning effort | `events.jsonl` | `session.model_change` → `reasoningEffort` | `high` |
| Agent mode | `events.jsonl` | `user.message` → `agentMode` | `plan`, `interactive` |
| Total premium requests | `events.jsonl` | `session.shutdown` → `totalPremiumRequests` | `21` |
| Total API duration | `events.jsonl` | `session.shutdown` → `totalApiDurationMs` | `1,239,460 ms` |
| Lines added | `events.jsonl` | `session.shutdown` → `codeChanges.linesAdded` | `488` |
| Lines removed | `events.jsonl` | `session.shutdown` → `codeChanges.linesRemoved` | `289` |
| Files modified | `events.jsonl` | `session.shutdown` → `codeChanges.filesModified` | List of absolute paths |
| Token usage per model | `events.jsonl` | `session.shutdown` → `modelMetrics` | `inputTokens: 5,726,932, outputTokens: 71,216` |
| Current token window | `events.jsonl` | `session.shutdown` → `currentTokens` | `80,042` |
| System/conversation/tool tokens | `events.jsonl` | `session.shutdown` breakdown | `system: 7,869, conversation: 52,257, tools: 19,912` |
| Resume count | `events.jsonl` | Count `session.resume` events | `15` |
| Compaction count | `events.jsonl` | Count `session.compaction_complete` | `6` |
| Subagent usage | `events.jsonl` | `subagent.started/completed` events | Model, tool calls, tokens, duration per subagent |
| Tool call count | `events.jsonl` | Count `tool.execution_start` events | `652` in current session |
| User message count | `events.jsonl` | Count `user.message` events | `30` in current session |
| CPU usage | OSHI | `OSProcess.processCpuLoadCumulative` | Real-time percentage |
| Memory usage | OSHI | `OSProcess.residentSetSize` | Bytes (e.g., 740 MB) |
| Process uptime | OSHI | `OSProcess.startTime` vs now | Duration since process started |

**Event types found in events.jsonl** (from a real session with 2,517 events):

| Event Type | Count | Contains |
|---|---|---|
| `tool.execution_start` | 652 | Tool name, arguments |
| `tool.execution_complete` | 649 | Success/failure, result summary, token telemetry |
| `assistant.message` | 397 | Assistant response content |
| `assistant.turn_start` | 340 | Turn ID, interaction ID |
| `assistant.turn_end` | 338 | Turn ID |
| `session.plan_changed` | 43 | Plan content updates |
| `user.message` | 30 | User message content, attachments, agent mode |
| `session.resume` | 15 | Resume time, event count, selected model |
| `session.shutdown` | 12 | Full session metrics (tokens, cost, code changes) |
| `session.compaction_start` | 9 | Token counts before compaction |
| `subagent.started` | 7 | Agent type, description |
| `subagent.completed` | 7 | Model, total tool calls, total tokens, duration |
| `system.notification` | 6 | System events |
| `session.compaction_complete` | 6 | Pre-compaction tokens, checkpoint path |
| `session.mode_changed` | 5 | Previous/new mode (plan, interactive) |
| `session.start` | 1 | Session ID, version, start time, CWD |
| `session.model_change` | 1 | Previous/new model, reasoning effort |

**What agent-pulse can display for Copilot CLI:**
- Real-time: active/idle status, current model, agent mode, CPU/memory, working directory, git branch
- Session: summary, first message, resume count, compaction count, total turns
- Cumulative: total tokens (in/out per model), premium requests, API duration, lines added/removed, files modified
- History: full conversation timeline from events.jsonl

Source: [GitHub Docs: Copilot CLI session data](https://docs.github.com/en/copilot/concepts/agents/copilot-cli/chronicle), [DeepWiki: Session state](https://deepwiki.com/github/copilot-cli/6.2-session-state-and-lifecycle-management)

---

### GitHub Copilot in VS Code

Same data as Copilot CLI (identical `events.jsonl`, `workspace.yaml`, lock files), **plus** VS Code-specific metadata.

| Data Point | Source File | How to Extract | Unique to VS Code |
|---|---|---|---|
| All Copilot CLI fields | (same as above) | (same as above) | No |
| VS Code workspace folder | `vscode.metadata.json` | `workspaceFolder.folderPath` | Yes |
| VS Code request IDs | `vscode.requests.metadata.json` | Array of `{vscodeRequestId, copilotRequestId}` | Yes |
| Tool edit mappings | `vscode.requests.metadata.json` | `toolIdEditMap` per request | Yes |
| IDE identification | Process tree (OSHI) | Parent is `Code Helper (Plugin)` | Yes |

**What agent-pulse can display additionally for VS Code sessions:**
- IDE badge: "VS Code" indicator (distinguishing from pure CLI)
- Workspace folder (may differ from CWD if session was started in a different project)
- Per-request VS Code integration tracking

Source: [GitHub blog: Track Copilot sessions from VS Code](https://github.blog/changelog/2025-07-14-start-and-track-github-copilot-coding-agent-sessions-from-visual-studio-code/), [VS Code Copilot agents](https://code.visualstudio.com/docs/copilot/agents/overview)

---

### GitHub Copilot in IntelliJ

**Environment**: JetBrains IDE plugin

**Detection method**: Process scanning (primary) — no file state available

**Least data available.** IntelliJ's Copilot runs as `copilot-language-server` with no external session state files.

**Step-by-step detection:**

1. IntelliJ bundles its own `copilot-language-server` binary (not the Homebrew CLI)
2. Scan for processes with path containing `github-copilot-intellij/copilot-agent/native/`
3. The `--stdio` flag indicates standard LSP mode; `--acp` indicates ACP protocol mode
4. IntelliJ may also spawn a separate `copilot --acp --stdio` process via npm/npx for ACP integration
5. These processes are children of the IntelliJ JVM process; walking the process tree (via OSHI's `getParentProcessID()`) confirms the IDE relationship

**Process signature:**
- Name: `copilot-language-server`
- Path: `.../<IDE>/plugins/github-copilot-intellij/copilot-agent/native/<platform>/copilot-language-server`
- Flags: `--stdio` (LSP), `--acp` (ACP mode)
- Also: `npm exec @github/copilot-language-server@<version> --acp` for ACP

**Important**: IntelliJ's copilot-language-server does NOT write to `~/.copilot/session-state/`. Its session state is managed within the IDE. This means file watching alone cannot detect IntelliJ Copilot sessions; process scanning is required.

| Data Point | Source | How to Extract | Available |
|---|---|---|---|
| Process name | OSHI | `copilot-language-server` | Yes |
| IntelliJ plugin path | OSHI | Path contains `github-copilot-intellij/copilot-agent/native/` | Yes |
| Platform/architecture | OSHI | Path contains `darwin-arm64`, `linux-amd64`, etc. | Yes |
| Protocol mode | OSHI | `--stdio` (LSP) or `--acp` flag in command line | Yes |
| Language server version | OSHI | Version in npx path (e.g., `@1.460.0`) for ACP | Partial |
| Parent IDE process | OSHI | `getParentProcessID()` → IntelliJ JVM | Yes |
| CPU usage | OSHI | `processCpuLoadCumulative` | Yes |
| Memory usage | OSHI | `residentSetSize` | Yes |
| Process uptime | OSHI | `startTime` | Yes |
| Working directory | OSHI | Process CWD (if available) | Platform-dependent |
| Session content | None | Not accessible externally | No |
| Token usage | None | Not accessible externally | No |
| Conversation history | None | Not accessible externally | No |

**What agent-pulse can display for IntelliJ Copilot:**
- Real-time: active status, CPU/memory, protocol mode (LSP vs ACP), uptime
- Context: parent IDE identification, platform/architecture
- Limitation: no session content, tokens, or conversation — just "Copilot is running in IntelliJ"

**Confidence level**: High — distinctive binary path, but limited session metadata outside the IDE.

Source: [JetBrains Marketplace: GitHub Copilot plugin](https://plugins.jetbrains.com/plugin/17718-github-copilot--your-ai-pair-programmer), [GitHub blog: Copilot in JetBrains](https://github.blog/changelog/2025-05-19-agent-mode-and-mcp-support-for-copilot-in-jetbrains-eclipse-and-xcode-now-in-public-preview/)

---

### Claude Code CLI

**Environment**: Terminal (standalone or VS Code integrated terminal)

**Detection method**: Process scanning (primary) + file watching (secondary) + hooks (real-time) + OTel (when configured)

Claude Code has **two distinct monitoring tiers**: file-based (basic) and OpenTelemetry (excellent).

**Step-by-step detection:**

1. Scan for processes named `claude` using OSHI
2. The claude binary typically runs as a single process
3. Watch `~/.claude/` for changes to `projects/` and `debug/` directories
4. Parse `~/.claude/projects/<project_hash>/memory/MEMORY.md` for project identification
5. Claude Code does not use lock files like Copilot CLI; detection relies more heavily on process scanning
6. For additional telemetry, if OpenTelemetry export is configured, consume structured events

**Process signature:**
- Name: `claude`, `claude-code`
- May spawn `node` subprocesses (Electron-based)

**Hooks:** 9 events via `~/.claude/settings.json` (SessionStart, SessionEnd, PreToolUse, PostToolUse, Stop, SubagentStop, UserPromptSubmit, PreCompact, Notification)

#### Tier 1: File-Based Detection (Always Available)

| Data Point | Source | How to Extract | Available |
|---|---|---|---|
| Process name | OSHI | `claude` | Yes |
| CPU/memory usage | OSHI | Standard OSHI metrics | Yes |
| Process uptime | OSHI | `startTime` | Yes |
| Working directory | OSHI | Process CWD | Yes |
| Project memory | `~/.claude/projects/<hash>/memory/MEMORY.md` | Read markdown file | Yes |
| Debug logs | `~/.claude/debug/<uuid>.txt` | Timestamped debug entries | Yes |
| Plugin config | `~/.claude/plugins/blocklist.json` | JSON | Yes |
| Config backups | `~/.claude/backups/` | Timestamped backup files | Yes |
| Global instructions | `~/.claude/CLAUDE.md` | Read markdown | Yes (if exists) |
| Project instructions | `<project>/.claude/settings.json` | JSON with permissions, tool config | Yes (if exists) |

#### Tier 2: OpenTelemetry Integration (When Configured)

Claude Code has **first-class, official OpenTelemetry support** documented at [code.claude.com/docs/en/monitoring-usage](https://code.claude.com/docs/en/monitoring-usage). When the user enables it (`CLAUDE_CODE_ENABLE_TELEMETRY=1`), Claude Code exports rich structured data via standard OTLP protocol.

**How agent-pulse enables this:** agent-pulse runs a local OTLP receiver on `localhost:4317` (gRPC) or `localhost:4318` (HTTP). The user sets:
```bash
export CLAUDE_CODE_ENABLE_TELEMETRY=1
export OTEL_METRICS_EXPORTER=otlp
export OTEL_LOGS_EXPORTER=otlp
export OTEL_EXPORTER_OTLP_PROTOCOL=grpc
export OTEL_EXPORTER_OTLP_ENDPOINT=http://localhost:4317
```
Or agent-pulse can auto-configure this via the managed settings file at `/Library/Application Support/ClaudeCode/managed-settings.json` (macOS).

**What agent-pulse can display for Claude Code:**

*Without OTel (Tier 1):*
- Real-time: active status, CPU/memory, working directory
- Context: project memory content (MEMORY.md), configured instructions
- Limitation: no token metrics, conversation content, or session IDs

*With OTel (Tier 2 — recommended):*
- Real-time: active status, current model, cost accumulating, tool calls in progress
- Session: session ID, token usage (input/output/cache per model), cost in USD, active time
- Code impact: lines added/removed, commits created, PRs opened
- Tool usage: every tool call with success/failure, duration, permission decisions
- API: every API request with model, tokens, cost, duration, cache stats
- Prompts: user prompt lengths (or full content if opted in)
- **This makes Claude Code monitoring comparable to Copilot CLI in richness**

**Confidence level**: Medium-High — process detection is reliable, but no lock-file equivalent means crash detection relies on process liveness checks.

Sources: [Official Claude Code Monitoring Docs](https://code.claude.com/docs/en/monitoring-usage), [Monad: Detection engineering for Claude Code](https://www.monad.com/blog/detection-engineering-for-claude-code-part-1), [BindPlane: Per-Session Cost and Token Tracking](https://bindplane.com/blog/claude-code-opentelemetry-per-session-cost-and-token-tracking)

---

### Claude Code in VS Code

Same data as Claude Code CLI (both tiers). The VS Code extension is a bridge to the CLI process.

| Data Point | Source | Unique to VS Code |
|---|---|---|
| All Claude Code CLI fields (Tier 1 + Tier 2) | (same as above) | No |
| IDE identification | Process tree (OSHI) | Yes — parent is VS Code extension host |
| Terminal type attribute | OTel `terminal.type` | Yes — reports `"vscode"` automatically |

**What agent-pulse can display additionally:**
- IDE badge: "VS Code" indicator on the Claude Code session (detected via process tree or OTel `terminal.type` attribute)

**Confidence level**: Medium-High — same as Claude Code CLI; VS Code parentage can be detected via process tree.

Source: [Claude Code VS Code docs](https://code.claude.com/docs/en/vs-code), [Claude Code VS Code extension guide](https://claudefa.st/blog/tools/extensions/claude-code-vscode)

---

### OpenAI Codex CLI

**Environment**: Terminal

**Detection method**: Process scanning (primary) + file watching (secondary) + notify hook (real-time)

**Step-by-step detection:**

1. Scan for processes named `codex` or `codex-tui` using OSHI
2. Codex is written in Rust, so it runs as a single native binary
3. Watch `~/.codex/sessions/` for new rollout files
4. New `rollout-*.jsonl` files indicate active sessions
5. Parse rollout files for session context (commands executed, files changed)
6. Codex logs PID and session tokens per invocation in its logs

**Process signature:**
- Name: `codex`, `codex-tui`, `codex-cli`
- Single Rust binary process

**Hooks:** `notify` hook fires on every turn completion with `thread-id`, messages, cwd

| Data Point | Source | How to Extract | Available |
|---|---|---|---|
| Process name | OSHI | `codex` or `codex-tui` | Yes |
| CPU/memory usage | OSHI | Standard OSHI metrics | Yes |
| Process uptime | OSHI | `startTime` | Yes |
| Working directory | OSHI | Process CWD | Yes |
| Session transcript | `~/.codex/sessions/YYYY/MM/DD/rollout-*.jsonl` | JSONL per session | Yes |
| Commands executed | Rollout JSONL | Parse command invocation entries | Yes |
| Files changed | Rollout JSONL | Parse file edit entries | Yes |
| Model responses | Rollout JSONL | Parse model response entries | Yes |
| Global history | `~/.codex/history.jsonl` | JSONL of all commands/sessions | Yes |
| Configuration | `~/.codex/config.toml` | Model, sandbox policy, approval settings | Yes |
| Global instructions | `~/.codex/AGENTS.md` | Read markdown | Yes (if exists) |
| TUI logs | `~/.codex/logs/codex-tui.log` | Timestamped log entries | Yes |
| Token usage | Rollout JSONL | Parse token counts from response metadata | Partial |

**What agent-pulse can display for Codex CLI:**
- Real-time: active status, CPU/memory, working directory
- Session: rollout transcript (commands, file changes, model responses), configured model
- Configuration: sandbox policy, auto-approve settings, custom instructions
- History: global command history across sessions

**Confidence level**: Medium-High — process name is distinctive; session files provide good history but no explicit lock mechanism.

Source: [OpenAI Codex CLI docs](https://developers.openai.com/codex/cli), [Codex config](https://developers.openai.com/codex/config-basic)

---

### Google Gemini CLI

**Environment**: Terminal

**Detection method**: Process scanning (primary) + file watching (secondary) + hooks (real-time)

**Step-by-step detection:**

1. Scan for processes named `gemini` using OSHI
2. Watch `~/.gemini/tmp/` for changes in project-hashed chat directories
3. New or modified chat files in `<project_hash>/chats/` indicate active sessions
4. Parse `~/.gemini/settings.json` for model and configuration context
5. Gemini supports `--resume` for session continuity

**Process signature:**
- Name: `gemini`
- Node.js-based CLI

**Hooks:** 6 events via `~/.gemini/settings.json` (SessionStart, SessionEnd, BeforeTool, AfterTool, BeforeModel, AfterModel)

| Data Point | Source | How to Extract | Available |
|---|---|---|---|
| Process name | OSHI | `gemini` | Yes |
| CPU/memory usage | OSHI | Standard OSHI metrics | Yes |
| Process uptime | OSHI | `startTime` | Yes |
| Working directory | OSHI | Process CWD | Yes |
| Chat history (per project) | `~/.gemini/tmp/<project_hash>/chats/` | Chat log files | Yes |
| Configuration | `~/.gemini/settings.json` | Model, theme, MCP servers | Yes |
| Shell history | `~/.gemini/shell_history` | Text file of CLI inputs | Yes |
| Global instructions | `~/.gemini/GEMINI.md` | Read markdown | Yes (if exists) |
| Extensions | `~/.gemini/extensions/` | Installed CLI extensions | Yes |
| Token usage | Chat log files | Parse token stats from chat entries | Partial |

**What agent-pulse can display for Gemini CLI:**
- Real-time: active status, CPU/memory, working directory
- Session: chat history for the current project
- Configuration: selected model, MCP server connections, custom instructions
- History: shell command history, per-project chat archives

**Confidence level**: Medium — process name is distinctive but file artifacts are less structured than Copilot CLI.

Source: [Gemini CLI session management](https://geminicli.com/docs/cli/session-management/), [Gemini CLI GitHub](https://github.com/google-gemini/gemini-cli)

---

### Cursor IDE & CLI

**Environment**: Standalone IDE (Electron-based, VS Code fork) + CLI agent (`cursor agent`)

**Detection method**: Process scanning (primary) + file watching (secondary) + hooks (v1.7+) + OTel (via cursor-otel-hook)

**Much richer than initially assessed.** Cursor stores centralized session data in SQLite databases, writes agent transcripts as JSONL, and has a first-class hooks system.

**Step-by-step detection:**

1. Scan for processes named `Cursor` or `Cursor Helper` using OSHI
2. Cursor stores workspace session data in a centralized `state.vscdb` SQLite database at `~/Library/Application Support/Cursor/User/workspaceStorage/<hash>/state.vscdb` (macOS)
3. The `state.vscdb` contains `composer.composerData` key in `ItemTable` with JSON blob tracking all agent sessions
4. AI-code tracking database at `~/.cursor/ai-tracking/ai-code-tracking.db` stores scored commits (AI vs human lines), conversation summaries, and file content tracking
5. Agent transcripts are written as JSONL at `~/.cursor/projects/<path-hash>/agent-transcripts/<composerId>/<composerId>.jsonl`
6. MCP server metadata is stored per-project at `~/.cursor/projects/<path-hash>/mcps/<server-name>/`
7. Application logs at `~/Library/Application Support/Cursor/logs/<date>/main.log` contain agent-loop wakelock events
8. For CLI detection: scan for `cursor` process with `agent` in command line args

**Process signature:**
- IDE: `Cursor`, `Cursor Helper`, `Cursor Helper (Renderer)`, `Cursor Helper (Plugin)`
- CLI: `cursor agent` (or `cursor` with `agent` subcommand)
- Electron app bundle at `/Applications/Cursor.app`

**Hooks (v1.7+):** 11+ events via `.cursor/hooks.json` or `~/.cursor/hooks.json`: sessionStart, sessionEnd, preToolUse, postToolUse, beforeShellExecution, afterShellExecution (CLI only), afterFileEdit, beforeSubmitPrompt, stop, beforeMCPExecution, afterMCPExecution, preCompact. Each hook receives JSON payloads with `conversation_id`, `generation_id`, prompt content, attachments, and workspace roots.

**Cursor CLI Agent:** Cursor now has a full CLI agent mode (`cursor agent`) with:
- Interactive agent sessions from the terminal
- Plan/Ask/Agent modes
- Session resume (`cursor agent --resume=<session-id>`)
- Session listing (`cursor agent ls`)
- Cloud handoff support
- AGENTS.md and CLAUDE.md rule file support

#### Tier 1: Centralized SQLite Databases (Always Available)

**`state.vscdb`** — Per-workspace SQLite at `~/Library/Application Support/Cursor/User/workspaceStorage/<hash>/state.vscdb`

| Data Point | Source Key | How to Extract | Example Value |
|---|---|---|---|
| Session/Composer ID | `composer.composerData` | JSON blob → `allComposers[].composerId` | `f1e2d3c4-b5a6-7890-abcd-1234567890ab` |
| Session name | `composer.composerData` | `allComposers[].name` | `"Welcome to Cursor"` |
| Mode (chat/edit) | `composer.composerData` | `allComposers[].unifiedMode` / `forceMode` | `"chat"` / `"edit"` |
| Lines added/removed | `composer.composerData` | `allComposers[].totalLinesAdded` / `totalLinesRemoved` | `1120` / `0` |
| Files changed count | `composer.composerData` | `allComposers[].filesChangedCount` | `3` |
| Session subtitle | `composer.composerData` | `allComposers[].subtitle` | `"Edited script.js, styles.css, index.html"` |
| Created/updated timestamps | `composer.composerData` | `allComposers[].createdAt` / `lastUpdatedAt` | Unix ms timestamps |
| Context usage % | `composer.composerData` | `allComposers[].contextUsagePercent` | `11.5` |
| Archive/draft status | `composer.composerData` | `allComposers[].isArchived` / `isDraft` | `false` / `false` |
| Worktree status | `composer.composerData` | `allComposers[].isWorktree` | `false` |
| Sub-composer count | `composer.composerData` | `allComposers[].numSubComposers` | `0` |
| Unread messages | `composer.composerData` | `allComposers[].hasUnreadMessages` | `false` |
| User prompts | `aiService.prompts` | JSON array of `{text, commandType}` | Full prompt history |
| Generations log | `aiService.generations` | JSON array of `{unixMs, generationUUID, type, textDescription}` | Timestamped generation history |

**`ai-code-tracking.db`** — Global AI tracking at `~/.cursor/ai-tracking/ai-code-tracking.db`

| Data Point | Table | How to Extract | Available |
|---|---|---|---|
| Conversation summaries | `conversation_summaries` | `conversationId, title, tldr, overview, summaryBullets, model, mode` | Yes |
| Scored commits (AI vs human) | `scored_commits` | `commitHash, linesAdded, composerLinesAdded, humanLinesAdded` | Yes |
| AI code hashes | `ai_code_hashes` | `hash, source, fileExtension, requestId, conversationId, model` | Yes |
| Tracked file content | `tracked_file_content` | `gitPath, content, conversationId, model` | Yes |
| AI deleted files | `ai_deleted_files` | `gitPath, composerId, conversationId, model, deletedAt` | Yes |
| Tracking start time | `tracking_state` | Key-value state | Yes |

#### Tier 2: Agent Transcripts (Full Conversation History)

Agent transcripts are stored as JSONL at `~/.cursor/projects/<path-hash>/agent-transcripts/<composerId>/<composerId>.jsonl`.

| Data Point | Source | How to Extract | Available |
|---|---|---|---|
| Full conversation | JSONL transcript | Parse `{role, message}` entries | Yes |
| User messages | JSONL transcript | Filter `role: "user"` entries | Yes |
| Assistant responses | JSONL transcript | Filter `role: "assistant"` entries | Yes |
| Tool calls | JSONL transcript | Embedded in assistant messages | Yes |
| Thinking/reasoning | JSONL transcript | Embedded in assistant content | Yes |

The path-hash for projects encodes the workspace path (e.g., `Users-jane-Documents-Projects` → `~/.cursor/projects/Users-jane-Documents-Projects/`). This is discoverable by watching the `~/.cursor/projects/` directory.

#### Tier 3: Application Logs

| Data Point | Source | How to Extract | Available |
|---|---|---|---|
| Agent-loop wakelocks | `~/Library/.../Cursor/logs/<date>/main.log` | Parse `[PowerMainService]` wakelock entries | Yes |
| Wakelock durations | main.log | `heldForMs=<N>` in wakelock stop entries | Yes |
| Agent-loop reason | main.log | `reason="agent-loop"` or `"agent-loop-resumed"` | Yes |
| MCP server configs | `~/.cursor/projects/<hash>/mcps/<server>/` | `SERVER_METADATA.json` | Yes |
| Cursor skills | `~/.cursor/skills-cursor/` | Manifest + SKILL.md files | Yes |
| IDE state | `~/.cursor/ide_state.json` | JSON with recently viewed files | Yes |
| Process metrics | OSHI | CPU/memory/uptime of Cursor processes | Yes |

#### Tier 4: Hooks + OTel (v1.7+)

Cursor's first-class hooks system sends JSON payloads for every agent lifecycle event. The [cursor-otel-hook](https://github.com/LangGuard-AI/cursor-otel-hook) project demonstrates exporting to OTel traces.

| Data Point | Hook Event | Available |
|---|---|---|
| Session start/end | `sessionStart` / `sessionEnd` | Yes |
| Tool execution (pre/post) | `preToolUse` / `postToolUse` | Yes |
| Shell commands | `beforeShellExecution` / `afterShellExecution` | Yes |
| File edits | `afterFileEdit` | Yes |
| Prompt submission | `beforeSubmitPrompt` | Yes |
| Agent response complete | `stop` | Yes |
| MCP calls | `beforeMCPExecution` / `afterMCPExecution` | Yes |
| Context compaction | `preCompact` | Yes |

**What agent-pulse can display for Cursor:**

*Without hooks (Tier 1-3 — always available):*
- Real-time: active status, CPU/memory, agent-loop wakelock status (from main.log)
- Session: composer ID, name, mode (chat/edit), lines added/removed, files changed, subtitle, context usage %, created/updated timestamps
- History: full conversation transcript (JSONL), all user prompts, generation history
- Code impact: AI vs human line attribution per commit (scored_commits), conversation summaries with model info
- Context: MCP servers, configured skills, recently viewed files

*With hooks (Tier 4 — optional):*
- Real-time tool calls, shell commands, file edits, and prompt submissions streamed to agent-pulse
- Session lifecycle events with conversation_id and generation_id
- **This makes Cursor monitoring "Excellent" level**

**Confidence level**: Very High — centralized SQLite databases with rich metadata, agent transcripts, and hooks system provide multiple monitoring vectors.

Sources:
- [Cursor docs: Agent overview](https://cursor.com/docs/agent/overview)
- [Cursor docs: CLI overview](https://cursor.com/docs/cli/overview)
- [Cursor docs: Using Agent in CLI](https://cursor.com/docs/cli/using)
- [Cursor docs: Hooks](https://cursor.com/docs/hooks)
- [cursor-otel-hook](https://github.com/LangGuard-AI/cursor-otel-hook)
- [GitButler: Deep Dive into Cursor Hooks](https://blog.gitbutler.com/cursor-hooks-deep-dive)

---

## Extractable Information Summary

### Data Richness by Agent

| Agent | Identity | Context | Activity | Tokens | Code Impact | Conversation | Overall Richness |
|---|---|---|---|---|---|---|---|
| **Copilot CLI** | ●●● | ●●● | ●●● | ●●● | ●●● | ●●● | **Excellent** |
| **Copilot VS Code** | ●●● | ●●● | ●●● | ●●● | ●●● | ●●● | **Excellent** |
| **Copilot IntelliJ** | ●●○ | ●○○ | ●○○ | ○○○ | ○○○ | ○○○ | **Limited** |
| **Claude Code CLI** (file only) | ●●○ | ●●○ | ●○○ | ○○○ | ○○○ | ○○○ | **Basic** |
| **Claude Code CLI** (with OTel) | ●●● | ●●● | ●●● | ●●● | ●●● | ●●○ | **Excellent** |
| **Claude Code VS Code** (file only) | ●●○ | ●●○ | ●○○ | ○○○ | ○○○ | ○○○ | **Basic** |
| **Claude Code VS Code** (with OTel) | ●●● | ●●● | ●●● | ●●● | ●●● | ●●○ | **Excellent** |
| **Codex CLI** | ●●○ | ●●○ | ●●○ | ●○○ | ●●○ | ●●○ | **Good** |
| **Gemini CLI** | ●●○ | ●●○ | ●●○ | ●○○ | ○○○ | ●●○ | **Good** |
| **Cursor IDE** (file-based) | ●●● | ●●● | ●●○ | ○○○ | ●●● | ●●● | **Very Good** |
| **Cursor IDE** (with hooks) | ●●● | ●●● | ●●● | ○○○ | ●●● | ●●● | **Excellent** |

Key: ●●● = rich data / ●●○ = moderate / ●○○ = minimal / ○○○ = not available

Key: ●●○ for Conversation in Claude Code + OTel reflects that prompt content is redacted by default (only lengths). Full content requires explicit `OTEL_LOG_USER_PROMPTS=1` opt-in.

**Key takeaway:** GitHub Copilot CLI, Claude Code (with OTel), and Cursor are the three richest monitoring targets. Copilot writes everything to local files automatically. Claude Code requires opt-in telemetry configuration but then provides equally rich data via OTLP. Cursor stores centralized SQLite databases with session metadata, AI-code tracking (AI vs human lines per commit), and full conversation transcripts as JSONL — plus a first-class hooks system for real-time event streaming. **agent-pulse should treat OTLP ingestion as a first-class feature**, since it transforms both Claude Code and Cursor (via hooks) from limited to excellent, and positions agent-pulse as a zero-config local receiver that replaces the need for Grafana/Prometheus stacks for individual developers.

### Information Categories

| Category | Description | Source |
|---|---|---|
| **Identity** | Which agent, which version, session ID | Process scanning + file metadata |
| **Context** | Working directory, project name, git branch | File metadata (workspace.yaml, etc.) |
| **Activity** | What the agent is doing right now, last user message | events.jsonl, process liveness, hooks |
| **Resource Usage** | CPU, memory, process uptime | OSHI process metrics |
| **Token Metrics** | Input/output tokens, model used, cost tier | events.jsonl shutdown/model events, OTel |
| **Code Impact** | Lines added/removed, files modified | events.jsonl shutdown events, Cursor SQLite |
| **Session History** | Conversation turns, tool calls, compactions | events.jsonl event stream, agent transcripts |
| **Lifecycle** | Start time, resume count, idle duration | Lock files + events.jsonl + hooks |

---

## MCP as Universal Connection

All 5 agents support MCP (Model Context Protocol). agent-pulse can run an MCP server on localhost.

**What MCP gives us:**
- ✅ Connection lifecycle — agent connects = alive, disconnects = dead
- ✅ Works for both IDE and CLI modes (shared config)
- ❌ Cannot query agent's internal state (server is passive)
- ❌ Cannot get token data (MCP tools are called BY the agent, not pushed)

**Best use:** Lightweight heartbeat / alive-dead signal. Not a replacement for hooks.

**MCP config locations:**
- Claude Code: `.mcp.json` or in `plugin.json`
- Cursor: `.cursor/mcp.json`
- Codex: MCP tool servers in config
- Gemini: MCP in settings or extension config

---

## Hook Deployment Strategy

### Shared Helper Script

All agents call one shared script — see [PPID + Session ID Correlation](#ppid--session-id-correlation) for the full script design. Summary:

```
~/.agent-pulse/
├── hooks/
│   ├── report.sh          ← macOS/Linux (resolves PPID+sessionID, POSTs to localhost:9876)
│   └── report.ps1         ← Windows (PowerShell equivalent)
├── config.json             ← Which agents are configured, agent-pulse port
└── backups/                ← Original agent configs before modification
```

### Auto-Configuration (first launch)

1. Detect installed agents: check `~/.claude/`, `~/.codex/`, `~/.gemini/`, `~/.cursor/`, Cursor app data
2. Show setup wizard in system tray UI
3. Back up original configs to `~/.agent-pulse/backups/`
4. Merge hook entries into each agent's config (preserve existing settings)
5. Start local HTTP listener on `localhost:9876`

### Config Paths (cross-platform)

| Agent | macOS/Linux | Windows |
|---|---|---|
| Copilot CLI | `~/.copilot/hooks/agent-pulse.json` | `%USERPROFILE%\.copilot\hooks\agent-pulse.json` |
| Claude Code | `~/.claude/settings.json` | `%USERPROFILE%\.claude\settings.json` |
| Codex | `~/.codex/config.toml` | `%USERPROFILE%\.codex\config.toml` |
| Gemini | `~/.gemini/settings.json` | `%USERPROFILE%\.gemini\settings.json` |
| Cursor | `~/.cursor/hooks.json` | `%APPDATA%\Cursor\hooks.json` |

### Safety Measures

- Always back up before modifying agent configs
- Merge, don't overwrite (preserve user's existing hooks)
- Hooks fail silently (`|| true`) — never break the agent
- Codex `notify` is singular — wrap existing command if present
- Uninstall restores from backups
- Marker comment identifies agent-pulse hooks for clean removal

---

## Product Positioning

### What agent-pulse IS

**The developer's AI cockpit** — a unified system tray dashboard showing all active AI coding agents on the workstation:

```
┌──────────────────────────────────────────┐
│  🟢 Claude Code  (14min, 12K tokens)     │
│  🟢 Cursor       (2 chats active)        │
│  🟡 Codex        (idle 30s)              │
│  ⚫ Gemini       (ended 5min ago)        │
│                                           │
│  Today: 145K tokens · ~$2.30             │
└──────────────────────────────────────────┘
```

### What agent-pulse is NOT

- Not a K8s fleet manager (Langfuse, Helicone, LangSmith solve this)
- Not an LLM observability platform (enterprise solved space)
- Not an AI agent framework

### Unique Value

No existing tool provides a unified view of all local AI coding agents. Each agent has its own status/history, but developers juggling 2-5 agents simultaneously have no single pane of glass.

---

## Sources

### Official Documentation
- [GitHub Docs: Copilot CLI session data](https://docs.github.com/en/copilot/concepts/agents/copilot-cli/chronicle)
- [GitHub Docs: Tracking Copilot sessions](https://docs.github.com/en/copilot/how-tos/use-copilot-agents/coding-agent/track-copilot-sessions)
- [Claude Code: .claude directory](https://code.claude.com/docs/en/claude-directory)
- [Claude Code: Monitoring & Usage (Official OTel docs)](https://code.claude.com/docs/en/monitoring-usage)
- [Claude Code: VS Code extension](https://code.claude.com/docs/en/vs-code)
- [OpenAI Codex CLI](https://developers.openai.com/codex/cli)
- [OpenAI Codex config](https://developers.openai.com/codex/config-basic)
- [Gemini CLI GitHub](https://github.com/google-gemini/gemini-cli)
- [Gemini CLI config](https://github.com/google-gemini/gemini-cli/blob/main/docs/reference/configuration.md)
- [Cursor docs: Agent overview](https://cursor.com/docs/agent/overview)
- [Cursor docs: CLI overview](https://cursor.com/docs/cli/overview)
- [Cursor docs: Using Agent in CLI](https://cursor.com/docs/cli/using)
- [Cursor docs: Hooks](https://cursor.com/docs/hooks)
- [OSHI GitHub](https://github.com/oshi/oshi)
- [OSHI OSProcess API](https://www.oshi.ooo/oshi-core-java11/apidocs/com.github.oshi/oshi/software/os/OSProcess.html)

### Technical Deep Dives
- [DeepWiki: Copilot CLI session state & lifecycle](https://deepwiki.com/github/copilot-cli/6.2-session-state-and-lifecycle-management)
- [DeepWiki: Copilot CLI session management & history](https://deepwiki.com/github/copilot-cli/3.3-session-management-and-history)
- [Monad: Detection engineering for Claude Code](https://www.monad.com/blog/detection-engineering-for-claude-code-part-1)
- [BindPlane: Claude Code Per-Session Cost and Token Tracking](https://bindplane.com/blog/claude-code-opentelemetry-per-session-cost-and-token-tracking)
- [SigNoz: Claude Code Monitoring with OpenTelemetry](https://signoz.io/blog/claude-code-monitoring-with-opentelemetry/)
- [Monitoring Claude Code with OpenTelemetry and Grafana](https://claude-blog.setec.rs/blog/claude-code-grafana-monitoring)
- [Gemini CLI session management](https://geminicli.com/docs/cli/session-management/)
- [NoBoxDev: Process-tree agent detection](https://noboxdev.com/blog/process-tree-agent-detection)
- [OpenTelemetry OTLP Receiver Guide](https://www.dash0.com/guides/opentelemetry-otlp-receiver)
- [Baeldung: Working with OpenTelemetry Collector](https://www.baeldung.com/java-opentelemetry-collector)

### Community & Analysis
- [GridWatch: Copilot session dashboard](https://www.faesel.com/blog/gridwatch-copilot-session-manager)
- [cursor-otel-hook: OpenTelemetry integration for Cursor](https://github.com/LangGuard-AI/cursor-otel-hook)
- [GitButler: Deep Dive into Cursor Hooks](https://blog.gitbutler.com/cursor-hooks-deep-dive)
- [Cursor Chat History Recovery Guide](https://www.cursor.fan/tutorial/HowTo/manage-cursor-chat-history/)
- [CursorChatAnalyzer](https://github.com/toddllm/CursorChatAnalyzer)

### JetBrains & IDE Integration
- [JetBrains Marketplace: GitHub Copilot plugin](https://plugins.jetbrains.com/plugin/17718-github-copilot--your-ai-pair-programmer)
- [GitHub blog: Copilot agent mode in JetBrains](https://github.blog/changelog/2025-05-19-agent-mode-and-mcp-support-for-copilot-in-jetbrains-eclipse-and-xcode-now-in-public-preview/)
- [VS Code: Copilot agents overview](https://code.visualstudio.com/docs/copilot/agents/overview)

### Agent Source Repositories
- Claude Code repository: `github.com/anthropics/claude-code` — hooks, plugins, OTLP
- OpenAI Codex repository: `github.com/openai/codex` — `codex-rs/hooks/`, `codex-rs/otel/`
- Google Gemini CLI repository: `github.com/google-gemini/gemini-cli` — hooks, extensions, telemetry
- VS Code repository: `github.com/microsoft/vscode` — chat APIs, language model API
- MCP specification: `modelcontextprotocol.io` — lifecycle, notifications

### Live System Evidence
- Process signatures captured via `ps aux` on macOS (Apple Silicon, 2026-04-02)
- File system artifacts inspected from `~/.copilot/session-state/`, `~/.claude/`, `~/.codex/`, `~/.cursor/`, `~/Library/Application Support/Cursor/`
- Cursor `state.vscdb` and `ai-code-tracking.db` SQLite schemas verified via live database inspection
- Cursor agent transcripts (JSONL) verified at `~/.cursor/projects/*/agent-transcripts/`
- Lock file format and contents verified from live running sessions
