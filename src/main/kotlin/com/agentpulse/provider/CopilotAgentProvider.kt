package com.agentpulse.provider

import com.agentpulse.model.AgentState
import com.agentpulse.model.AgentStatus
import com.agentpulse.model.CopilotPayload
import com.agentpulse.model.HookEvent
import com.agentpulse.model.HookEventType
import com.agentpulse.util.agentConfigDir
import java.nio.file.InvalidPathException
import java.nio.file.Path
import kotlin.io.path.exists
import kotlin.io.path.isDirectory
import kotlin.io.path.listDirectoryEntries

abstract class CopilotAgentProvider : AgentProvider {

    // Copilot-specific: scan $COPILOT_HOME/session-state/ for inuse.<PID>.lock files.
    // Wrapped in try/catch — filesystem errors (permissions, race conditions) must NOT
    // prevent the event from being processed. Returns null on any error (session ID
    // unknown is acceptable; losing the event is not).
    override fun resolveSessionId(pid: Int): String? = try {
        val sessionStateDir = agentConfigDir("COPILOT_HOME", ".copilot").resolve("session-state")
        if (!sessionStateDir.exists()) null
        else sessionStateDir.listDirectoryEntries().firstOrNull { dir ->
            dir.isDirectory() && dir.listDirectoryEntries("inuse.$pid.lock").isNotEmpty()
        }?.fileName?.toString()
    } catch (e: Exception) {
        System.err.println("[agent-pulse] Failed to resolve Copilot session ID for PID $pid: ${e.message}")
        null
    }

    private fun incrementCounter(extra: Map<String, String>, key: String): String =
        ((extra[key]?.toIntOrNull() ?: 0) + 1).toString()

    private fun safeParseCwd(raw: String?): Path? = try {
        raw?.let { Path.of(it) }
    } catch (_: InvalidPathException) {
        null
    }

    override fun reconcileAgentState(event: HookEvent, currentState: AgentState?): AgentState {
        val p = event.payload as? CopilotPayload ?: return currentState ?: fallbackState(event)
        val sessionId = resolveSessionId(event.pid)
        return when (event.eventType) {
            HookEventType.SessionStart -> {
                if (sessionId == null) {
                    System.err.println("[agent-pulse] ${agentType.name} sessionStart with no session ID for PID ${event.pid} — ignoring")
                    return currentState ?: fallbackState(event)
                }
                AgentState(
                    id = "${agentType.name}_$sessionId",
                    name = "${agentType.displayName} — ${sessionId.take(8)}",
                    agentType = agentType,
                    status = AgentStatus.Running,
                    pid = event.pid,
                    sessionId = sessionId,
                    cwd = safeParseCwd(p.cwd),
                    eventCount = 1,
                    lastActivity = event.timestamp,
                )
            }
            HookEventType.SessionEnd -> currentState?.copy(
                status = AgentStatus.Stopped,
                eventCount = currentState.eventCount + 1,
                lastActivity = event.timestamp,
            ) ?: fallbackState(event).copy(status = AgentStatus.Stopped)
            HookEventType.PostToolUse -> {
                val toolName = p.toolName
                currentState?.copy(
                    eventCount = currentState.eventCount + 1,
                    lastActivity = event.timestamp,
                    extra = currentState.extra + buildMap {
                        toolName?.let { put("lastTool", it) }
                        put("toolCalls", incrementCounter(currentState.extra, "toolCalls"))
                    },
                ) ?: fallbackState(event)
            }
            HookEventType.SubagentStart -> currentState?.copy(
                eventCount = currentState.eventCount + 1,
                lastActivity = event.timestamp,
                extra = currentState.extra + mapOf(
                    "subagents" to incrementCounter(currentState.extra, "subagents")
                ),
            ) ?: fallbackState(event)
            HookEventType.UserPromptSubmitted -> currentState?.copy(
                eventCount = currentState.eventCount + 1,
                lastActivity = event.timestamp,
                extra = currentState.extra + mapOf(
                    "prompts" to incrementCounter(currentState.extra, "prompts")
                ),
            ) ?: fallbackState(event)
            else -> currentState?.copy(
                eventCount = currentState.eventCount + 1,
                lastActivity = event.timestamp,
            ) ?: fallbackState(event)
        }
    }

    private fun fallbackState(event: HookEvent): AgentState {
        val sessionId = resolveSessionId(event.pid)
        return AgentState(
            id = "${agentType.name}_${sessionId ?: event.pid}",
            name = "${agentType.displayName} — ${sessionId?.take(8) ?: "PID ${event.pid}"}",
            agentType = agentType,
            status = AgentStatus.Running,
            pid = event.pid,
            sessionId = sessionId,
            eventCount = 1,
            lastActivity = event.timestamp,
        )
    }
}
