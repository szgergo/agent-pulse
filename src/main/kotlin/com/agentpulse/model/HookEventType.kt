package com.agentpulse.model

/**
 * All documented hook event types across all supported agents.
 *
 * Each entry carries the known raw string values used by the various agent CLIs/IDEs so
 * that the watcher boundary can map them via [fromRaw] to a typed constant.
 *
 * Casing varies by agent (e.g. Copilot uses `sessionStart`, Claude uses `SessionStart`);
 * [fromRaw] performs a case-insensitive lookup and falls back to [Unknown].
 */
enum class HookEventType(vararg val rawValues: String) {

    // ── Lifecycle — shared across Copilot CLI, Claude Code, Gemini CLI, Cursor ───────────
    /** Session begins. Raw: `sessionStart` (Copilot/Cursor), `SessionStart` (Claude/Gemini). */
    SessionStart("sessionStart", "SessionStart"),

    /** Session ends. Raw: `sessionEnd` (Copilot/Cursor), `SessionEnd` (Claude/Gemini). */
    SessionEnd("sessionEnd", "SessionEnd"),

    // ── Tool execution — Copilot CLI, Claude Code, Cursor ────────────────────────────────
    /** Before tool invocation. Raw: `preToolUse` (Copilot/Cursor), `PreToolUse` (Claude). */
    PreToolUse("preToolUse", "PreToolUse"),

    /** After tool invocation. Raw: `postToolUse` (Copilot/Cursor), `PostToolUse` (Claude). */
    PostToolUse("postToolUse", "PostToolUse"),

    // ── Agent completion — Copilot CLI, Claude Code, Cursor ──────────────────────────────
    /** Agent response/turn is complete. Raw: `stop` (Cursor), `Stop` (Claude). */
    Stop("stop", "Stop"),

    /** Subagent completed. Raw: `subagentStop` (Copilot), `SubagentStop` (Claude). */
    SubagentStop("subagentStop", "SubagentStop"),

    // ── Context management — Claude Code, Cursor ────────────────────────────────────────
    /** Before context compaction. Raw: `preCompact` (Cursor), `PreCompact` (Claude). */
    PreCompact("preCompact", "PreCompact"),

    // ── Copilot CLI specific ──────────────────────────────────────────────────────────────
    /** User submitted a prompt. Raw: `userPromptSubmitted`. */
    UserPromptSubmitted("userPromptSubmitted"),

    /** Top-level agent stopped. Raw: `agentStop`. */
    AgentStop("agentStop"),

    /** An error occurred during the session. Raw: `errorOccurred`. */
    ErrorOccurred("errorOccurred"),

    // ── Claude Code specific ─────────────────────────────────────────────────────────────
    /** User submitted a prompt (Claude naming). Raw: `UserPromptSubmit`. */
    UserPromptSubmit("UserPromptSubmit"),

    /** User notification triggered. Raw: `Notification`. */
    Notification("Notification"),

    // ── Gemini CLI specific ───────────────────────────────────────────────────────────────
    /** Before a tool call. Raw: `BeforeTool`. */
    BeforeTool("BeforeTool"),

    /** After a tool call. Raw: `AfterTool`. */
    AfterTool("AfterTool"),

    /** Before agent execution. Raw: `BeforeAgent`. */
    BeforeAgent("BeforeAgent"),

    /** After agent execution. Raw: `AfterAgent`. */
    AfterAgent("AfterAgent"),

    /** Before LLM model request. Raw: `BeforeModel`. */
    BeforeModel("BeforeModel"),

    /** After LLM model response. Raw: `AfterModel`. */
    AfterModel("AfterModel"),

    // ── Cursor specific ───────────────────────────────────────────────────────────────────
    /** Before shell command execution (CLI agent only). Raw: `beforeShellExecution`. */
    BeforeShellExecution("beforeShellExecution"),

    /** After shell command execution (CLI agent only). Raw: `afterShellExecution`. */
    AfterShellExecution("afterShellExecution"),

    /** After a file was modified. Raw: `afterFileEdit`. */
    AfterFileEdit("afterFileEdit"),

    /** Before prompt is submitted. Raw: `beforeSubmitPrompt`. */
    BeforeSubmitPrompt("beforeSubmitPrompt"),

    /** Before an MCP tool call. Raw: `beforeMCPExecution`. */
    BeforeMcpExecution("beforeMCPExecution"),

    /** After an MCP tool call. Raw: `afterMCPExecution`. */
    AfterMcpExecution("afterMCPExecution"),

    // ── Codex CLI specific ────────────────────────────────────────────────────────────────
    /** Turn completion notification (Codex's single hook event). Raw: `notify`. */
    Notify("notify"),

    // ── Fallback ─────────────────────────────────────────────────────────────────────────
    /** Unrecognised event type received at runtime. */
    Unknown("unknown");

    companion object {
        private val index: Map<String, HookEventType> =
            entries.flatMap { type -> type.rawValues.map { raw -> raw to type } }.toMap()

        /**
         * Resolve a raw event-type string (from the hook filename or payload) to a
         * [HookEventType]. Matching is case-insensitive. Returns [Unknown] for
         * unrecognised values.
         */
        fun fromRaw(raw: String): HookEventType =
            index[raw] ?: entries.firstOrNull { type ->
                type.rawValues.any { it.equals(raw, ignoreCase = true) }
            } ?: Unknown
    }
}
