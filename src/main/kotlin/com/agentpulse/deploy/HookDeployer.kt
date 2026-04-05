package com.agentpulse.deploy

/**
 * Deploys agent-specific hook infrastructure (scripts, configs) so that
 * AI coding agents report events back to agent-pulse.
 *
 * Each implementation handles one agent (or the shared base infrastructure).
 * All deployers are launched in parallel on Dispatchers.IO from main().
 */
interface HookDeployer {
    fun deployAgentHook()
}
