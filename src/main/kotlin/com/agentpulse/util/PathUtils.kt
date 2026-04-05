package com.agentpulse.util

import java.nio.file.Path

/**
 * Resolve an agent's config directory, respecting env-var overrides.
 *
 * Every agent lets users relocate their config via an env var (e.g. COPILOT_HOME).
 * Hardcoding the default path misses sessions for users who set these vars.
 *
 * @param envVar the environment variable name to check (e.g. "COPILOT_HOME"), or null if N/A.
 * @param defaultPath the fallback relative to user.home (e.g. ".copilot").
 */
fun agentConfigDir(envVar: String?, defaultPath: String): Path =
    envVar?.let(System::getenv)?.let(Path::of)
        ?: Path.of(System.getProperty("user.home"), defaultPath)

/**
 * agent-pulse base directory: `~/.agent-pulse`.
 * All agent-pulse data lives under this tree (hooks, events, cache, etc.).
 */
fun agentPulseBaseDir(): Path =
    Path.of(System.getProperty("user.home"), ".agent-pulse")

/**
 * Hooks directory: `~/.agent-pulse/hooks`.
 * Deployed hook scripts (e.g., report-copilot.sh, report-claude.sh) live here.
 */
fun agentPulseHooksDir(): Path =
    agentPulseBaseDir().resolve("hooks")

/**
 * Events directory: `~/.agent-pulse/events`.
 * Hook events are written here as JSON files by running hook scripts.
 */
fun agentPulseEventsDir(): Path =
    agentPulseBaseDir().resolve("events")
