# How to Monitor AI Coding Agents: Detection Strategies for agent-pulse

## Purpose

This document explains, step by step, how agent-pulse can detect and monitor running AI coding agents across different environments (CLI terminals, VS Code, IntelliJ, standalone IDEs). It covers the concrete file system artifacts, process signatures, and metadata each agent produces, backed by real-world evidence gathered from a live macOS development machine and official documentation.

> **See also:** [`agent-extensibility-research.md`](agent-extensibility-research.md) — deep research into push-based monitoring via agent hooks, MCP, OTel, token usage, native process event APIs, and the architecture shift from file scraping to agent-pushed lifecycle events. That document supersedes parts of this one regarding detection strategy.

---

## Table of Contents

1. [Detection Strategy Overview](#detection-strategy-overview)
2. [Layer 1: Process Scanning](#layer-1-process-scanning)
3. [Layer 2: File System Watching](#layer-2-file-system-watching)
4. [Layer 3: Metadata Parsing](#layer-3-metadata-parsing)
5. [Agent-by-Agent Analysis](#agent-by-agent-analysis)
   - [GitHub Copilot CLI](#github-copilot-cli)
   - [Claude Code CLI](#claude-code-cli)
   - [OpenAI Codex CLI](#openai-codex-cli)
   - [Google Gemini CLI](#google-gemini-cli)
   - [GitHub Copilot in VS Code](#github-copilot-in-vs-code)
   - [GitHub Copilot in IntelliJ](#github-copilot-in-intellij)
   - [Claude Code in VS Code](#claude-code-in-vs-code)
   - [Cursor IDE & CLI](#cursor-ide--cli)
6. [How OSHI Enables Process Detection on the JVM](#how-oshi-enables-process-detection-on-the-jvm)
7. [File System Watching Strategy](#file-system-watching-strategy)
8. [Combining Detection Layers](#combining-detection-layers)
9. [Extractable Information per Agent](#extractable-information-per-agent)
10. [Architectural Implication: Embedded OTLP Receiver](#architectural-implication-embedded-otlp-receiver)
11. [Sources](#sources)

---

## Detection Strategy Overview

agent-pulse uses three complementary detection layers to identify running AI agents:

| Layer | Mechanism | What It Detects | Latency |
|---|---|---|---|
| **Process Scanning** | OSHI library reads OS process table | Running agent processes by name/path/args | ~50-200ms per scan |
| **File System Watching** | JBR's FSEvents WatchService | Lock files appearing/disappearing, session state changes | ~100-500ms (FSEvents coalescing) |
| **Metadata Parsing** | Read JSON/JSONL files in session directories | Session details: workspace, first message, timestamps, working directory | On-demand after detection |

**Why three layers?**

- **Process scanning alone** can tell you "a copilot process is running" but not which project it's working on or what session it belongs to.
- **File watching alone** can tell you "a new lock file appeared in session X" but not whether the process is still alive (a crash might leave orphaned lock files).
- **Metadata parsing** enriches detections with context: the workspace folder, session name, conversation history, and token usage.

The combination gives agent-pulse high-confidence, real-time, context-rich agent monitoring.

---

## Layer 1: Process Scanning

### What We Scan For

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

### Real-World Process Evidence

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

### How to Scan: OSHI on the JVM

OSHI provides cross-platform process enumeration from Kotlin/JVM:

```kotlin
val si = SystemInfo()
val os = si.operatingSystem
val processes = os.getProcesses(null, null)

for (proc in processes) {
    val name = proc.name           // e.g., "copilot"
    val path = proc.path           // e.g., "/opt/homebrew/Caskroom/copilot-cli/0.0.420/copilot"
    val cmdLine = proc.commandLine // Full command line with arguments
    val pid = proc.processID
    val startTime = proc.startTime // Epoch millis
    val user = proc.user
}
```

agent-pulse matches processes against known signatures using a combination of:
- **Process name** (fast initial filter)
- **Executable path** (distinguishes Homebrew copilot from IntelliJ copilot-language-server)
- **Command-line arguments** (identifies ACP mode, session flags, etc.)

Source: [OSHI GitHub](https://github.com/oshi/oshi), [OSProcess API](https://www.oshi.ooo/oshi-core-java11/apidocs/com.github.oshi/oshi/software/os/OSProcess.html)

---

## Layer 2: File System Watching

### What We Watch

Each agent stores session state in predictable directories under the user's home folder. By watching these directories with JBR's native FSEvents WatchService, agent-pulse gets near-instant notifications when sessions start, stop, or change.

| Agent | Watch Directory | Key Artifacts | What Changes Mean |
|---|---|---|---|
| **Copilot CLI** | `~/.copilot/session-state/` | `inuse.<PID>.lock` files | Lock created = session started; lock deleted = session ended |
| **Claude Code** | `~/.claude/` | `projects/<hash>/memory/`, `debug/` | Session activity, memory updates |
| **Codex CLI** | `~/.codex/sessions/` | `YYYY/MM/DD/rollout-*.jsonl` | New rollout = new session; writes = active session |
| **Gemini CLI** | `~/.gemini/tmp/` | `<project_hash>/chats/` | Chat files created/modified during sessions |
| **Cursor** | `<project>/.cursor/` | `checkpoints/`, `logs/` | Agent activity, state snapshots |

### The Lock File Pattern (Copilot CLI Deep Dive)

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

### What about `vscode.metadata.json`?

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

---

## Layer 3: Metadata Parsing

Once a session is detected (via lock file or process scan), agent-pulse can parse session metadata to enrich the dashboard display.

### Copilot CLI Metadata

| File | Format | Key Fields | Purpose |
|---|---|---|---|
| `events.jsonl` | JSON Lines | Each line is a conversation event (user message, assistant response, tool call, file edit) | Full session history, token counting, activity timeline |
| `session.db` | SQLite | Session state tables | Structured query of session data |
| `workspace.yaml` | YAML | Working directory, git branch, git remote | Project context |
| `vscode.metadata.json` | JSON | Workspace folder, first message | VS Code integration context |
| `checkpoints/` | Directory of checkpoint files | Compacted history, summaries | Long session management |

### Claude Code Metadata

| File | Format | Key Fields | Purpose |
|---|---|---|---|
| `~/.claude/projects/<hash>/memory/MEMORY.md` | Markdown | Per-project persistent memory | Project context |
| `<project>/.claude/settings.json` | JSON | Permissions, tool config | Project configuration |
| `~/.claude/debug/` | Directory | Debug logs | Session diagnostics |

Claude Code also emits **OpenTelemetry (OTLP)** structured events for all session activity, including tool executions, prompts, and API requests. This is a potential future integration point for agent-pulse.

Source: [Claude Code detection engineering](https://www.monad.com/blog/detection-engineering-for-claude-code-part-1), [Claude .claude directory docs](https://code.claude.com/docs/en/claude-directory)

### Codex CLI Metadata

| File | Format | Key Fields | Purpose |
|---|---|---|---|
| `~/.codex/sessions/YYYY/MM/DD/rollout-*.jsonl` | JSON Lines | Full session transcript (input, model response, file changes, command output) | Session history |
| `~/.codex/history.jsonl` | JSON Lines | Command and session records | Global history |
| `~/.codex/config.toml` | TOML | Model, sandboxing policy, approval settings | Configuration |

Source: [OpenAI Codex CLI docs](https://developers.openai.com/codex/cli), [Codex config docs](https://developers.openai.com/codex/config-basic)

### Gemini CLI Metadata

| File | Format | Key Fields | Purpose |
|---|---|---|---|
| `~/.gemini/tmp/<project_hash>/chats/` | Chat log files | Conversation, tool calls, token stats | Per-project session history |
| `~/.gemini/settings.json` | JSON | Theme, model, MCP servers | User configuration |
| `~/.gemini/shell_history` | Text | CLI input history | Command recall |

Source: [Gemini CLI session management](https://geminicli.com/docs/cli/session-management/), [Gemini CLI config](https://github.com/google-gemini/gemini-cli/blob/main/docs/reference/configuration.md)

---

## Agent-by-Agent Analysis

### GitHub Copilot CLI

**Environment**: Terminal (standalone or VS Code integrated terminal)

**Detection method**: File watching (primary) + process scanning (secondary)

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

**Confidence level**: Very High — lock files are an explicit, documented session mechanism.

Source: [GitHub Docs: Copilot CLI session data](https://docs.github.com/en/copilot/concepts/agents/copilot-cli/chronicle), [DeepWiki: Session state](https://deepwiki.com/github/copilot-cli/6.2-session-state-and-lifecycle-management)

---

### Claude Code CLI

**Environment**: Terminal (standalone or VS Code integrated terminal)

**Detection method**: Process scanning (primary) + file watching (secondary)

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

**Confidence level**: Medium-High — process detection is reliable, but no lock-file equivalent means crash detection relies on process liveness checks.

Source: [Claude Code docs](https://code.claude.com/docs/en/claude-directory), [Detection engineering for Claude Code](https://www.monad.com/blog/detection-engineering-for-claude-code-part-1)

---

### OpenAI Codex CLI

**Environment**: Terminal

**Detection method**: Process scanning (primary) + file watching (secondary)

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

**Confidence level**: Medium-High — process name is distinctive; session files provide good history but no explicit lock mechanism.

Source: [OpenAI Codex CLI docs](https://developers.openai.com/codex/cli), [Codex config](https://developers.openai.com/codex/config-basic)

---

### Google Gemini CLI

**Environment**: Terminal

**Detection method**: Process scanning (primary) + file watching (secondary)

**Step-by-step detection:**

1. Scan for processes named `gemini` using OSHI
2. Watch `~/.gemini/tmp/` for changes in project-hashed chat directories
3. New or modified chat files in `<project_hash>/chats/` indicate active sessions
4. Parse `~/.gemini/settings.json` for model and configuration context
5. Gemini supports `--resume` for session continuity

**Process signature:**
- Name: `gemini`
- Node.js-based CLI

**Confidence level**: Medium — process name is distinctive but file artifacts are less structured than Copilot CLI.

Source: [Gemini CLI session management](https://geminicli.com/docs/cli/session-management/), [Gemini CLI GitHub](https://github.com/google-gemini/gemini-cli)

---

### GitHub Copilot in VS Code

**Environment**: VS Code extension host

**Detection method**: Process scanning (indirect) + file watching (via Copilot CLI session state)

**Step-by-step detection:**

1. When Copilot agent mode is used in VS Code, it spawns Copilot CLI processes internally
2. These appear as `Code Helper (Plugin)` processes with `copilotCLIShim.js` in the command line
3. The session state is written to the same `~/.copilot/session-state/` directory as standalone Copilot CLI
4. `vscode.metadata.json` is present in these sessions (distinguishing them from pure CLI sessions)
5. Detection is therefore identical to Copilot CLI detection, with the VS Code metadata providing additional context

**Process signature:**
- Name: `Code Helper (Plugin)` (macOS), with `copilotCLIShim.js` in args
- Child process: `copilot` (the actual CLI runtime)
- Parent process: VS Code (`Electron`)

**Confidence level**: Very High — same lock file mechanism as Copilot CLI, plus VS Code-specific metadata.

Source: [GitHub blog: Track Copilot sessions from VS Code](https://github.blog/changelog/2025-07-14-start-and-track-github-copilot-coding-agent-sessions-from-visual-studio-code/), [VS Code Copilot agents](https://code.visualstudio.com/docs/copilot/agents/overview)

---

### GitHub Copilot in IntelliJ

**Environment**: JetBrains IDE plugin

**Detection method**: Process scanning (primary)

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

**Confidence level**: High — distinctive binary path, but limited session metadata outside the IDE.

Source: [JetBrains Marketplace: GitHub Copilot plugin](https://plugins.jetbrains.com/plugin/17718-github-copilot--your-ai-pair-programmer), [GitHub blog: Copilot in JetBrains](https://github.blog/changelog/2025-05-19-agent-mode-and-mcp-support-for-copilot-in-jetbrains-eclipse-and-xcode-now-in-public-preview/)

---

### Claude Code in VS Code

**Environment**: VS Code extension

**Detection method**: Process scanning (same as Claude Code CLI)

**Step-by-step detection:**

1. The Claude Code VS Code extension acts as a **bridge** to the Claude Code CLI
2. When a session is opened, the extension launches a `claude` CLI process
3. Detection is identical to Claude Code CLI (process named `claude`)
4. The VS Code extension provides UI integration but the actual agent runs as a subprocess
5. File artifacts are the same as CLI: `~/.claude/projects/<hash>/`

**Process signature:**
- Same as Claude Code CLI (`claude` process)
- Parent process: VS Code extension host (`Code Helper (Plugin)`)

**Confidence level**: Medium-High — same as Claude Code CLI; VS Code parentage can be detected via process tree.

Source: [Claude Code VS Code docs](https://code.claude.com/docs/en/vs-code), [Claude Code VS Code extension guide](https://claudefa.st/blog/tools/extensions/claude-code-vscode)

---

### Cursor IDE & CLI

**Environment**: Standalone IDE (Electron-based, VS Code fork) + CLI agent (`cursor agent`)

**Detection method**: Process scanning (primary) + file watching (secondary) + hooks system (optional, OTel via third-party)

**Step-by-step detection:**

1. Scan for processes named `Cursor` or `Cursor Helper` using OSHI
2. Cursor stores workspace session data in a centralized `state.vscdb` SQLite database at `~/Library/Application Support/Cursor/User/workspaceStorage/<hash>/state.vscdb` (macOS)
3. The `state.vscdb` contains `composer.composerData` key in `ItemTable` with JSON blob tracking all agent sessions: IDs, names, timestamps, lines added/removed, files changed, mode (chat/edit), and more
4. AI-code tracking database at `~/.cursor/ai-tracking/ai-code-tracking.db` stores scored commits (AI vs human lines), conversation summaries, and file content tracking
5. Agent transcripts are written as JSONL at `~/.cursor/projects/<path-hash>/agent-transcripts/<composerId>/<composerId>.jsonl` with full conversation history (user messages + assistant responses)
6. MCP server metadata is stored per-project at `~/.cursor/projects/<path-hash>/mcps/<server-name>/`
7. Application logs at `~/Library/Application Support/Cursor/logs/<date>/main.log` contain agent-loop wakelock events with timestamps and durations
8. For CLI detection: scan for `cursor` process with `agent` in command line args

**Process signature:**
- IDE: `Cursor`, `Cursor Helper`, `Cursor Helper (Renderer)`, `Cursor Helper (Plugin)`
- CLI: `cursor agent` (or `cursor` with `agent` subcommand)
- Electron app bundle at `/Applications/Cursor.app`

**Key discovery: Centralized state, not just project-local.** Previously assessed as "project-local only," but inspection reveals Cursor stores rich session data centrally:

1. **`state.vscdb`** — SQLite database per workspace with full session metadata
2. **`~/.cursor/ai-tracking/ai-code-tracking.db`** — Global AI tracking database
3. **`~/.cursor/projects/`** — Agent transcripts, MCP configs, per-project data

**Cursor Hooks System (v1.7+):** Cursor has a first-class hooks system via `.cursor/hooks.json` (project-level or `~/.cursor/hooks.json` global). Hooks fire at lifecycle points including `sessionStart`, `sessionEnd`, `preToolUse`, `postToolUse`, `beforeShellExecution`, `afterFileEdit`, `beforeSubmitPrompt`, `stop`, and more. Each hook receives JSON payloads with `conversation_id`, `generation_id`, prompt content, attachments, and workspace roots.

**Third-party OTel support via hooks:** The [cursor-otel-hook](https://github.com/LangGuard-AI/cursor-otel-hook) project uses Cursor's hooks system to export structured OTel traces covering session lifecycle, tool usage, shell commands, MCP calls, file operations, prompts, and subagent activities. agent-pulse could either:
1. Bundle a similar hook that exports to its local OTLP receiver, or
2. Read the hook artifacts directly from `~/.cursor/hooks/`

**Cursor CLI Agent:** Cursor now has a full CLI agent mode (`cursor agent`) with:
- Interactive agent sessions from the terminal
- Plan/Ask/Agent modes
- Session resume (`cursor agent --resume=<session-id>`)
- Session listing (`cursor agent ls`)
- Cloud handoff support
- AGENTS.md and CLAUDE.md rule file support

**Confidence level**: High — centralized SQLite databases with rich metadata, agent transcripts, and hooks system provide multiple monitoring vectors.

Sources:
- [Cursor docs: Agent overview](https://cursor.com/docs/agent/overview)
- [Cursor docs: CLI overview](https://cursor.com/docs/cli/overview)
- [Cursor docs: Using Agent in CLI](https://cursor.com/docs/cli/using)
- [Cursor docs: Hooks](https://cursor.com/docs/hooks)
- [cursor-otel-hook](https://github.com/LangGuard-AI/cursor-otel-hook)
- [GitButler: Deep Dive into Cursor Hooks](https://blog.gitbutler.com/cursor-hooks-deep-dive)

---

## How OSHI Enables Process Detection on the JVM

[OSHI](https://github.com/oshi/oshi) (Operating System and Hardware Information) is a pure-Java library that provides cross-platform access to OS-level process information without JNI or native dependencies.

### Key API for agent-pulse

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

### Detection Algorithm

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

## File System Watching Strategy

### Directories to Watch

agent-pulse registers the following directories with JBR's native FSEvents WatchService, using the `FILE_TREE` modifier for recursive watching:

| Directory | Agent | Watch Mode | Rationale |
|---|---|---|---|
| `~/.copilot/session-state/` | Copilot CLI, Copilot VS Code | Recursive (FILE_TREE) | Lock files in UUID subdirectories |
| `~/.claude/` | Claude Code (CLI + VS Code) | Recursive (FILE_TREE) | Project memory, debug logs |
| `~/.codex/sessions/` | Codex CLI | Recursive (FILE_TREE) | Rollout files by date |
| `~/.gemini/tmp/` | Gemini CLI | Recursive (FILE_TREE) | Chat files by project hash |

### What Events We Care About

| Event | Meaning | Action |
|---|---|---|
| `ENTRY_CREATE` in `session-state/<UUID>/` | New lock file or session file | New session detected; parse metadata |
| `ENTRY_DELETE` in `session-state/<UUID>/` | Lock file removed | Session ended; update status |
| `ENTRY_MODIFY` in `session-state/<UUID>/` | events.jsonl updated | Session is active; update activity timestamp |
| `ENTRY_CREATE` in `sessions/YYYY/MM/DD/` | New Codex rollout | New Codex session detected |
| `ENTRY_CREATE` in `tmp/<hash>/chats/` | New Gemini chat | New Gemini session detected |

### Why Not Watch Project-Level Directories?

Cursor (`.cursor/`) stores state in project directories rather than a centralized home-directory location. Watching these would require knowing all active project paths upfront. Instead, agent-pulse:

1. **Discovers projects via process scanning**: When a Cursor process is found, extract the working directory from the process command line or `/proc/<PID>/cwd` equivalent
2. **Dynamically registers watches**: Add the discovered project's `.cursor/` directory to the watch list
3. **Removes watches**: When the process exits, unregister the project-level watch

This hybrid approach avoids watching the entire filesystem while still catching project-local state changes.

---

## Combining Detection Layers

### The Full Detection Pipeline

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

### Detection Confidence by Agent

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

## Extractable Information per Agent

This section documents, for each agent we support, exactly **what information agent-pulse can display in the dashboard** given the detection methods described above. This is based on real file artifacts inspected from a live system, official documentation, and process metadata available via OSHI.

### Information Categories

| Category | Description | Source |
|---|---|---|
| **Identity** | Which agent, which version, session ID | Process scanning + file metadata |
| **Context** | Working directory, project name, git branch | File metadata (workspace.yaml, etc.) |
| **Activity** | What the agent is doing right now, last user message | events.jsonl, process liveness |
| **Resource Usage** | CPU, memory, process uptime | OSHI process metrics |
| **Token Metrics** | Input/output tokens, model used, cost tier | events.jsonl shutdown/model events |
| **Code Impact** | Lines added/removed, files modified | events.jsonl shutdown events |
| **Session History** | Conversation turns, tool calls, compactions | events.jsonl event stream |
| **Lifecycle** | Start time, resume count, idle duration | Lock files + events.jsonl |

### GitHub Copilot CLI

**Richest data source of all agents.** Copilot CLI writes a structured `events.jsonl` log with every interaction, plus YAML and JSON metadata files. This was verified from a live system with 18 sessions.

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

---

### GitHub Copilot in IntelliJ

**Least data available.** IntelliJ's Copilot runs as `copilot-language-server` with no external session state files. Information is limited to what OSHI can extract from the process.

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

---

### Claude Code CLI

Claude Code has **two distinct monitoring tiers**: file-based (basic) and OpenTelemetry (excellent). Without OTel, Claude Code provides only process-level data and project memories. **With OTel enabled**, Claude Code emits structured telemetry rivaling Copilot CLI's `events.jsonl` in richness.

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

Sources:
- [Official Claude Code Monitoring Docs](https://code.claude.com/docs/en/monitoring-usage)
- [Monad: Detection engineering for Claude Code](https://www.monad.com/blog/detection-engineering-for-claude-code-part-1)
- [BindPlane: Per-Session Cost and Token Tracking](https://bindplane.com/blog/claude-code-opentelemetry-per-session-cost-and-token-tracking)

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

---

### OpenAI Codex CLI

Codex CLI writes structured JSONL rollout transcripts per session, providing moderate visibility.

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

---

### Google Gemini CLI

Gemini CLI stores per-project chat histories and configuration with moderate detail.

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

---

### Cursor IDE & CLI

**Much richer than initially assessed.** Cursor stores centralized session data in SQLite databases, writes agent transcripts as JSONL, and has a hooks system that can export OTel telemetry. This was verified from a live Cursor installation on this machine.

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

#### Tier 3: Application Logs & Hooks

| Data Point | Source | How to Extract | Available |
|---|---|---|---|
| Agent-loop wakelocks | `~/Library/.../Cursor/logs/<date>/main.log` | Parse `[PowerMainService]` wakelock entries | Yes |
| Wakelock durations | main.log | `heldForMs=<N>` in wakelock stop entries | Yes |
| Agent-loop reason | main.log | `reason="agent-loop"` or `"agent-loop-resumed"` | Yes |
| MCP server configs | `~/.cursor/projects/<hash>/mcps/<server>/` | `SERVER_METADATA.json` | Yes |
| Cursor skills | `~/.cursor/skills-cursor/` | Manifest + SKILL.md files | Yes |
| IDE state | `~/.cursor/ide_state.json` | JSON with recently viewed files | Yes |
| Process metrics | OSHI | CPU/memory/uptime of Cursor processes | Yes |

#### Tier 4: Hooks + OTel (Optional, via cursor-otel-hook)

If agent-pulse provides a hook script (or the user installs cursor-otel-hook), Cursor's hooks system sends JSON payloads for every agent lifecycle event to the hook. This can export to agent-pulse's OTLP receiver.

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
- **This would make Cursor monitoring approach "Excellent" level**

---

### Summary: Data Richness by Agent

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

**Key takeaway:** GitHub Copilot CLI, Claude Code (with OTel), and Cursor are the three richest monitoring targets. Copilot writes everything to local files automatically. Claude Code requires opt-in telemetry configuration but then provides equally rich data via OTLP. Cursor stores centralized SQLite databases with session metadata, AI-code tracking (AI vs human lines per commit), and full conversation transcripts as JSONL — plus an optional hooks system for real-time event streaming. **agent-pulse should treat OTLP ingestion as a first-class feature**, since it transforms both Claude Code and Cursor (via hooks) from limited to excellent, and positions agent-pulse as a zero-config local receiver that replaces the need for Grafana/Prometheus stacks for individual developers.

---

## Architectural Implication: Embedded OTLP Receiver

The Claude Code telemetry discovery has a significant architectural implication for agent-pulse. To unlock "Excellent" monitoring for Claude Code (and potentially other OTel-emitting agents in the future), agent-pulse needs an **embedded OTLP receiver** — a local gRPC/HTTP server that accepts standard OpenTelemetry data.

### Why This Matters

Without an OTLP receiver, agent-pulse is limited to file-watching and process-scanning. That works well for Copilot CLI (which writes rich `events.jsonl` files) but leaves Claude Code at "Basic" level — just process detection and project memory files. With an OTLP receiver, Claude Code jumps to "Excellent" — matching Copilot CLI's richness with tokens, cost, tool calls, code impact, and more.

This also future-proofs agent-pulse. Any tool that adopts OpenTelemetry (and the trend is clear — Claude Code, LangChain, LlamaIndex all support it) can feed data into agent-pulse without needing a custom provider.

### Implementation Approach

**agent-pulse acts as a lightweight, embedded OTLP collector on localhost.** No separate Collector process, no Docker, no Prometheus stack — just a gRPC or HTTP endpoint built into the app.

Two viable approaches for the Kotlin/JVM stack:

#### Option 1: gRPC Server (Recommended)

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

#### Option 2: HTTP/JSON Endpoint

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

### User Experience Flow

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

### Port Conflict Handling

If another OTLP collector is already running on port 4317 (e.g., Grafana Alloy, otel-collector), agent-pulse should:
1. Detect the conflict on startup
2. Offer to use an alternative port (e.g., 4320)
3. Update the Claude Code configuration accordingly
4. Or offer "passthrough" mode: receive data and forward to the existing collector

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

### Live System Evidence
- Process signatures captured via `ps aux` on macOS (Apple Silicon, 2026-04-02)
- File system artifacts inspected from `~/.copilot/session-state/`, `~/.claude/`, `~/.codex/`, `~/.cursor/`, `~/Library/Application Support/Cursor/`
- Cursor `state.vscdb` and `ai-code-tracking.db` SQLite schemas verified via live database inspection
- Cursor agent transcripts (JSONL) verified at `~/.cursor/projects/*/agent-transcripts/`
- Lock file format and contents verified from live running sessions
