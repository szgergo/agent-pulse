# Agent Extensibility & Push-Based Monitoring Research

## Purpose

This document consolidates deep research into how agent-pulse can receive data FROM AI coding agents via their native hook/plugin/telemetry systems, rather than scraping files passively. This is a companion to `agent-monitoring-research.md` which covers file-based detection.

**Key finding: 4 of 5 agents support push-based lifecycle hooks — the architecture should prioritize push over pull.**

---

## Table of Contents

1. [Architecture Shift: Pull vs Push](#architecture-shift-pull-vs-push)
2. [Per-Agent Session Lifecycle Signals](#per-agent-session-lifecycle-signals)
3. [Per-Agent Hook/Plugin Systems](#per-agent-hookplugin-systems)
4. [Token Usage & Cost Data](#token-usage--cost-data)
5. [MCP as Universal Connection](#mcp-as-universal-connection)
6. [Process Event Monitoring (Native APIs)](#process-event-monitoring-native-apis)
7. [Hook Deployment Strategy](#hook-deployment-strategy)
8. [Product Positioning](#product-positioning)

---

## Architecture Shift: Pull vs Push

### Old Model (file scraping)

```
agent-pulse polls/watches → agent files on disk → parses data
```

Problems: heuristic detection, no end-of-session signal for 3/5 agents, O(n) file scanning.

### New Model (hooks push data)

```
Agent fires lifecycle hook → POSTs JSON to agent-pulse HTTP API on localhost
```

Benefits: definitive lifecycle signals, richer data, token counts, zero polling for 4/5 agents.

### Hybrid Model (recommended)

```
Primary:  Agent hooks → POST to localhost:9876 → agent-pulse receives
Fallback: File watching + process check (for Cursor, and when hooks aren't configured)
```

---

## Per-Agent Session Lifecycle Signals

### What Each Agent Writes to Disk (Summary)

| Signal | Copilot CLI | Claude Code | Cursor | Codex | Gemini |
|---|---|---|---|---|---|
| Per-session lock file | ✅ `inuse.<PID>.lock` | ✅ `~/.claude/session-health/{id}.lock` (JSON w/ PID, version, heartbeat) | ❌ App-level `code.lock` only | ❌ | ❌ |
| Session start signal | Lock created | Lock created | chatSession JSON created | Rollout JSONL with `session_meta` | Session JSON created |
| Session end signal | Lock deleted + shutdown event | Lock deleted | `status=Completed` | ❌ Nothing | ❌ Nothing |
| Last activity | JSONL append time | Lock `lastHeartbeat` | `timing.lastRequestEnded` | Rollout file mtime | `lastUpdated` field |

### Honest Assessment

- **Copilot CLI + Claude Code**: Definitive lock-file-based lifecycle ✅
- **Cursor**: App-level detection + per-session files with status field ⚠️
- **Codex + Gemini**: Start signal only, no end marker — timestamp heuristic ⚠️

---

## Per-Agent Hook/Plugin Systems

### Claude Code — Hooks System ⭐⭐⭐

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

### Gemini CLI — Hooks System ⭐⭐⭐

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

### Codex CLI — Notify Hook ⭐⭐

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

### Cursor — VS Code Extension ⚠️

**No native hook system.** Requires building a VS Code extension.

**Stable VS Code APIs available:**
- `vscode.lm.selectChatModels()` — get available AI models
- `LanguageModelChat.countTokens()` — token counting ✅
- `vscode.lm.onDidChangeChatModels` — model change events

**Proposed (unstable) APIs:**
- `ChatHookType.SessionStart` / `ChatHookType.SessionEnd` — lifecycle events 🟡
- `ChatDebugModelTurnEvent` — detailed chat events with token info 🟡

**Communication from extension to agent-pulse:** HTTP POST, WebSocket, or file writing.

**Implementation effort:** ~8-16h for a working extension vs ~1-2h for hook config in other agents.

**Fallback (no extension needed):**
- MCP server connection → alive/dead signal
- FileWatcher on `chatSessions/*.json` → session creation
- `state.vscdb` SQLite queries → timing, status, context %
- `code.lock` → app PID

---

### Copilot CLI — Richest Passive Data ✅

No hook configuration needed. Copilot CLI already writes the richest local file artifacts of all agents by default: lock files with PIDs for lifecycle, `events.jsonl` with full token metrics and tool calls, `workspace.yaml` with session metadata. agent-pulse simply reads these existing files — no deployment step required for this agent.

---

### Summary Table

| Agent | Mechanism | Effort | Session Start | Session End | Per-Turn Data | Token Data |
|---|---|---|---|---|---|---|
| **Copilot CLI** | Passive file reading | None (data already on disk) | ✅ | ✅ | ✅ | ✅ |
| **Claude Code** | Hooks + OTLP | ~2h config | ✅ `SessionStart` | ✅ `SessionEnd` | ✅ `PostToolUse` | ✅ Via OTLP |
| **Gemini** | Hooks + OTel | ~2h config | ✅ `SessionStart` | ✅ `SessionEnd` | ✅ `AfterTool` | ⚠️ Via OTel |
| **Codex** | Notify hook + OTel | ~1h config | ⚠️ First turn | ⚠️ Inferred | ✅ Every turn | ⚠️ Via OTel |
| **Cursor** | VS Code extension | ~8-16h dev | 🟡 Proposed API | 🟡 Proposed API | ✅ Stable API | ✅ `countTokens()` |

---

## Token Usage & Cost Data

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

## Process Event Monitoring (Native APIs)

Research into avoiding OSHI's 50-300ms full process tree scan.

### Per-OS Best Approach

| OS | Discovery (new processes) | Exit Detection | Permissions | JNA Feasible | Effort |
|---|---|---|---|---|---|
| **macOS** | `sysctl(KERN_PROC)` poll 1-2s (~1-5ms) | `kqueue(EVFILT_PROC)` push ~1ms | No root, no SIP | ✅ | ~500 LOC |
| **Linux** | Netlink CN_PROC push (no polling!) | Netlink CN_PROC push | `CAP_NET_ADMIN` (non-root on kernel 6.2+) | ✅ | ~300-500 LOC |
| **Windows** | WMI `Win32_ProcessStartTrace` push | `RegisterWaitForSingleObject` push ~1ms | Admin preferred for creation; SYNCHRONIZE for exit | ✅ | ~500 LOC |

### Ruled Out

- **macOS Endpoint Security**: Locked behind Apple entitlements (App Store/MDM only)
- **macOS NSWorkspace**: Only sees `.app` bundles, not CLI tools
- **macOS DTrace**: SIP blocks it, high overhead
- **Linux inotify/fanotify on /proc**: Doesn't work on procfs
- **Linux eBPF**: Overkill, requires root
- **Windows ETW**: Requires admin, complex JVM integration

### Architecture Role

Native process monitoring is a **complement to hooks**, not a replacement:
- Hooks tell us "session started/ended" with rich data
- Process monitor tells us "is this PID still alive?" for agents without end signals (Codex, Gemini fallback)

---

## Hook Deployment Strategy

### Shared Helper Script

All agents call one script — update logic in one place:

```
~/.agent-pulse/
├── hooks/
│   ├── report.sh          ← macOS/Linux (bash + curl)
│   └── report.ps1         ← Windows (PowerShell)
├── config.json             ← Which agents are configured
└── backups/                ← Original configs before modification
```

`report.sh`:
```bash
#!/bin/bash
cat | curl -s --max-time 2 -X POST http://localhost:9876/api/event \
  -H "Content-Type: application/json" -d @- 2>/dev/null || true
```

### Auto-Configuration (first launch)

1. Detect installed agents: check `~/.claude/`, `~/.codex/`, `~/.gemini/`, Cursor app data
2. Show setup wizard in system tray UI
3. Back up original configs to `~/.agent-pulse/backups/`
4. Merge hook entries into each agent's config (preserve existing settings)
5. Start local HTTP listener on `localhost:9876`

### Config Paths (cross-platform)

| Agent | macOS/Linux | Windows |
|---|---|---|
| Claude Code | `~/.claude/settings.json` | `%USERPROFILE%\.claude\settings.json` |
| Codex | `~/.codex/config.toml` | `%USERPROFILE%\.codex\config.toml` |
| Gemini | `~/.gemini/settings.json` | `%USERPROFILE%\.gemini\settings.json` |
| Cursor | `~/Library/Application Support/Cursor/` (macOS) / `~/.config/Cursor/` (Linux) | `%APPDATA%\Cursor\` |

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

- Claude Code repository: `github.com/anthropics/claude-code` — hooks, plugins, OTLP
- OpenAI Codex repository: `github.com/openai/codex` — `codex-rs/hooks/`, `codex-rs/otel/`
- Google Gemini CLI repository: `github.com/google-gemini/gemini-cli` — hooks, extensions, telemetry
- VS Code repository: `github.com/microsoft/vscode` — chat APIs, language model API
- MCP specification: `modelcontextprotocol.io` — lifecycle, notifications
- Apple kqueue man pages — EVFILT_PROC
- Linux kernel documentation — netlink proc connector (CN_PROC)
- Windows MSDN — WMI events, RegisterWaitForSingleObject
