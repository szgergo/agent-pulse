package com.agentpulse.provider

import com.agentpulse.model.AgentState
import com.agentpulse.model.AgentType
import com.agentpulse.model.HookEvent

class CopilotIntelliJProvider : AgentProvider {
    override val agentType = AgentType.CopilotIntelliJ
    override fun processEvent(event: HookEvent, currentState: AgentState?): AgentState {
        // Stub — real implementation in Step 4
        return currentState?.copy(
            eventCount = currentState.eventCount + 1,
            lastActivity = event.timestamp * 1000,
        ) ?: initialState(event)
    }
}
