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
 * they return a reconciled state. State management is handled by [AgentSessionManager].
 */
interface AgentProvider {
    val agentType: AgentType

    /**
     * Reconcile an incoming hook event with the existing session state.
     *
     * If [currentState] is null (first event for this session), produce a fresh
     * [AgentState] from the event. Otherwise, merge the event into [currentState]
     * and return the updated snapshot.
     *
     * @param event The incoming hook event.
     * @param currentState The existing state for this session, or null for a new session.
     * @return Reconciled [AgentState] reflecting the new event.
     */
    fun reconcileAgentState(event: HookEvent, currentState: AgentState?): AgentState

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
        lastActivity = event.timestamp,
    )
}
