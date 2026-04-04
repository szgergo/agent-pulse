package com.agentpulse.provider

import com.agentpulse.model.AgentState
import com.agentpulse.model.AgentType
import com.agentpulse.model.HookEvent
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Central state holder for all agent sessions.
 *
 * Receives [HookEvent]s (pushed by FileWatcher), routes them to the appropriate
 * [AgentProvider], and updates the reactive [agents] StateFlow that Compose UI observes.
 */
class AgentStateManager(
    providers: List<AgentProvider>,
) {
    private val providerMap: Map<AgentType, AgentProvider> =
        providers.associateBy { it.agentType }

    private val _mutableAgentList = MutableStateFlow<List<AgentState>>(emptyList())
    val agents: StateFlow<List<AgentState>> = _mutableAgentList.asStateFlow()

    /**
     * Process an incoming hook event. Called by FileWatcher on each new event file.
     * Routes to the appropriate provider, updates the state flow.
     */
    fun onEvent(event: HookEvent) {
        val provider = providerMap[event.agent] ?: return
        val currentAgentStates = _mutableAgentList.value
        val existingState = currentAgentStates.find { it.agentType == event.agent && it.pid == event.pid }
        val newState = provider.reconcileAgentState(event, existingState)

        _mutableAgentList.value = if (existingState != null) {
            currentAgentStates.map { if (it.id == newState.id) newState else it }
        } else {
            currentAgentStates + newState
        }
    }
}
