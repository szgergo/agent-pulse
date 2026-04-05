package com.agentpulse.provider

import com.agentpulse.model.AgentType

class CopilotCliProvider : CopilotAgentProvider() {
    override val agentType = AgentType.CopilotCli
}
