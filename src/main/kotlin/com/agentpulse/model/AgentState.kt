package com.agentpulse.model

import java.nio.file.Path

data class AgentState(
    val id: String,                    // "{type}_{sessionId}" or "{type}_{pid}"
    val name: String,                  // "Copilot CLI — session a1b2c3d4"
    val agentType: AgentType,
    val status: AgentStatus,
    val pid: Int,
    val sessionId: String? = null,     // Agent-specific session identifier
    val cwd: Path? = null,             // Working directory (if available from hook payload)
    val model: String? = null,         // Post-MVP enrichment
    val eventCount: Int = 0,           // Number of hook events received for this session
    val lastActivity: Long? = null,    // Epoch millis of most recent hook event
    val uptimeSecs: Long? = null,
    val tokenUsage: Long? = null,      // Post-MVP enrichment
    val extra: Map<String, String> = emptyMap(),
)
