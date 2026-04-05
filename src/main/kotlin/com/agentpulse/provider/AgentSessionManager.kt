package com.agentpulse.provider

import com.agentpulse.model.AgentState
import com.agentpulse.model.AgentStatus
import com.agentpulse.model.AgentType
import com.agentpulse.model.HookEvent
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Central state holder for all active agent sessions.
 *
 * Receives [HookEvent]s (pushed by FileWatcher), routes them to the appropriate
 * [AgentProvider], and updates the reactive [agents] StateFlow that Compose UI observes.
 *
 * The list represents sessions that are currently active. Sessions in a terminal state
 * ([AgentStatus.Stopped] or [AgentStatus.Error]) are removed immediately on transition.
 */
class AgentSessionManager(
    providers: List<AgentProvider>,
) {
    private val providerMap: Map<AgentType, AgentProvider> =
        providers.associateBy { it.agentType }

    private val _mutableAgentList = MutableStateFlow<List<AgentState>>(emptyList())
    val agents: StateFlow<List<AgentState>> = _mutableAgentList.asStateFlow()

    /**
     * Process an incoming hook event. Called by FileWatcher on each new event file.
     * Routes to the appropriate provider and updates the state flow.
     * Sessions that transition to a terminal state are removed immediately.
     */
    fun onEvent(event: HookEvent) {
        val provider = providerMap[event.agent] ?: return
        val sessions = _mutableAgentList.value
        val existingSession = sessions.find { it.agentType == event.agent && it.pid == event.pid }

        val updatedSession = runCatching {
            provider.reconcileAgentState(event, existingSession)
        }.getOrElse { e ->
            if (e is CancellationException) throw e
            System.err.println("[agent-pulse] ${event.agent.name} provider failed on ${event.eventType}: ${e.message}")
            return  // keep previous state, don't crash
        }

        if (updatedSession.status in TERMINAL_STATUSES) {
            if (existingSession != null) {
                _mutableAgentList.value = sessions.filter { it.id != updatedSession.id }
            }
            return
        }

        _mutableAgentList.value = if (existingSession != null) {
            sessions.map { if (it.id == updatedSession.id) updatedSession else it }
        } else {
            sessions + updatedSession
        }
    }

    companion object {
        private val TERMINAL_STATUSES = setOf(AgentStatus.Stopped, AgentStatus.Error)
    }
}
