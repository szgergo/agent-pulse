package com.agentpulse.model

data class HookEvent(
    val agent: AgentType,
    val eventType: String,       // "sessionStart", "postToolUse", "sessionEnd", etc.
    val pid: Int,                // Agent PID (from $PPID in hook script)
    val timestamp: Long,         // Epoch seconds (from filename)
    val payload: HookPayload,    // Typed payload — parsed at watcher boundary
)
