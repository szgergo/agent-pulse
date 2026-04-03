package com.agentpulse.model

/**
 * Lifecycle state of an agent session.
 *
 * - [Running]: Agent is actively processing a task.
 * - [Idle]: Agent is connected and waiting for input (session open but no active tool use).
 * - [Stopped]: Agent session has ended normally (sessionEnd event received).
 * - [Error]: Agent encountered an error during processing.
 */
enum class AgentStatus { Running, Idle, Stopped, Error }
