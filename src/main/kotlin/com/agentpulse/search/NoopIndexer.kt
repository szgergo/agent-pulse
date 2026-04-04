package com.agentpulse.search

import com.agentpulse.model.AgentState

class NoopIndexer : SearchIndexer {
    override fun indexAgent(agent: AgentState) {}
    override fun removeAgent(agentId: String) {}
    override fun clearAll() {}
}
