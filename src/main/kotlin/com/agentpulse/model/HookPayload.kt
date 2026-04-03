package com.agentpulse.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Typed hook event payload. Each agent has its own data class reflecting
 * the JSON schema of its hook events. Parsed in HookEventWatcher (Step 3).
 */
@Serializable
sealed interface HookPayload {
    val cwd: String?
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
