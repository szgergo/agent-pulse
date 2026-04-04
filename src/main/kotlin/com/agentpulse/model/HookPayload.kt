package com.agentpulse.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * Typed hook event payload. Each agent has its own data class reflecting
 * the JSON schema of its hook events. Parsed in HookEventWatcher (Step 3).
 */
@Serializable
sealed interface HookPayload {
    val cwd: String?

    companion object {
        private val json = Json { ignoreUnknownKeys = true }

        fun fromRawPayload(agent: AgentType, rawJson: String): HookPayload = when (agent) {
            AgentType.CopilotCli, AgentType.CopilotVsCode, AgentType.CopilotIntelliJ ->
                json.decodeFromString<CopilotPayload>(rawJson)
            AgentType.ClaudeCode -> json.decodeFromString<ClaudePayload>(rawJson)
            AgentType.CursorIde -> json.decodeFromString<CursorPayload>(rawJson)
            AgentType.CodexCli -> json.decodeFromString<CodexPayload>(rawJson)
            AgentType.GeminiCli -> json.decodeFromString<GeminiPayload>(rawJson)
        }
    }
}

@Serializable
data class CopilotPayload(
    override val cwd: String? = null,
    val toolName: String? = null,
    val prompt: String? = null,
    val errorMessage: String? = null,
) : HookPayload

@Serializable
data class ClaudePayload(
    override val cwd: String? = null,
    @SerialName("tool_name") val toolName: String? = null,
) : HookPayload

@Serializable
data class CursorPayload(
    override val cwd: String? = null,
    @SerialName("conversation_id") val conversationId: String? = null,
    @SerialName("file_path") val filePath: String? = null,
) : HookPayload

@Serializable
data class CodexPayload(
    override val cwd: String? = null,
    @SerialName("thread_id") val threadId: String? = null,
) : HookPayload

@Serializable
data class GeminiPayload(
    override val cwd: String? = null,
    val toolName: String? = null,
) : HookPayload
