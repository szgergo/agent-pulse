# Research: Can CLI Agents Read Cross-Session Data?

**Question**: Can we use the CLI versions of AI coding agents to gather rich data about
_other_ running agent sessions on the same machine?

**Date**: 2026-04-04  
**Environment**: macOS (Apple Silicon), Copilot CLI 1.0.18, Cursor 1.x, Claude Code, Gemini CLI, Codex CLI  
**Method**: Direct filesystem inspection, SQLite queries, CLI invocation on live machine with real sessions

---

## Executive Summary

**Yes — overwhelmingly.** Every agent stores session data in user-owned files with no encryption
and no inter-session access controls. The richest source discovered is **Copilot CLI's
`events.jsonl`**, which contains per-model token counts, costs, code changes, tool calls, and
full session lifecycle events — all readable by any process running as the same OS user.

| Agent | Session files readable? | Token/cost data? | Cross-session CLI? | Data richness |
|-------|------------------------|-------------------|--------------------|--------------:|
| **Copilot CLI** | ✅ events.jsonl, workspace.yaml, session.db | ✅ Per-model tokens, premium reqs, cost | `--resume[=id]`, `--continue` | ★★★★★ |
| **Claude Code** | ✅ `<session>.jsonl` in projects/ | ⚠️ In conversation only | `/resume [id]` | ★★★☆☆ |
| **Cursor** | ✅ agent-transcripts/*.jsonl, SQLite DB | ✅ AI percentage per commit | No CLI (IDE only) | ★★★★☆ |
| **Gemini CLI** | ✅ chats/*.json | ✅ Token counts in chat files | `--list-sessions`, `--resume` | ★★★☆☆ |
| **Codex CLI** | ⚠️ sessions/ dir exists (empty on this machine) | ⚠️ Unknown | No known session listing | ★☆☆☆☆ |

---

## 1. GitHub Copilot CLI — The Gold Mine

### 1.1 Session Discovery

**Path**: `~/.copilot/session-state/<UUID>/`

Each session directory contains:

```
~/.copilot/session-state/<UUID>/
├── events.jsonl          ← THE RICHEST DATA SOURCE IN THIS ENTIRE PROJECT
├── workspace.yaml        ← Instant session metadata (repo, branch, cwd)
├── session.db            ← SQLite (todos, planning data)
├── inuse.<PID>.lock      ← Active session indicator (content = PID)
├── checkpoints/          ← Session checkpoint history
└── files/                ← Persistent session files
```

**Verified on this machine**: 47 session directories, 4 with active lock files.

**workspace.yaml** provides instant session indexing:

```yaml
# ~/.copilot/session-state/9efc8dc9-2d3e-4d2c-b7a1-76611d720f0c/workspace.yaml
id: 9efc8dc9-2d3e-4d2c-b7a1-76611d720f0c
cwd: /Users/gergoszabo/Documents/Projects/DriftingPulsar
git_root: /Users/gergoszabo/Documents/Projects/DriftingPulsar
repository: szgergo/DriftingPulsar
branch: master
created_at: 2026-03-13T11:10:44.478Z
```

**Lock files** (`inuse.<PID>.lock`) are `644` permissions — any process can detect active sessions.

### 1.2 events.jsonl — Comprehensive Session Telemetry

**This is the single most important discovery of this research.**

Each session's `events.jsonl` is a JSON Lines file containing every lifecycle event. We found
**25 distinct event types** in the current active session (b8c53b29, 13,500+ events):

| Event Type | Count | Key Data Fields |
|-----------|------:|----------------|
| `session.start` | 1 | sessionId, copilotVersion, cwd, startTime |
| `session.resume` | 50 | **selectedModel**, **reasoningEffort**, gitContext (branch/repo/commit) |
| `session.shutdown` | 46 | **modelMetrics** (per-model: inputTokens, outputTokens, cacheTokens, cost, request count), **codeChanges** (linesAdded, linesRemoved, filesModified[]), totalApiDurationMs |
| `session.model_change` | 7 | previousModel, newModel, reasoningEffort |
| `session.compaction_complete` | 29 | pre/post token counts (system, conversation, toolDefinitions) |
| `session.mode_changed` | 52 | mode (e.g., plan, autopilot, interactive) |
| `session.plan_changed` | 80 | Plan state changes |
| `assistant.message` | 2,243 | outputTokens, tool requests with arguments, content |
| `assistant.turn_start` / `turn_end` | 3,067 | Turn lifecycle boundaries |
| `user.message` | 149 | content, attachments, agentMode |
| `tool.execution_start` | 3,736 | toolName, arguments (including MCP tool names) |
| `tool.execution_complete` | 3,727 | model, success, result content, toolTelemetry |
| `subagent.started` / `completed` | 125 | **model**, **totalToolCalls**, **totalTokens**, **durationMs** |
| `system.notification` | 59 | System-level notifications |

### 1.3 Real Data Extracted from Live Sessions

**Cross-session token metrics** (extracted from `session.shutdown` events across 12 sessions):

| Session | Models Used | Input Tokens | Output Tokens | Premium Reqs | Code Changes |
|---------|------------|-------------:|--------------:|-------------:|-------------|
| b8c53b29 (current) | opus-4.6 (104r) + haiku-4.5 (38r) | 9,620,513 | 73,630 | 27 | +10,169 / -2,482 in 22 files |
| 031ae44b | sonnet-4.6 (51r) | 1,840,735 | 54,433 | 3 | 0 changes |
| 9143d31b | opus-4.6 (45r) + haiku-4.5 (13r) | 1,509,326 | 28,574 | 3 | 0 changes |
| 5db15873 | sonnet-4.6 (35r) | 1,274,809 | 61,478 | 3 | +56 in 1 file |
| 04e96e39 | gpt-5-mini (44r) | 1,300,287 | 12,370 | 0 | +23/-3 in 3 files |
| ed82a6fd | gpt-5.4 (14r) | 560,030 | 24,506 | 2 | +20/-1 in 2 files |

**Subagent metrics** (from the current session):

```json
{
  "model": "claude-haiku-4.5",
  "totalToolCalls": 35,
  "totalTokens": 344131,
  "durationMs": 176446
}
```

### 1.4 session.db (SQLite)

**Permissions**: `644` — world-readable. Readable in `?mode=ro` even while session is active.

**Schema**: `todos` (id, title, description, status, created_at, updated_at) + `todo_deps` (todo_id, depends_on)

Contains session planning state — useful for understanding what an agent is working on.

### 1.5 CLI Session Management

```
copilot --continue                   # Resume most recent session
copilot --resume                     # Interactive session picker
copilot --resume=<UUID>              # Resume specific session
copilot --resume=<task-id>           # Resume by task ID
```

**No `list` or `status` command exists.** Session discovery is filesystem-only.

### 1.6 Verification Commands

```bash
# List all sessions with metadata
for dir in ~/.copilot/session-state/*/; do
    cat "$dir/workspace.yaml" 2>/dev/null
done

# Find active sessions
ls ~/.copilot/session-state/*/inuse.*.lock 2>/dev/null

# Extract shutdown metrics from any session
grep '"type":"session.shutdown"' ~/.copilot/session-state/<UUID>/events.jsonl | \
    python3 -c "import sys,json; [print(json.dumps(json.loads(l)['data']['modelMetrics'],indent=2)) for l in sys.stdin]"
```

---

## 2. Cursor IDE

### 2.1 AI Code Tracking Database

**Path**: `~/.cursor/ai-tracking/ai-code-tracking.db`  
**Type**: SQLite 3.x  
**Readable while IDE running**: ✅ Yes (WAL mode)

**Verified schema**:

```sql
CREATE TABLE ai_code_hashes (
    hash TEXT PRIMARY KEY,
    source TEXT NOT NULL,           -- 'composer', 'tab', etc.
    fileExtension TEXT,             -- '.css', '.html', '.js', etc.
    fileName TEXT,
    requestId TEXT,
    conversationId TEXT,
    timestamp INTEGER,
    model TEXT,                     -- e.g. 'default'
    createdAt INTEGER NOT NULL
);

CREATE TABLE scored_commits (
    commitHash TEXT NOT NULL,
    branchName TEXT NOT NULL,
    linesAdded INTEGER,
    linesDeleted INTEGER,
    tabLinesAdded INTEGER,          -- AI tab-completion lines
    composerLinesAdded INTEGER,     -- AI composer lines
    humanLinesAdded INTEGER,        -- Human-written lines
    v2AiPercentage TEXT,            -- AI authorship percentage!
    PRIMARY KEY (commitHash, branchName)
);

CREATE TABLE conversation_summaries (
    conversationId TEXT PRIMARY KEY,
    title TEXT,
    tldr TEXT,
    overview TEXT,
    summaryBullets TEXT,
    model TEXT,
    mode TEXT,                      -- 'agent', 'chat', etc.
    updatedAt INTEGER NOT NULL
);
```

**Verified data on this machine** (568 tracked code hashes):

| Source | Model | Extension | Count |
|--------|-------|-----------|------:|
| composer | default | .css | 359 |
| composer | default | .html | 180 |
| composer | default | .js | 29 |

The `scored_commits` table is **exceptionally valuable** — it computes what percentage of each
commit's code was AI-generated vs human-written, broken down by tab completion vs composer/agent.

### 2.2 Agent Transcripts

**Path**: `~/.cursor/projects/<path-slug>/agent-transcripts/<UUID>/<UUID>.jsonl`

**Format**: JSON Lines with full conversation turns:

```json
{"role":"user","message":{"content":[{"type":"text","text":"can you create git here..."}]}}
{"role":"assistant","message":{"content":[{"type":"text","text":"I'll set this folder up..."}]}}
```

**Verified on this machine**: Found transcript for project `Users-gergoszabo-Documents-Projects`.

### 2.3 Limitations

- **No CLI interface**: Cursor is IDE-only. No command-line access to sessions.
- **Hooks system (v1.7+)**: Exists but not documented for cross-session awareness.
- **No token/cost data in DB**: The SQLite schema tracks code hashes and AI percentages, not token usage.

### 2.4 Verification Commands

```bash
# Query conversation summaries
sqlite3 ~/.cursor/ai-tracking/ai-code-tracking.db \
    "SELECT conversationId, title, model, mode FROM conversation_summaries ORDER BY updatedAt DESC LIMIT 10;"

# Read agent transcripts
cat ~/.cursor/projects/*/agent-transcripts/*/*.jsonl | head -5

# AI authorship breakdown
sqlite3 ~/.cursor/ai-tracking/ai-code-tracking.db \
    "SELECT commitHash, v2AiPercentage, linesAdded, humanLinesAdded FROM scored_commits ORDER BY scoredAt DESC LIMIT 10;"
```

---

## 3. Claude Code

### 3.1 Session Files

**Path**: `~/.claude/projects/<folder-hash>/<SESSION_UUID>.jsonl`

Each session is a JSONL file containing the full conversation (user prompts + assistant
responses + tool calls). Files are owned by the user with standard permissions.

**On this machine**: The `~/.claude/projects/` directory exists but contains no session files
(Claude Code was used minimally — only debug logs found).

### 3.2 Debug Logs

**Path**: `~/.claude/debug/<UUID>.txt`  
**Permissions**: `644` (world-readable)  
**Content**: Query processing logs with timestamps

**Verified**: 2 debug log files found on this machine.

### 3.3 Configuration

**Path**: `~/.claude.json`

```json
{
  "firstStartTime": "2026-02-28T14:17:21.338Z",
  "userID": "8184a7ac...",
  "mcpServers": {}
}
```

### 3.4 Community MCP Server for Cross-Session Access

**Repository**: https://github.com/es6kr/claude-code-sessions (11 ★)

Provides MCP tools for cross-session access:

- `list_projects()` — Discover all projects
- `list_sessions(project)` — List sessions in any project
- `summarize_session(project, session)` — Extract conversation
- `analyze_session(project, session)` — Session analytics

**Installation**:

```bash
claude mcp add claude-sessions -- npx claude-sessions-mcp
```

### 3.5 Session Management

```
/resume              # Interactive session picker (within Claude Code)
/resume <UUID>       # Resume specific session
```

### 3.6 Data Richness Assessment

Claude Code sessions contain **conversation history only** — no structured metrics like token
counts, model usage, or code change summaries. To get those, you'd need to parse the
conversation text.

### 3.7 Verification Commands

```bash
# List all projects with sessions
ls ~/.claude/projects/

# Read a session file
cat ~/.claude/projects/<folder-hash>/<UUID>.jsonl | head -5

# Parse conversation turns
cat ~/.claude/projects/<folder-hash>/<UUID>.jsonl | \
    python3 -c "import sys,json; [print(json.loads(l).get('role','?')) for l in sys.stdin]"
```

---

## 4. Gemini CLI

### 4.1 Session Files

**Path**: `~/.gemini/tmp/<project_hash>/chats/<session>.json`

Each session is a JSON file containing:
- Complete conversation history (prompts + responses)
- Tool executions (inputs/outputs)
- Token usage (input, output, cached)
- Timestamps

### 4.2 CLI Session Management (Best in Class)

Gemini CLI has the **most complete session management CLI** of all agents:

```bash
gemini --list-sessions       # List all sessions with metadata
gemini --resume              # Interactive session picker
gemini --resume 1            # Resume by index
gemini --resume <UUID>       # Resume by ID
gemini --delete-session 2    # Delete session by index
```

### 4.3 Project Registry

**Path**: `~/.gemini/projects.json`

Maps project paths to unique identifiers — provides a global index of all known projects.

### 4.4 Other Files

```
~/.gemini/
├── tmp/<project_hash>/
│   ├── chats/           ← Session conversation files
│   ├── shell_history    ← All shell commands executed
│   ├── memory/          ← Agent memory
│   └── plans/           ← Planning documents
├── projects.json        ← Project registry
├── settings.json        ← Global config (retention policies)
└── agents/              ← Custom agent definitions
```

### 4.5 Status on This Machine

**Gemini CLI is not installed** on this machine. The `~/.gemini/` directory exists but is
essentially empty (only `tmp/` stub). Findings are based on documentation and source code
analysis.

### 4.6 Verification Commands

```bash
# List sessions (when installed)
gemini --list-sessions

# Read session file
cat ~/.gemini/tmp/<project_hash>/chats/<session>.json | python3 -m json.tool

# Check token usage in session
cat ~/.gemini/tmp/<project_hash>/chats/<session>.json | \
    python3 -c "import sys,json; d=json.load(sys.stdin); [print(t.get('tokenUsage',{})) for t in d.get('turns',[])]"
```

---

## 5. Codex CLI

### 5.1 Session Files

**Expected path**: `~/.codex/sessions/YYYY/MM/DD/rollout-*.jsonl`

**Status on this machine**: Directory exists but is **empty**. Codex CLI is not installed.

### 5.2 Known Limitations

Based on documentation analysis:

- **No session listing command** found
- **Fire-and-forget architecture**: Only supports notify hooks (no bidirectional hook protocol)
- **MCP support**: Available via `~/.codex/config.toml` for tool access

### 5.3 Configuration

**Path**: `~/.codex/config.toml`

```toml
[mcp_servers.my_server]
command = "npx"
args = ["my-mcp-server"]
```

### 5.4 Data Richness Assessment

**Lowest of all agents.** Session data format is poorly documented, CLI has no session management
commands, and the notify-only hook architecture limits what can be captured.

---

## 6. Comparative Analysis

### 6.1 Data Access Matrix

| Data Point | Copilot CLI | Claude Code | Cursor | Gemini CLI | Codex CLI |
|-----------|:-----------:|:-----------:|:------:|:----------:|:---------:|
| Session discovery | ✅ Filesystem | ✅ Filesystem | ✅ SQLite | ✅ CLI + FS | ⚠️ Filesystem |
| Active session detection | ✅ Lock files | ⚠️ No locks found | ❌ No mechanism | ❓ Unknown | ❓ Unknown |
| Token usage | ✅ Per-model | ❌ Not structured | ❌ Not in DB | ✅ In chat JSON | ❓ Unknown |
| Cost / premium requests | ✅ In shutdown event | ❌ Not available | ❌ Not available | ❌ Not available | ❌ Not available |
| Code changes summary | ✅ Lines ±, files modified | ❌ Not structured | ✅ AI% per commit | ❌ Not structured | ❓ Unknown |
| Model identification | ✅ Exact model + version | ✅ In conversation | ✅ In DB (generic) | ✅ In chat JSON | ❓ Unknown |
| Tool call history | ✅ Full args + results | ✅ In conversation | ✅ In transcripts | ✅ In chat JSON | ❓ Unknown |
| Subagent metrics | ✅ tokens, tools, duration | ❌ N/A | ❌ N/A | ❌ N/A | ❌ N/A |
| Conversation content | ✅ Full text | ✅ Full text | ✅ Full text | ✅ Full text | ⚠️ Unknown |
| Readable while active | ✅ Yes | ✅ Yes | ✅ Yes (SQLite) | ✅ Yes | ⚠️ Unknown |

### 6.2 Integration Effort for agent-pulse

| Agent | Effort | Method | Value |
|-------|--------|--------|-------|
| **Copilot CLI** | Low | Read events.jsonl + workspace.yaml | 🔥 Extremely high — all metrics |
| **Cursor** | Medium | Query SQLite DB + read transcripts | High — AI% unique |
| **Claude Code** | Medium | Read session .jsonl files | Medium — conversations only |
| **Gemini CLI** | Medium | Read chat JSON files | Medium — tokens available |
| **Codex CLI** | High | Parse session rollout files | Low — limited data |

---

## 7. Security & Privacy Notes

All agents store session data with **user-level file permissions only**. There is:

- ❌ No encryption at rest
- ❌ No inter-session access controls
- ❌ No audit logging of cross-session reads
- ❌ No capability-based access tokens

This means **any process running as the user can read all agent session data**. This is
consistent with standard Unix security models but worth noting for the agent-pulse design:
we don't need any special permissions or hacks — just standard file I/O.

**File permissions verified on this machine**:

| File | Permissions | Notes |
|------|------------|-------|
| `~/.copilot/session-state/<UUID>/` | `drwx------` (700) | Directory restricted |
| `events.jsonl` | `-rw-------` (600) | Owner-only, but we ARE the owner |
| `inuse.<PID>.lock` | `-rw-r--r--` (644) | World-readable |
| `session.db` | `-rw-r--r--` (644) | World-readable |
| `workspace.yaml` | `-rw-r--r--` (644) | World-readable |
| `~/.cursor/ai-tracking/*.db` | `-rw-r--r--` (644) | World-readable |
| `~/.claude/debug/*.txt` | `-rw-r--r--` (644) | World-readable |

---

## 8. Recommendations for agent-pulse

### 8.1 Primary Recommendation: Enrichment Layer via events.jsonl

**Copilot CLI's events.jsonl should be the foundation of the enrichment layer (Step 9+).**

Instead of relying solely on hooks (which give us ~225 bytes per event), we can read events.jsonl
from active sessions to get:

- Exact model name and version
- Token counts (input, output, cache read/write) per model
- Premium request count and cost
- Code changes (lines added/removed, files modified)
- Tool calls with arguments and results
- Subagent metrics (tokens, duration, tool calls)
- Session duration and API response time totals

This is **orders of magnitude richer** than what hooks alone provide.

### 8.2 Proposed Architecture Addition

```
┌────────────────────────────────────────────────────────┐
│                    ENRICHMENT LAYER                     │
│                                                         │
│  HookEventWatcher (existing)                            │
│    └── "Agent X started/stopped" (lightweight)          │
│                                                         │
│  SessionDataReader (NEW)                                │
│    ├── Copilot: events.jsonl → token/cost/code metrics  │
│    ├── Cursor: ai-code-tracking.db → AI% per commit     │
│    ├── Claude: <session>.jsonl → conversation stats      │
│    └── Gemini: chats/<session>.json → token usage        │
│                                                         │
│  Merged into AgentState:                                │
│    state + metrics + code changes + cost                │
└────────────────────────────────────────────────────────┘
```

### 8.3 Pros

1. **Zero additional infrastructure** — just read files that already exist
2. **No agent cooperation needed** — no instructions, no MCP servers, no hooks
3. **Real-time capable** — events.jsonl is append-only, can tail with WatchService
4. **Richest possible data** — more data than agents expose through any API
5. **Works retroactively** — can analyze past sessions that ran before agent-pulse was installed
6. **No performance impact on agents** — passive file reading only

### 8.4 Cons

1. **Undocumented format** — events.jsonl schema is not a public API; could change without notice
2. **Agent-specific parsers needed** — each agent stores data differently (5 different formats)
3. **Privacy concerns** — reading full conversation content may be unexpected by users
4. **File locking risk** — need read-only access to avoid interfering with active sessions
   (SQLite: `?mode=ro`; JSONL: standard file read is safe)
5. **Data volume** — events.jsonl can be large (current session: 13,500+ events). Need incremental
   parsing with offset tracking.

### 8.5 Risk Mitigation

| Risk | Mitigation |
|------|-----------|
| Schema changes | Version detection via `copilotVersion` in `session.start` event; graceful degradation |
| Large files | Seek to last-read offset; only parse new lines (JSONL is append-only) |
| Privacy | Only extract structured metrics (tokens, cost, code stats); never display conversation content |
| File locking | Read-only file handles; SQLite with `?mode=ro` parameter |
| Agent not installed | Graceful skip — each reader is independent |

---

## 9. Summary

The filesystem-based session data of all five agents is **freely accessible** to any process
running as the same OS user. No special permissions, APIs, or agent cooperation is required.

**Copilot CLI stands out dramatically** — its events.jsonl contains structured telemetry that
rivals what you'd get from an official API: per-model token counts, cost/premium request tracking,
code change summaries, tool call logs, and subagent metrics. This single file per session is more
valuable than all other agents' data combined.

**Cursor offers a unique metric** — AI authorship percentage per git commit — that no other agent
provides. The SQLite database is queryable and structured.

**Claude Code and Gemini CLI** store rich conversations but lack structured metrics. Useful for
conversation statistics (message count, duration) but require text parsing for deeper analysis.

**Codex CLI** has the least accessible data and should be lowest priority for enrichment.

**Bottom line**: The hooks + filesystem reading combination gives agent-pulse access to more data
about running agents than any of the agents' own CLI tools expose. This is a significant
competitive advantage for the project.
