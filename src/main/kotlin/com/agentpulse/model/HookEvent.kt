package com.agentpulse.model

import java.time.Instant

data class HookEvent(
    val agent: AgentType,
    val eventType: HookEventType,    // Typed event — resolved from hook filename or payload
    val pid: Int,                    // Agent PID (from $PPID in hook script)
    val timestamp: Instant,          // Point in time when the event occurred (from filename)
    val payload: HookPayload,        // Typed payload — parsed at watcher boundary
)
