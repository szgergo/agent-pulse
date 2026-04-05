package com.agentpulse.search

import com.agentpulse.model.AgentState

class NoopIndexer : SearchIndexer {
    override fun indexAgent(agent: AgentState) { /* no-op: search not yet implemented */ }
    override fun removeAgent(agentId: String) { /* no-op: search not yet implemented */ }
    override fun clearAll() { /* no-op: search not yet implemented */ }
}
