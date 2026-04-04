package com.agentpulse.model

enum class AgentType(val rawName: String, val displayName: String, val icon: String) {
    CopilotCli("copilot-cli", "Copilot CLI", "🤖"),
    CopilotVsCode("copilot-vscode", "Copilot (VS Code)", "🤖"),
    CopilotIntelliJ("copilot-intellij", "Copilot (IntelliJ)", "🤖"),
    ClaudeCode("claude-code", "Claude Code", "🧠"),
    CursorIde("cursor", "Cursor", "⚡"),
    CodexCli("codex-cli", "Codex CLI", "📦"),
    GeminiCli("gemini-cli", "Gemini CLI", "💎");

    companion object {
        private val byRawName = entries.associateBy { it.rawName }
        fun forName(name: String): AgentType? = byRawName[name]
    }
}
