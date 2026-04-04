package com.agentpulse.model

import java.nio.file.Path
import java.time.Instant
import kotlin.time.Duration

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
    val lastActivity: Instant? = null, // Timestamp of the most recent hook event
    val uptime: Duration? = null,      // How long the session has been active
    val tokenUsage: Long? = null,      // Post-MVP enrichment
    val extra: Map<String, String> = emptyMap(),
)
