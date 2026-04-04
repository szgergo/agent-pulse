package com.agentpulse.model

/**
 * All documented hook event types across all supported agents.
 *
 * The enum [name] is the canonical form. [fromRaw] resolves incoming strings
 * (which may be in any capitalisation, e.g. Copilot `sessionStart` vs Claude
 * `SessionStart`) via a case-insensitive name match — analogous to Java's
 * `Enum.valueOf` but without the case sensitivity restriction.
 */
enum class HookEventType {

    // ── Lifecycle — shared across Copilot CLI, Claude Code, Gemini CLI, Cursor ───────────
    /** Session begins. Agents: Copilot/Cursor (`sessionStart`), Claude/Gemini (`SessionStart`). */
    SessionStart,

    /** Session ends. Agents: Copilot/Cursor (`sessionEnd`), Claude/Gemini (`SessionEnd`). */
    SessionEnd,

    // ── Tool execution — Copilot CLI, Claude Code, Cursor ────────────────────────────────
    /** Before tool invocation. Agents: Copilot/Cursor (`preToolUse`), Claude (`PreToolUse`). */
    PreToolUse,

    /** After tool invocation. Agents: Copilot/Cursor (`postToolUse`), Claude (`PostToolUse`). */
    PostToolUse,

    // ── Agent completion — Copilot CLI, Claude Code, Cursor ──────────────────────────────
    /** Agent response/turn is complete. Agents: Cursor (`stop`), Claude (`Stop`). */
    Stop,

    /** Subagent completed. Agents: Copilot (`subagentStop`), Claude (`SubagentStop`). */
    SubagentStop,

    // ── Context management — Claude Code, Cursor ────────────────────────────────────────
    /** Before context compaction. Agents: Cursor (`preCompact`), Claude (`PreCompact`). */
    PreCompact,

    // ── Copilot CLI specific ──────────────────────────────────────────────────────────────
    /** User submitted a prompt. Raw: `userPromptSubmitted`. */
    UserPromptSubmitted,

    /** Top-level agent stopped. Raw: `agentStop`. */
    AgentStop,

    /** An error occurred during the session. Raw: `errorOccurred`. */
    ErrorOccurred,

    // ── Claude Code specific ─────────────────────────────────────────────────────────────
    /** User submitted a prompt (Claude naming — distinct from Copilot's UserPromptSubmitted). Raw: `UserPromptSubmit`. */
    UserPromptSubmit,

    /** User notification triggered. Raw: `Notification`. */
    Notification,

    // ── Gemini CLI specific ───────────────────────────────────────────────────────────────
    /** Before a tool call. Raw: `BeforeTool`. */
    BeforeTool,

    /** After a tool call. Raw: `AfterTool`. */
    AfterTool,

    /** Before agent execution. Raw: `BeforeAgent`. */
    BeforeAgent,

    /** After agent execution. Raw: `AfterAgent`. */
    AfterAgent,

    /** Before LLM model request. Raw: `BeforeModel`. */
    BeforeModel,

    /** After LLM model response. Raw: `AfterModel`. */
    AfterModel,

    // ── Cursor specific ───────────────────────────────────────────────────────────────────
    /** Before shell command execution (CLI agent only). Raw: `beforeShellExecution`. */
    BeforeShellExecution,

    /** After shell command execution (CLI agent only). Raw: `afterShellExecution`. */
    AfterShellExecution,

    /** After a file was modified. Raw: `afterFileEdit`. */
    AfterFileEdit,

    /** Before prompt is submitted. Raw: `beforeSubmitPrompt`. */
    BeforeSubmitPrompt,

    /** Before an MCP tool call. Raw: `beforeMCPExecution`. */
    BeforeMcpExecution,

    /** After an MCP tool call. Raw: `afterMCPExecution`. */
    AfterMcpExecution,

    // ── Codex CLI specific ────────────────────────────────────────────────────────────────
    /** Turn completion notification (Codex's single hook event). Raw: `notify`. */
    Notify,

    // ── Fallback ─────────────────────────────────────────────────────────────────────────
    /** Unrecognised event type received at runtime. */
    Unknown;

    companion object {
        private val index: Map<String, HookEventType> =
            entries
                .filter { it != Unknown }
                .associateBy { it.name.lowercase() }

        /**
         * Resolve a raw event-type string (from the hook filename or payload) to a
         * [HookEventType] by matching [name] case-insensitively. Returns [Unknown] for
         * unrecognised values.
         */
        fun fromRaw(raw: String): HookEventType = index[raw.lowercase()] ?: Unknown
    }
}
