package com.agentpulse.deploy

import com.agentpulse.util.agentConfigDir
import com.agentpulse.util.agentPulseHooksDir
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import kotlin.io.path.createDirectories
import kotlin.io.path.exists
import kotlin.io.path.outputStream
import kotlin.io.path.writeText

// Implements HookDeployer interface — registered in the deployers list in Main.kt.
// Uses agentConfigDir() and agentPulseHooksDir() helpers from util/PathUtils.kt.
// See: https://docs.github.com/en/copilot/reference/copilot-cli-reference/cli-config-dir-reference
//
// Threading: all deployers are launched in parallel on Dispatchers.IO from main().
// All filesystem I/O runs on the IO dispatcher — no additional withContext needed.

class CopilotHookDeployer : HookDeployer {
    override fun deployAgentHook() {
        // Step 1: deploy report-copilot.sh from classpath resource
        val hooksDir = agentPulseHooksDir()
        hooksDir.createDirectories()
        val scriptDest = hooksDir.resolve("report-copilot.sh")
        val inputStream = javaClass.getResourceAsStream("/hooks/report-copilot.sh")
        checkNotNull(inputStream) { "Bundled resource /hooks/report-copilot.sh not found — packaging error" }
        inputStream.use { input ->
            scriptDest.outputStream().use { output -> input.copyTo(output) }
        }
        scriptDest.toFile().setExecutable(true, false)

        // Step 2: write agent-pulse.json to Copilot hooks directory
        val copilotHomeDir = agentConfigDir("COPILOT_HOME", ".copilot")
        if (!copilotHomeDir.exists()) {
            println("[agent-pulse] Copilot home not found at $copilotHomeDir — skipping hook deployment")
            return
        }
        // Copilot merges all *.json files from its hooks dir, so agent-pulse.json coexists
        // safely alongside any user-defined hook configs. We only write our own file.
        val copilotHooksDir = copilotHomeDir.resolve("hooks")
        copilotHooksDir.createDirectories()  // safe: only creates 'hooks' subdir; parent already confirmed

        val hookConfig = copilotHooksDir.resolve("agent-pulse.json")
        val events = listOf("sessionStart", "sessionEnd", "postToolUse", "userPromptSubmitted", "subagentStart")
        val configJson = buildJsonObject {
            put("version", 1)
            putJsonObject("hooks") {
                for (event in events) {
                    putJsonArray(event) {
                        addJsonObject {
                            put("type", "command")
                            put("bash", "\$HOME/.agent-pulse/hooks/report-copilot.sh $event")
                        }
                    }
                }
            }
        }
        hookConfig.writeText(Json { prettyPrint = true }.encodeToString<JsonElement>(configJson))
    }
}
