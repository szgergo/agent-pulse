package com.agentpulse.model

enum class AgentType(val displayName: String, val icon: String) {
    CopilotCli("Copilot CLI", "🤖"),
    CopilotVsCode("Copilot (VS Code)", "🤖"),
    CopilotIntelliJ("Copilot (IntelliJ)", "🤖"),
    ClaudeCode("Claude Code", "🧠"),
    CursorIde("Cursor", "⚡"),
    CodexCli("Codex CLI", "📦"),
    GeminiCli("Gemini CLI", "💎"),
}
