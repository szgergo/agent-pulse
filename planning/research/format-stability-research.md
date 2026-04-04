# Research: Mitigating Undocumented Format Risk

**Question**: The session files we plan to read (events.jsonl, workspace.yaml, SQLite DBs,
JSONL transcripts) are not public APIs. They can change without notice. What can we do to
make agent-pulse resilient to these changes?

**Date**: 2026-04-04  
**Method**: Direct schema evolution analysis on live data (47 Copilot sessions across 6 CLI
versions), community tool analysis, Kotlin/JVM pattern research

---

## Executive Summary

The risk is **real but manageable**. We have direct evidence that Copilot CLI's events.jsonl
schema has already evolved across versions (0.0.420 → 1.0.4 → 1.0.6 → 1.0.14 → 1.0.18),
and Cursor's SQLite has undergone at least 25 schema migrations. However, all observed
changes follow an **additive-only pattern** — new fields appear, but existing ones are never
removed or renamed. Combined with the right defensive parsing strategy, we can build a system
that survives format changes gracefully.

**Recommended approach**: Tolerant Reader pattern + Versioned Adapters + Health Checks +
Golden File Tests. Estimated effort: ~1 day of additional work, spread across the enrichment
layer implementation (Steps 9-12).

---

## 1. Evidence: How These Formats Actually Evolve

### 1.1 Copilot CLI events.jsonl — Observed Schema Evolution

We have 47 real sessions spanning CLI versions 0.0.420 through 1.0.18 on this machine.
Direct comparison reveals:

**Envelope (top-level structure) — STABLE across all versions:**

```
v=0.0.420:   {id, parentId, timestamp, type, data}
v=1.0.4:     {id, parentId, timestamp, type, data}
v=1.0.6:     {id, parentId, timestamp, type, data}
v=1.0.14:    {id, parentId, timestamp, type, data}
v=1.0.18:    {id, parentId, timestamp, type, data}
```

Zero changes across 6+ versions. This is the safest part to depend on.

**session.start data — Additive evolution:**

```
v=0.0.420:   [context, copilotVersion, producer, sessionId, startTime, version]
v=1.0.4:     [context, copilotVersion, producer, sessionId, startTime, version] + alreadyInUse
v=1.0.6:     same as 1.0.4
v=1.0.14:    same as 1.0.4 + remoteSteerable
v=1.0.18:    same as 1.0.14 (some sessions add selectedModel)
```

Fields only get **added**, never removed or renamed.

**session.shutdown data — Additive evolution:**

```
v=1.0.4:     [codeChanges, currentModel, modelMetrics, sessionStartTime, shutdownType,
              totalApiDurationMs, totalPremiumRequests]                        — 7 keys

v=1.0.6:     same as 1.0.4 + conversationTokens, currentTokens,
              systemTokens, toolDefinitionsTokens                             — 11 keys

v=1.0.14:    same as 1.0.6                                                   — 11 keys
```

**modelMetrics inner structure — STABLE across all versions:**

```
All versions: {requests: {cost, count}, usage: {cacheReadTokens, cacheWriteTokens,
               inputTokens, outputTokens}}
```

Zero changes in the most valuable data structure.

**codeChanges inner structure — STABLE across all versions:**

```
All versions: {filesModified, linesAdded, linesRemoved}
```

### 1.2 Copilot CLI — Known Breaking Changes

From the Copilot CLI changelog and GitHub issues:

| Version | Change | Impact |
|---------|--------|--------|
| 1.0.6 | Fixed "Session file is corrupted" for pre-1.0.6 sessions | Format incompatibility between pre/post 1.0.6 |
| 1.0.15-16 | Multi-device session breakage (Issue #2513) | Session resume failures |
| All | events.jsonl unbounded growth to 20MB+ (Issue #2490) | Performance issue, not schema change |
| All | Raw Unicode U+2028 in JSONL (Issue #2490) | Line separator breaks naive line-by-line parsing |

**Source**: https://github.com/github/copilot-cli/blob/main/changelog.md,
https://github.com/github/copilot-cli/issues/2490

### 1.3 Copilot CLI — Version Detection

The `copilotVersion` field in `session.start` events provides version detection:

```json
{"type":"session.start","data":{"copilotVersion":"1.0.14","version":"1.0.14",...}}
```

However, some sessions report `copilotVersion: "unknown"` — this appears to be from
ACP/IDE-spawned sessions (not terminal). We must handle this gracefully.

### 1.4 Cursor — Schema Migration Evidence

```sql
PRAGMA schema_version;  -- Returns: 25
PRAGMA user_version;    -- Returns: 0
PRAGMA application_id;  -- Returns: 0
```

**25 schema migrations** have occurred in Cursor's `ai-code-tracking.db`. The `user_version`
and `application_id` pragmas are unused — there's no explicit version marker for consumers.
The schema itself is the only version indicator.

### 1.5 Claude Code — Format Evolution

The `ai-sessions-mcp` adapter (https://github.com/yoavf/ai-sessions-mcp) reveals Claude Code
has had at least two message formats:

```go
// Old format (flat):
{"type": "message", "role": "user", "content": "text"}

// New format (nested):
{"type": "message", "message": {"role": "user", "content": "text"}}
```

Both must be handled simultaneously. Additionally, Claude Code auto-deletes sessions after
~30 days (confirmed by claude-code-karma README).

### 1.6 Gemini CLI — Minimal Evolution Data

Session format uses JSON (not JSONL). Community adapters use `interface{}` for the Content
field, suggesting the structure is known to be unstable. No version field exists.

### 1.7 Codex CLI — Best Versioning (Ironically)

Codex Relay's bundle format includes explicit `schema_version` and SHA256 checksums:

```json
{"schema_version": 1, "codex": {"cli_version": "0.104.0"}}
```

This is the only agent with proper format versioning.

---

## 2. Risk Assessment

### 2.1 What Could Go Wrong

| Scenario | Likelihood | Impact | Detection |
|----------|-----------|--------|-----------|
| New fields added to events.jsonl | **Very High** (already happened 3x) | **None** if we ignore unknowns | Automatic |
| Existing field renamed | **Very Low** (never observed) | **High** — parser breaks | Health check |
| Existing field removed | **Low** (never observed) | **Medium** — null where expected | Health check |
| Entire file format changed (e.g., JSONL → SQLite) | **Very Low** | **Critical** — parser useless | File type check |
| Field type changed (string → number) | **Low** | **Medium** — parse error | Type-safe parsing |
| New event types added | **High** (25 types exist, more coming) | **None** if we skip unknowns | Automatic |
| Event type removed | **Very Low** | **Low** — less data | Graceful degradation |
| Cursor SQLite schema migration | **Certain** (25 already) | **Medium** — query breaks | SQL error handling |
| Unicode/encoding issues in JSONL | **Medium** (Issue #2490) | **Medium** — line parse fails | Per-line try/catch |

### 2.2 Stability Tiers

| Data Source | Stability | Confidence | Notes |
|------------|-----------|-----------|-------|
| events.jsonl **envelope** (`id, type, timestamp, data`) | ★★★★★ | Very High | Unchanged across all versions |
| events.jsonl **modelMetrics** | ★★★★☆ | High | Unchanged since first appearance |
| events.jsonl **codeChanges** | ★★★★☆ | High | Unchanged since first appearance |
| events.jsonl **session.start/shutdown keys** | ★★★☆☆ | Medium | Additive changes every few versions |
| workspace.yaml core fields | ★★★★☆ | High | Simple YAML, core fields stable |
| Cursor SQLite schema | ★★☆☆☆ | Low | 25 migrations and counting |
| Claude Code JSONL format | ★★☆☆☆ | Low | Known format migration already |
| Gemini chat JSON | ★★☆☆☆ | Low | Undocumented, adapter uses `Any` |

---

## 3. Mitigation Strategies

### 3.1 Strategy 1: Tolerant Reader Pattern (MUST HAVE)

**Principle** (Martin Fowler): Be strict with output, lenient with input. Only require fields
that are absolutely necessary. Provide defaults for everything else.

**Kotlin implementation with Jackson:**

```kotlin
// Configure ObjectMapper to be maximally forgiving
val defensiveMapper: ObjectMapper = jacksonObjectMapper().apply {
    disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
    disable(DeserializationFeature.FAIL_ON_NULL_FOR_PRIMITIVES)
    enable(DeserializationFeature.ACCEPT_SINGLE_VALUE_AS_ARRAY)
    enable(DeserializationFeature.ACCEPT_EMPTY_STRING_AS_NULL_OBJECT)
}

// Data class with minimal required fields, everything else nullable
@JsonIgnoreProperties(ignoreUnknown = true)
data class CopilotEvent(
    val type: String,                    // REQUIRED — can't do anything without it
    val timestamp: String? = null,       // Nice to have
    val data: JsonNode? = null,          // Raw JSON — parse lazily per event type
)

// Per-event-type extraction uses safe accessors
fun JsonNode.safeString(field: String): String? = this.get(field)?.asText()
fun JsonNode.safeLong(field: String): Long? = this.get(field)?.asLong()
fun JsonNode.safeInt(field: String): Int? = this.get(field)?.asInt()
```

**Why this works**: Unknown fields are silently ignored. Missing optional fields return null.
Only `type` is required. The `data` field is kept as raw `JsonNode` and parsed lazily — so
even if the data structure changes, the envelope parsing never breaks.

**Effort**: ~30 minutes. Built into every data class from day one.

### 3.2 Strategy 2: Versioned Adapter Pattern (SHOULD HAVE)

**One adapter per agent, version-aware parsing inside each adapter:**

```kotlin
sealed interface SessionDataAdapter {
    val agentName: String
    fun isAvailable(): Boolean
    fun detectVersion(): String?
    fun healthCheck(): HealthCheckResult
    fun readSessionMetrics(sessionDir: Path): SessionMetrics?
}

class CopilotAdapter : SessionDataAdapter {
    override val agentName = "copilot-cli"

    override fun detectVersion(): String? {
        // Read first line of events.jsonl, extract copilotVersion
        // Returns null if file doesn't exist or isn't parseable
    }

    override fun readSessionMetrics(sessionDir: Path): SessionMetrics? {
        val version = detectVersion()
        return when {
            version == null -> readWithLatestParser(sessionDir)
            version < "1.0.6" -> readPreV106(sessionDir)
            else -> readWithLatestParser(sessionDir)
        }
    }

    private fun readWithLatestParser(sessionDir: Path): SessionMetrics? {
        // Tolerant reader — extract what we can, skip what we can't
    }
}

class CursorAdapter : SessionDataAdapter {
    override fun readSessionMetrics(sessionDir: Path): SessionMetrics? {
        // Query SQLite with defensive SQL (SELECT ... IF EXISTS patterns)
        // If table/column doesn't exist, degrade gracefully
    }
}
```

**Why this works**: Each agent's quirks are isolated. Version detection happens once per
session. If a format changes, only one adapter needs updating — others continue working.

**Effort**: ~2-3 hours. Part of the enrichment layer design (Steps 9-10).

### 3.3 Strategy 3: Health Check / Canary Pattern (SHOULD HAVE)

**Check format assumptions before committing to full parsing:**

```kotlin
data class HealthCheckResult(
    val healthy: Boolean,
    val missingRequiredFields: List<String>,
    val unknownFields: Set<String>,      // New fields = possible new version
    val warnings: List<String>,
)

class FormatHealthChecker(
    private val requiredFields: List<String>,
    private val knownOptionalFields: Set<String>,
) {
    fun check(data: Map<String, Any?>): HealthCheckResult {
        val present = data.keys
        val missing = requiredFields.filter { it !in present }
        val unknown = present - requiredFields.toSet() - knownOptionalFields

        val warnings = mutableListOf<String>()
        if (unknown.isNotEmpty()) {
            warnings.add("Unknown fields detected (possible format update): $unknown")
        }

        return HealthCheckResult(
            healthy = missing.isEmpty(),
            missingRequiredFields = missing,
            unknownFields = unknown,
            warnings = warnings,
        )
    }
}
```

**On startup**, run health check against one sample file per agent:
- If **healthy** (required fields present): proceed normally
- If **unhealthy** (required fields missing): log warning, disable that adapter, surface
  a notification in the UI: "⚠️ Copilot CLI updated — enriched metrics temporarily unavailable"
- If **unknown fields detected**: log info (helps us know when to update)

**Effort**: ~1 hour. Run once on startup, then periodically.

### 3.4 Strategy 4: Golden File Contract Tests (SHOULD HAVE)

**Commit real sample files from each agent version and test against them:**

```
src/test/resources/golden/
├── copilot/
│   ├── v0.0.420/
│   │   ├── events.jsonl        (first 50 lines from a real session)
│   │   └── workspace.yaml
│   ├── v1.0.4/
│   │   ├── events.jsonl
│   │   └── workspace.yaml
│   ├── v1.0.6/
│   │   └── events.jsonl
│   └── v1.0.14/
│       └── events.jsonl
├── cursor/
│   └── ai-code-tracking-schema-v25.sql
└── claude/
    ├── flat-format.jsonl
    └── nested-format.jsonl
```

```kotlin
class CopilotAdapterContractTest {
    @ParameterizedTest
    @ValueSource(strings = ["v0.0.420", "v1.0.4", "v1.0.6", "v1.0.14"])
    fun `adapter parses all known versions`(version: String) {
        val events = loadGoldenFile("copilot/$version/events.jsonl")
        val adapter = CopilotAdapter()
        val result = adapter.parseEvents(events)

        // Core fields must always parse
        assertNotNull(result)
        assertTrue(result.events.isNotEmpty())
        assertNotNull(result.events.first().type)
    }

    @Test
    fun `new version backwards compatible with latest parser`() {
        // When a new CLI version appears, add its golden file
        // This test ensures the existing parser doesn't break
    }
}
```

**Why this works**: When Copilot CLI 1.1.0 ships and changes something, we add its golden
file and see exactly what broke. CI catches regressions before users do.

**Effort**: ~1 hour to set up. ~10 minutes per new version to add a golden file.

### 3.5 Strategy 5: Defensive SQLite Access (MUST HAVE for Cursor)

Cursor's 25 schema migrations mean SQL queries can break at any time:

```kotlin
class CursorDbReader(private val dbPath: Path) {

    // Discover available tables and columns dynamically
    private fun discoverSchema(): Map<String, List<String>> {
        return useConnection { conn ->
            val tables = mutableMapOf<String, List<String>>()
            val rs = conn.metaData.getTables(null, null, "%", arrayOf("TABLE"))
            while (rs.next()) {
                val table = rs.getString("TABLE_NAME")
                val cols = mutableListOf<String>()
                val colRs = conn.metaData.getColumns(null, null, table, "%")
                while (colRs.next()) {
                    cols.add(colRs.getString("COLUMN_NAME"))
                }
                tables[table] = cols
            }
            tables
        }
    }

    fun readConversations(): List<Conversation>? {
        val schema = discoverSchema()

        // Check if expected table exists
        val columns = schema["conversation_summaries"] ?: return null

        // Build query based on available columns
        val selectCols = listOf("conversationId", "title", "model", "mode", "updatedAt")
            .filter { it in columns }

        if ("conversationId" !in columns) return null  // Can't do anything useful

        val sql = "SELECT ${selectCols.joinToString()} FROM conversation_summaries ORDER BY updatedAt DESC"
        // ... execute and parse
    }
}
```

**Why this works**: Instead of hardcoding SQL queries that break on schema changes, we
discover what's available and adapt. If a column disappears, we return less data instead
of crashing.

**Effort**: ~1 hour. One-time investment in the Cursor adapter.

### 3.6 Strategy 6: Incremental JSONL Parsing with Error Isolation (MUST HAVE)

events.jsonl can be large (20MB+) and may contain malformed lines (Unicode issues):

```kotlin
class IncrementalJsonlReader(
    private val mapper: ObjectMapper,
) {
    /**
     * Parse JSONL file line-by-line with per-line error isolation.
     * A malformed line never prevents parsing of subsequent lines.
     */
    fun readEvents(
        path: Path,
        fromOffset: Long = 0,
    ): Sequence<Pair<CopilotEvent, Long>> = sequence {
        Files.newBufferedReader(path, Charsets.UTF_8).use { reader ->
            if (fromOffset > 0) {
                // Seek to offset for incremental reading
                reader.skip(fromOffset)
            }
            var offset = fromOffset
            var line = reader.readLine()
            while (line != null) {
                val lineBytes = line.toByteArray(Charsets.UTF_8).size.toLong() + 1
                try {
                    val event = mapper.readValue(line, CopilotEvent::class.java)
                    yield(event to offset)
                } catch (e: Exception) {
                    // Log and skip — one bad line doesn't kill the stream
                    logger.debug("Skipping malformed line at offset $offset: ${e.message}")
                }
                offset += lineBytes
                line = reader.readLine()
            }
        }
    }
}
```

**Offset tracking** enables incremental reads — we remember where we left off and only
parse new events. Combined with WatchService, this gives near-real-time updates.

**Effort**: ~30 minutes. Core infrastructure for all JSONL readers.

### 3.7 Strategy 7: UI Transparency (NICE TO HAVE)

Surface format health to the user so they know what's working:

```
┌─────────────────────────────────────────┐
│ 🟢 Copilot CLI (v1.0.18) — Full metrics │
│ 🟢 Cursor — AI% tracking active         │
│ 🟡 Claude Code — Basic monitoring only   │
│    (format update detected, metrics      │
│     limited until agent-pulse update)    │
│ ⚪ Gemini CLI — Not installed            │
│ ⚪ Codex CLI — Not installed             │
└─────────────────────────────────────────┘
```

**Effort**: ~30 minutes in the UI layer (Step 7-8).

---

## 4. Community Precedent: Who Else Parses These Files?

We are not alone. Several production tools already read these undocumented formats:

| Project | Stars | Agents Parsed | Approach | URL |
|---------|------:|---------------|----------|-----|
| **ai-sessions-mcp** | 25 | Copilot, Claude, Codex, Gemini | Go adapter pattern, `interface{}` for flexible fields | https://github.com/yoavf/ai-sessions-mcp |
| **agent-sessions** | 435 | Claude, Cursor, Codex | Swift, native macOS app | https://github.com/jazzyalex/agent-sessions |
| **ccboard** | 45 | Claude, Cursor, Codex | Rust TUI, 492 tests, SQLite cache | https://github.com/FlorianBruniaux/ccboard |
| **claude-code-karma** | 150 | Claude | Python/Svelte dashboard | https://github.com/JayantDevkar/claude-code-karma |
| **cli-continues** | — | Copilot CLI | TypeScript, Zod schemas with `.passthrough()` | https://github.com/yigitkonur/cli-continues |
| **Codex_Relay** | 32 | Codex | Tauri app, explicit schema versioning | https://github.com/Red-noblue/Codex_Relay |

**Common patterns across all of them:**
1. Ignore unknown fields (every single one does this)
2. Per-agent adapter/parser modules
3. Graceful skip when tool not installed
4. No version negotiation — just try parsing and handle errors

**Key insight**: The community treats these files as **de facto stable** despite being
undocumented. No project has reported a catastrophic format break. The risk is real but
the blast radius has historically been small (a missing field, not a total format overhaul).

---

## 5. Recommended Implementation for agent-pulse

### 5.1 Architecture Overview

```
┌──────────────────────────────────────────────────────────┐
│                   ENRICHMENT LAYER                        │
│                                                           │
│  ┌─────────────────────────────────────────────────────┐  │
│  │           SessionDataAdapter (sealed interface)      │  │
│  │  + agentName: String                                 │  │
│  │  + isAvailable(): Boolean                            │  │
│  │  + healthCheck(): HealthCheckResult                  │  │
│  │  + readMetrics(sessionDir): SessionMetrics?          │  │
│  └────────────┬──────────┬──────────┬──────────┬───────┘  │
│               │          │          │          │           │
│  ┌────────────▼┐ ┌───────▼───┐ ┌───▼─────┐ ┌─▼────────┐  │
│  │  Copilot    │ │  Cursor   │ │  Claude  │ │  Gemini  │  │
│  │  Adapter    │ │  Adapter  │ │  Adapter │ │  Adapter │  │
│  │             │ │           │ │          │ │          │  │
│  │ Jackson +   │ │ Dynamic   │ │ Dual-    │ │ Jackson  │  │
│  │ JsonNode    │ │ SQL + JDBC│ │ format   │ │ lenient  │  │
│  │ lazy parse  │ │ schema    │ │ support  │ │ parse    │  │
│  │ offset      │ │ discovery │ │ (flat +  │ │          │  │
│  │ tracking    │ │           │ │ nested)  │ │          │  │
│  └─────────────┘ └───────────┘ └──────────┘ └──────────┘  │
│                                                           │
│  ┌─────────────────────────────────────────────────────┐  │
│  │         FormatHealthChecker (startup + periodic)     │  │
│  │  - Required field validation per adapter             │  │
│  │  - Unknown field detection (new version warning)     │  │
│  │  - Adapter enable/disable based on health            │  │
│  └─────────────────────────────────────────────────────┘  │
│                                                           │
│  ┌─────────────────────────────────────────────────────┐  │
│  │      Golden File Tests (CI contract verification)    │  │
│  │  - One golden file per agent per known version       │  │
│  │  - Backwards compatibility tests                     │  │
│  │  - Regression detection on parser changes            │  │
│  └─────────────────────────────────────────────────────┘  │
└──────────────────────────────────────────────────────────┘
```

### 5.2 Prioritized Action Items

| # | Strategy | Priority | Effort | When |
|---|----------|----------|--------|------|
| 1 | Tolerant Reader (Jackson `ignoreUnknown`, nullable fields) | **MUST** | 30 min | Day 1 of each adapter |
| 2 | Incremental JSONL with per-line error isolation | **MUST** | 30 min | Day 1 of Copilot adapter |
| 3 | Defensive SQLite (schema discovery, dynamic SQL) | **MUST** | 1 hr | Day 1 of Cursor adapter |
| 4 | Versioned Adapter interface | **SHOULD** | 2 hr | Start of enrichment layer |
| 5 | Health Check on startup | **SHOULD** | 1 hr | After first adapter works |
| 6 | Golden File contract tests | **SHOULD** | 1 hr setup + 10 min/version | Alongside adapters |
| 7 | UI transparency (adapter status display) | **NICE** | 30 min | UI polish phase |

**Total additional effort: ~6-8 hours, distributed across Steps 9-12.**

### 5.3 What We Explicitly Do NOT Need

- ❌ **JSON Schema validation library** — overkill for our use case. Health checks are simpler
  and sufficient.
- ❌ **Schema registry** — we're consumers, not producers. No need for a registry.
- ❌ **Automatic format migration** — we read-only. If the format changes, we adapt our parser.
- ❌ **Encryption/signing of data** — these are local files we read. Integrity is the agent's
  responsibility.

---

## 6. Worst Case: What If Everything Breaks?

Even if all enrichment adapters fail simultaneously:

1. **Hook-based monitoring still works** — the core HookEventWatcher + report.sh pipeline
   is completely independent of session file formats. Agent lifecycle events (start, stop,
   status changes) continue flowing.

2. **Process monitoring still works** — ProcessHandle-based detection is zero-dependency.

3. **Enrichment is additive** — it adds token counts, cost metrics, and code change data
   ON TOP of the basic agent state. Without it, agent-pulse degrades to "who's running and
   what are they doing" instead of "who's running, what are they doing, and how much is it
   costing." Still very useful.

This is the key architectural insight: **enrichment failure should never prevent core
monitoring from functioning.**

```kotlin
// The merge point — enrichment is always optional
fun buildAgentState(
    hookEvent: HookEvent,                     // Always available
    processInfo: ProcessInfo?,                // Best-effort
    sessionMetrics: SessionMetrics?,          // Best-effort (from adapters)
): AgentState {
    return AgentState(
        agent = hookEvent.agent,
        pid = hookEvent.pid,
        status = hookEvent.eventType.toStatus(),
        // Enrichment — null is fine, UI shows "—" or hides the field
        model = sessionMetrics?.model,
        tokensUsed = sessionMetrics?.totalTokens,
        cost = sessionMetrics?.cost,
        codeChanges = sessionMetrics?.codeChanges,
    )
}
```

---

## 7. Summary & Recommendation

### The Risk

These are undocumented file formats that WILL change. We have proof: Copilot CLI has evolved
its events.jsonl across at least 4 versions in the span of 0.0.420 → 1.0.18. Cursor has
undergone 25 SQLite schema migrations. Claude Code has changed its message format at least
once.

### The Mitigation

The Tolerant Reader pattern combined with per-adapter isolation gives us:

- **Survival**: Unknown fields are ignored, missing optional fields default to null
- **Detection**: Health checks catch breaking changes on startup, before users see errors
- **Verification**: Golden file tests ensure we don't regress when updating parsers
- **Isolation**: One adapter breaking doesn't affect others
- **Degradation**: Enrichment failure doesn't prevent core monitoring

### The Precedent

At least 6 production tools already parse these same files. All use the same approach:
ignore unknowns, try-catch per record, skip gracefully. None have reported catastrophic
failures from format changes.

### The Cost

~6-8 hours of additional implementation effort, naturally integrated into the enrichment
layer work (Steps 9-12). This is not a separate workstream — it's how we BUILD the
enrichment layer from the start.

### Bottom Line

**Don't avoid reading these files because they're undocumented. Instead, read them
defensively.** The data they contain (per-model token counts, costs, AI authorship
percentages) is too valuable to leave on the table. The risk is manageable with standard
engineering practices that we should be applying anyway.
