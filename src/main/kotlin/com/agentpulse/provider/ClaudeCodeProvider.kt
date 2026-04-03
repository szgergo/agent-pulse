package com.agentpulse.provider

import com.agentpulse.model.AgentState
import com.agentpulse.model.AgentStatus
import com.agentpulse.model.AgentType
import com.agentpulse.model.HookEvent

class ClaudeCodeProvider : AgentProvider {
    override val agentType = AgentType.ClaudeCode
    override fun processEvent(event: HookEvent, currentState: AgentState?): AgentState {
        // Stub — real implementation in Step 4
        return currentState?.copy(
            eventCount = currentState.eventCount + 1,
            lastActivity = event.timestamp * 1000,
        ) ?: AgentState(
            id = "${agentType.name}_${event.pid}",
            name = "${agentType.displayName} — PID ${event.pid}",
            agentType = agentType,
            status = AgentStatus.Running,
            pid = event.pid,
            eventCount = 1,
            lastActivity = event.timestamp * 1000,
        )
    }
}
