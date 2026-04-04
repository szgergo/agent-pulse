# Research: Competitive Landscape — AI Agent Monitoring Tools

**Question**: What community tools already parse AI agent session files, and how does
agent-pulse compare?

**Date**: 2026-04-04  
**Method**: GitHub search (15+ queries), repo analysis, README/source inspection  
**Finding**: The market is **much larger than expected** — 53+ active projects, with several
having thousands of stars. However, the space is fragmented with clear differentiation
opportunities.

---

## Executive Summary

Agent-pulse enters a **crowded but fragmented market**. There are 53+ active projects in
the AI agent monitoring space, but they cluster into distinct niches. No single tool does
exactly what agent-pulse plans to do: a **native desktop system-tray app** monitoring
**all 5 major agents** with **hook-based + file-reading enrichment** and **real-time
token/cost metrics**.

The closest competitors are:

| Tool | Stars | Threat Level | Why |
|------|------:|:------------|-----|
| **agentlytics** | 403 | 🔴 Very High | Electron desktop app, 8 agents, real-time, cost tracking |
| **tokscale** | 1,579 | 🔴 High | CLI + leaderboard, 10+ agents, very popular |
| **splitrail** | 144 | 🟡 Medium | Rust desktop, 11+ agents, real-time |
| **ccboard** | 45 | 🟡 Medium | Rust TUI+Web, hook-based (similar tech approach) |

But none of them are:
- Native Kotlin/Compose Desktop (all use Electron, Rust, or Python)
- System-tray always-on (most are launch-on-demand CLI/web)
- Hook-based for ALL agents (most use only file reading or only Claude hooks)
- Focused on exactly the 5 core agents developers actually use

---

## 1. The Top Competitors (by relevance to agent-pulse)

### 1.1 agentlytics — MOST SIMILAR 🔴

| | |
|---|---|
| **URL** | https://github.com/f/agentlytics |
| **Stars** | 403 |
| **Language** | JavaScript (React/Electron) |
| **Form** | macOS desktop app (Electron) |
| **Agents** | 8: Cursor, Windsurf, Claude Code, VS Code Copilot, Zed, Antigravity, OpenCode, Command Code |
| **Real-time** | Yes — always-on dashboard |
| **Cost tracking** | Yes |
| **Last update** | 2026-04-04 (very active) |

**How it compares to agent-pulse:**

| Dimension | agentlytics | agent-pulse |
|-----------|------------|-------------|
| Desktop app | ✅ Yes (Electron) | ✅ Yes (Kotlin/Compose) |
| System tray | ❌ No | ✅ Yes |
| Cross-platform | ❌ macOS only | ✅ macOS + Windows + Linux |
| Native performance | ❌ Electron (150MB+ RAM) | ✅ JBR native (~30-50MB) |
| Copilot CLI support | ❌ VS Code plugin only | ✅ CLI + ACP |
| Gemini CLI | ❌ No | ✅ Yes |
| Codex CLI | ❌ No | ✅ Yes |
| Hook-based detection | ❌ File reading only | ✅ Hooks + file reading |
| Enrichment (events.jsonl) | ❌ Basic file scanning | ✅ Deep telemetry extraction |

**Verdict**: Similar concept but different tech stack and agent coverage. Agent-pulse's
hook-based approach and events.jsonl enrichment give it deeper data. Electron vs native
Kotlin is a meaningful performance difference for an always-on app.

---

### 1.2 tokscale — MOST POPULAR CLI 🔴

| | |
|---|---|
| **URL** | https://github.com/junhoyeo/tokscale |
| **Stars** | 1,579 |
| **Language** | Rust |
| **Form** | CLI tool + web leaderboard + 3D graphs |
| **Agents** | 10+: Claude Code, Codex, Gemini, Cursor, OpenCode, AmpCode, Factory Droid, etc. |
| **Real-time** | Yes |
| **Cost tracking** | Yes |
| **Last update** | 2026-04-04 (very active) |

**How it compares to agent-pulse:**

| Dimension | tokscale | agent-pulse |
|-----------|---------|-------------|
| Form factor | CLI (terminal) | Desktop app (system tray) |
| Always-on | ❌ Manual invocation | ✅ System tray, always running |
| UI richness | Terminal-based | ✅ Compose Desktop GUI |
| Global leaderboard | ✅ Unique feature | ❌ Not planned |
| Offline-first | ✅ | ✅ |
| Hook-based | ❌ File reading | ✅ Hooks + enrichment |

**Verdict**: Different form factor (CLI vs desktop). tokscale targets developers who live
in the terminal. agent-pulse targets developers who want a persistent visual dashboard.
The leaderboard/social feature is clever and unique.

---

### 1.3 cockpit-tools — HIGHEST OVERALL STARS 🟡

| | |
|---|---|
| **URL** | https://github.com/jlcodes99/cockpit-tools |
| **Stars** | 3,456 |
| **Language** | Rust |
| **Form** | Native desktop account manager |
| **Agents** | 8: Antigravity, Codex, Copilot, Windsurf, Kiro, Cursor, Gemini, CodeBuddy |
| **Real-time** | Yes — quota monitoring |
| **Last update** | 2026-04-04 (very active) |

**Key difference**: This is a **multi-account manager** focused on API keys, quota
management, and account switching. It's about managing your subscriptions, not monitoring
active coding sessions. Different problem space — less direct competition.

---

### 1.4 Claude-Code-Usage-Monitor — MOST STARRED SINGLE-AGENT 🟡

| | |
|---|---|
| **URL** | https://github.com/Maciek-roboblog/Claude-Code-Usage-Monitor |
| **Stars** | 7,340 |
| **Language** | Python |
| **Form** | Terminal dashboard with predictions |
| **Agents** | Claude Code only |
| **Real-time** | Yes |
| **Cost tracking** | Yes — with predictions & warnings |

**Key difference**: Claude Code ONLY. Massively popular because Claude's usage limits are
a pain point. agent-pulse can learn from its UX for the Claude adapter but isn't directly
competing — we're multi-agent.

---

### 1.5 ccboard — CLOSEST TECHNICAL APPROACH 🟡

| | |
|---|---|
| **URL** | https://github.com/FlorianBruniaux/ccboard |
| **Stars** | 45 |
| **Language** | Rust |
| **Form** | TUI + Web dashboard |
| **Agents** | Claude Code (primary, hooks), Cursor/Codex/OpenCode (read-only import) |
| **Real-time** | Yes — hook-based with 500ms debounce |
| **Cost tracking** | Yes — 4-level alerts, 30-day forecasts, anomaly detection |
| **Last update** | 2026-03-30 |

**How it compares to agent-pulse:**

| Dimension | ccboard | agent-pulse |
|-----------|---------|-------------|
| Hook-based monitoring | ✅ Claude only | ✅ All agents |
| Form factor | TUI + Web | Desktop (system tray) |
| Multi-agent live monitoring | ❌ Claude live, others read-only | ✅ All agents live |
| Cost analytics | ✅ Advanced (forecasts, anomalies) | Planned (Step 11-12) |
| Knowledge base ("Brain") | ✅ Cross-session | ❌ Not planned |
| Test suite | ✅ 492 tests | Planned |

**Verdict**: Technically the most similar approach (hooks + file reading). But Claude-focused
with secondary support for others. agent-pulse's universal hook deployment across all 5
agents is a differentiator.

---

### 1.6 agent-sessions — BEST NATIVE macOS 🟡

| | |
|---|---|
| **URL** | https://github.com/jazzyalex/agent-sessions |
| **Stars** | 435 |
| **Language** | Swift/SwiftUI |
| **Form** | Native macOS desktop app |
| **Agents** | 6: Codex, Claude, Gemini, Copilot CLI, Droid, OpenCode |
| **Real-time** | Yes — "Agent Cockpit" with iTerm2 integration |
| **Last update** | 2026-04-03 (very active) |

**Key insight**: This is what agent-pulse would look like if it were macOS-only and Swift.
Beautiful native app, session browsing, live monitoring. But macOS-only is a hard limitation.
agent-pulse's cross-platform story matters.

---

### 1.7 cli-continues — MOST AGENTS SUPPORTED

| | |
|---|---|
| **URL** | https://github.com/yigitkonur/cli-continues |
| **Stars** | 1,001 |
| **Language** | TypeScript |
| **Form** | CLI tool |
| **Agents** | 14: Claude, Codex, Copilot, Gemini, Cursor, Amp, Cline, Roo, Kilo, Kiro, Crush, OpenCode, Factory Droid, Antigravity |

**Key difference**: This is a **session handoff tool** (migrate sessions between agents),
not a monitoring dashboard. Solves the "I hit Claude's rate limit, continue in Codex"
problem. Different use case, but its 14-agent parser library is impressive and validates
that reading these files works at scale.

---

### 1.8 claude-code-karma — BEST WEB UX

| | |
|---|---|
| **URL** | https://github.com/JayantDevkar/claude-code-karma |
| **Stars** | 150 |
| **Language** | Python (FastAPI) + TypeScript (SvelteKit) |
| **Form** | Local web dashboard |
| **Agents** | Claude Code only |
| **Real-time** | Yes — polling (3s live, 30s historical) |
| **Last update** | 2026-04-02 |

**Notable**: The most polished web UI in this space. Beautiful timeline, Kanban task view,
cache hit rate visualization. Claude-only though.

---

### 1.9 Hook-Based Monitoring Tools

Several tools use the same hook-based approach as agent-pulse, but only for Claude Code:

| Tool | Stars | Agent | Approach |
|------|------:|-------|----------|
| **claude-code-hooks-multi-agent-observability** | 1,334 | Claude | Hook-based monitoring |
| **claude-code-statusline** | 417 | Claude | Hook → tmux/terminal status |
| **claude-code-tamagotchi** | 389 | Claude | Hook → virtual pet metaphor |

All validate the hook-based approach but are Claude-only. agent-pulse's universal hook
deployment is unique.

---

### 1.10 System Tray / Menu Bar Tools

The system-tray niche is underserved:

| Tool | Stars | OS | Agents | Notes |
|------|------:|:---|--------|-------|
| **ClaudeBar** | 882 | macOS | 7 | Menu bar app, most popular tray tool |
| **OpenCode-Bar** | 222 | macOS | 5 | Menu bar for OpenCode family |
| **ai-token-monitor** | 107 | macOS | 1 | Claude token monitor |
| **CodexBarWin** | 9 | Windows | 3 | Rare Windows tray tool |
| **copilot-tracker** | 5 | Cross | 1 | Early stage |

**Key insight**: macOS menu bar tools exist but are agent-specific. No cross-platform
system tray tool monitors all 5 core agents. This is agent-pulse's niche.

---

### 1.11 Session File Parsers (from format-stability research)

These tools directly parse the same files agent-pulse plans to read:

| Tool | Stars | Agents | Purpose |
|------|------:|--------|---------|
| **ai-sessions-mcp** | 25 | 7 | MCP server for session access |
| **Codex_Relay** | 32 | 1 (Codex) | Cross-device session migration |
| **ai-chat-extractor** | 1 | 4 | Export tool (appears abandoned) |

Small community tools, but they validate that reading these files is feasible.

---

## 2. Market Segmentation

The 53+ tools cluster into distinct segments:

```
┌─────────────────────────────────────────────────────────────────┐
│                    AI AGENT MONITORING MARKET                     │
│                                                                   │
│  ┌──────────────────────┐  ┌───────────────────────────────────┐  │
│  │  ACCOUNT MANAGERS    │  │  SESSION BROWSERS                 │  │
│  │  cockpit-tools 3456★ │  │  agent-sessions 435★              │  │
│  │  (quota, keys, plans)│  │  agentsview 658★                  │  │
│  └──────────────────────┘  │  cli-continues 1001★ (handoff)    │  │
│                            └───────────────────────────────────┘  │
│  ┌──────────────────────┐  ┌───────────────────────────────────┐  │
│  │  USAGE MONITORS      │  │  REAL-TIME DASHBOARDS             │  │
│  │  (single-agent)      │  │  (multi-agent)                    │  │
│  │  Claude-Usage 7340★  │  │  agentlytics 403★                │  │
│  │  tokscale 1579★      │  │  splitrail 144★                  │  │
│  │  sniffly 1199★       │  │  ccboard 45★                     │  │
│  │  claude-pulse 303★   │  │                                   │  │
│  └──────────────────────┘  │  ┌─────────────────────────────┐  │  │
│                            │  │ agent-pulse (PLANNED)        │  │  │
│  ┌──────────────────────┐  │  │ • System tray always-on     │  │  │
│  │  MENU BAR / TRAY     │  │  │ • All 5 core agents         │  │  │
│  │  (single-agent mostly)│  │  │ • Hook + file enrichment    │  │  │
│  │  ClaudeBar 882★      │  │  │ • Native Kotlin/Compose     │  │  │
│  │  OpenCode-Bar 222★   │  │  │ • Cross-platform            │  │  │
│  └──────────────────────┘  │  └─────────────────────────────┘  │  │
│                            └───────────────────────────────────┘  │
│  ┌──────────────────────┐  ┌───────────────────────────────────┐  │
│  │  HOOK-BASED TOOLS    │  │  MCP / INTEGRATION TOOLS          │  │
│  │  (Claude only)       │  │  ai-sessions-mcp 25★              │  │
│  │  hooks-observ. 1334★ │  │  claude-code-sessions 11★         │  │
│  │  statusline 417★     │  │                                   │  │
│  │  tamagotchi 389★     │  │                                   │  │
│  └──────────────────────┘  └───────────────────────────────────┘  │
└─────────────────────────────────────────────────────────────────┘
```

**agent-pulse sits at the intersection** of "Real-Time Dashboards" and "Menu Bar / Tray" —
a position no existing tool fully occupies.

---

## 3. Competitive Advantages of agent-pulse

### 3.1 What agent-pulse has that nobody else does

| Advantage | Details | Closest competitor |
|-----------|---------|-------------------|
| **Universal hook deployment** | Hooks for ALL 5 agents, not just Claude | ccboard (Claude hooks only) |
| **events.jsonl deep enrichment** | Per-model tokens, cost, code changes from Copilot CLI | Nobody reads events.jsonl for metrics |
| **Native Kotlin/Compose Desktop** | Not Electron, not web, truly native | agent-sessions (Swift/macOS only) |
| **Cross-platform system tray** | macOS + Windows + Linux, always-on | ClaudeBar (macOS only, Claude only) |
| **Defensive adaptive parsing** | Health checks, versioned adapters, golden files | cli-continues (Zod passthrough) |
| **Read-only safety guarantee** | Explicit read-only contract for all file access | Not documented by others |

### 3.2 What competitors have that agent-pulse doesn't (yet)

| Feature | Who has it | Should we add it? |
|---------|-----------|-------------------|
| Global leaderboard | tokscale | ❌ Different philosophy (privacy-first) |
| Session handoff between agents | cli-continues | ❌ Out of scope |
| Cross-session knowledge base | ccboard ("Brain") | 🤔 Interesting for post-MVP |
| Cost forecasting & anomaly detection | ccboard, Claude-Usage-Monitor | ✅ Natural fit for Step 11-12 |
| 3D token visualizations | tokscale | ❌ Gimmick |
| Export/share sessions | agent-sessions, agentsview | 🤔 Post-MVP |
| Virtual pet metaphor | claude-code-tamagotchi | ❌ Fun but not our style |

### 3.3 Unique technical depth

The combination of **hooks + events.jsonl enrichment** is genuinely unique. Here's what
each approach gives us compared to competitors:

```
COMPETITORS (file reading only):
  Session files → "Agent X has a session in project Y"
  ≈ Basic presence detection

agent-pulse (hooks + events.jsonl + SQLite + process scanning):
  Hooks           → Real-time lifecycle events (started, stopped, status change)
  events.jsonl    → Per-model tokens, cost, code changes, tool calls, subagents
  workspace.yaml  → Project context, branch, repo, timestamps
  session.db      → Current task/plan state
  ProcessHandle   → PID, uptime, parent-child tree
  Cursor SQLite   → AI authorship % per commit (unique metric)
  ≈ Full operational intelligence
```

---

## 4. Risks & Threats

### 4.1 Immediate threats

| Threat | Severity | Mitigation |
|--------|----------|-----------|
| **agentlytics** adds system tray | 🔴 High | Ship faster. Electron tray is inferior to native. |
| **tokscale** adds GUI mode | 🔴 High | Rust GUI ecosystem is immature. Our Compose UI will be richer. |
| **Claude-Usage-Monitor** goes multi-agent | 🟡 Medium | Python + terminal limits their UI potential. |
| **agent-sessions** goes cross-platform | 🟡 Medium | SwiftUI → cross-platform is a massive rewrite. |
| **GitHub/Anthropic ship official monitoring** | 🔴 High | First-party tools always win. Race to establish value. |

### 4.2 The real risk: first-party tools

The biggest threat isn't any open-source competitor — it's **GitHub or Anthropic building
official monitoring into their products**. GitHub could add usage dashboards to Copilot CLI,
Anthropic could add it to Claude Code. If this happens, single-agent monitors die, but
**multi-agent aggregators survive** because developers will still need a unified view.

This reinforces agent-pulse's multi-agent positioning.

---

## 5. Strategic Recommendations

### 5.1 Positioning

**"The Activity Monitor for AI Coding Agents"**

Just as macOS Activity Monitor shows all running processes, agent-pulse shows all running
AI agents. Always on, always visible, zero configuration.

Don't compete on:
- Session browsing (agent-sessions, agentsview are better)
- Session handoff (cli-continues owns this)
- Single-agent analytics depth (Claude-Usage-Monitor, ccboard are deeper)

Compete on:
- **Universal real-time visibility** (no other tool shows all 5 agents in one tray icon)
- **Native performance** (not Electron)
- **Cross-platform** (not macOS-only)
- **Zero-config setup** (auto-detect agents, auto-deploy hooks)

### 5.2 Differentiation priorities

1. **System tray presence with real-time updates** — This is the core UX differentiator.
   Make the tray icon change color based on agent activity. Make the popup show a live view.
2. **Instant setup** — Install → see your agents. No configuration, no web server, no
   terminal commands. Hooks deploy automatically on first run.
3. **Cross-platform from day one** — Most competitors launch macOS-first. If agent-pulse
   works on Windows and Linux from v1.0, that's a significant addressable market.
4. **Deep Copilot CLI integration** — events.jsonl enrichment is unique. No competitor
   extracts per-model token counts from Copilot CLI sessions.

### 5.3 What NOT to build

- ❌ Global leaderboards (privacy concern, different philosophy)
- ❌ Session handoff (cli-continues has this, out of scope)
- ❌ Full session transcript viewer (agent-sessions, agentsview are better)
- ❌ Support for 14+ agents (focus on the 5 that matter)
- ❌ Web dashboard mode (stay native, stay in the tray)

---

## 6. Summary

### The landscape

| Segment | # Tools | Top Tool | Stars |
|---------|--------:|----------|------:|
| Single-agent usage monitors | 15+ | Claude-Code-Usage-Monitor | 7,340 |
| Account/quota managers | 5+ | cockpit-tools | 3,456 |
| Session browsers | 8+ | cli-continues | 1,001 |
| Multi-agent dashboards | 5+ | agentlytics | 403 |
| Hook-based monitors | 5+ | claude-code-hooks-observability | 1,334 |
| System tray / menu bar | 7+ | ClaudeBar | 882 |
| MCP integrations | 3+ | ai-sessions-mcp | 25 |

### agent-pulse's position

Agent-pulse occupies a **unique intersection**: multi-agent real-time dashboard +
system tray always-on + native desktop + hook-based enrichment. No existing tool
combines all four.

The closest competitor is **agentlytics** (Electron desktop, 8 agents, real-time) but
it lacks system tray presence, cross-platform support, hook-based detection, and
events.jsonl deep enrichment.

### Bottom line

The market validates demand (7,340 stars for just a Claude usage monitor). The
fragmentation validates the opportunity (no winner-take-all yet). Agent-pulse's
combination of native performance, universal agent coverage, always-on tray presence,
and deep enrichment via hooks+file reading is genuinely differentiated.

**Ship fast, stay focused, stay native.**
