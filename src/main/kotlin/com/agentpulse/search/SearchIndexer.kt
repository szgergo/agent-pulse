package com.agentpulse.search

import com.agentpulse.model.AgentState

interface SearchIndexer {
    fun indexAgent(agent: AgentState)
    fun removeAgent(agentId: String)
    fun clearAll()
}
