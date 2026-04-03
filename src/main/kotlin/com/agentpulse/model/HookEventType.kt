package com.agentpulse.model

/**
 * All documented hook event types across all supported agents.
 *
 * Each entry stores one canonical raw string (the documented event name). Matching in
 * [fromRaw] is always case-insensitive, so agents that vary only in capitalisation
 * (e.g. Copilot `sessionStart` vs Claude `SessionStart`) resolve to the same constant
 * without needing duplicate entries.
 */
enum class HookEventType(val rawValue: String) {

    // ── Lifecycle — shared across Copilot CLI, Claude Code, Gemini CLI, Cursor ───────────
    /** Session begins. Agents: Copilot/Cursor (`sessionStart`), Claude/Gemini (`SessionStart`). */
    SessionStart("sessionStart"),

    /** Session ends. Agents: Copilot/Cursor (`sessionEnd`), Claude/Gemini (`SessionEnd`). */
    SessionEnd("sessionEnd"),

    // ── Tool execution — Copilot CLI, Claude Code, Cursor ────────────────────────────────
    /** Before tool invocation. Agents: Copilot/Cursor (`preToolUse`), Claude (`PreToolUse`). */
    PreToolUse("preToolUse"),

    /** After tool invocation. Agents: Copilot/Cursor (`postToolUse`), Claude (`PostToolUse`). */
    PostToolUse("postToolUse"),

    // ── Agent completion — Copilot CLI, Claude Code, Cursor ──────────────────────────────
    /** Agent response/turn is complete. Agents: Cursor (`stop`), Claude (`Stop`). */
    Stop("stop"),

    /** Subagent completed. Agents: Copilot (`subagentStop`), Claude (`SubagentStop`). */
    SubagentStop("subagentStop"),

    // ── Context management — Claude Code, Cursor ────────────────────────────────────────
    /** Before context compaction. Agents: Cursor (`preCompact`), Claude (`PreCompact`). */
    PreCompact("preCompact"),

    // ── Copilot CLI specific ──────────────────────────────────────────────────────────────
    /** User submitted a prompt. Raw: `userPromptSubmitted`. */
    UserPromptSubmitted("userPromptSubmitted"),

    /** Top-level agent stopped. Raw: `agentStop`. */
    AgentStop("agentStop"),

    /** An error occurred during the session. Raw: `errorOccurred`. */
    ErrorOccurred("errorOccurred"),

    // ── Claude Code specific ─────────────────────────────────────────────────────────────
    /** User submitted a prompt (Claude naming — distinct from Copilot's UserPromptSubmitted). Raw: `UserPromptSubmit`. */
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
            entries
                .filter { it != Unknown }
                .associate { type -> type.rawValue.lowercase() to type }

        /**
         * Resolve a raw event-type string (from the hook filename or payload) to a
         * [HookEventType]. Matching is case-insensitive via [String.lowercase]. Returns
         * [Unknown] for unrecognised values.
         */
        fun fromRaw(raw: String): HookEventType = index[raw.lowercase()] ?: Unknown
    }
}
