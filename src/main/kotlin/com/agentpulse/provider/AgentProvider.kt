package com.agentpulse.provider

import com.agentpulse.model.AgentState
import com.agentpulse.model.AgentStatus
import com.agentpulse.model.AgentType
import com.agentpulse.model.HookEvent

/**
 * Translates agent-specific hook events into universal [AgentState].
 *
 * Each implementation knows how to interpret its agent's JSON payload —
 * extracting session IDs, status, working directory, etc.
 *
 * Providers are pure functions: given an event and optional current state,
 * they return an updated state. State management is handled by [AgentStateManager].
 */
interface AgentProvider {
    val agentType: AgentType

    /**
     * Process a hook event and return the updated agent state.
     *
     * @param event The hook event to process.
     * @param currentState The current state for this session, or null if this is the first event.
     * @return Updated [AgentState] reflecting the new event.
     */
    fun processEvent(event: HookEvent, currentState: AgentState?): AgentState

    /** Default ID for a new session: "{typeName}_{pid}". */
    fun defaultId(event: HookEvent): String = "${agentType.name}_${event.pid}"

    /** Default display name for a new session. */
    fun defaultName(event: HookEvent): String = "${agentType.displayName} — PID ${event.pid}"

    /** Create a minimal initial [AgentState] for a new session. */
    fun initialState(event: HookEvent): AgentState = AgentState(
        id = defaultId(event),
        name = defaultName(event),
        agentType = agentType,
        status = AgentStatus.Running,
        pid = event.pid,
        eventCount = 1,
        lastActivity = event.timestamp * 1000,
    )
}
