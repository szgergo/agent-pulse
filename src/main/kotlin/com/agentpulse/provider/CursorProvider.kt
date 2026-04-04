package com.agentpulse.provider

import com.agentpulse.model.AgentState
import com.agentpulse.model.AgentType
import com.agentpulse.model.HookEvent

class CursorProvider : AgentProvider {
    override val agentType = AgentType.CursorIde
    override fun reconcileAgentState(event: HookEvent, currentState: AgentState?): AgentState {
        // Stub — real implementation in Step 4
        return currentState?.copy(
            eventCount = currentState.eventCount + 1,
            lastActivity = event.timestamp,
        ) ?: initialState(event)
    }
}
