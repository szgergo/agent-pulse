package com.agentpulse.deploy

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.exists
import kotlin.io.path.readText
import kotlin.io.path.writeText

class HookDeployer(
    private val baseDir: Path = Path.of(System.getProperty("user.home"), ".agent-pulse"),
) {
    private val configFile = baseDir.resolve("config.json")
    private val hooksDir = baseDir.resolve("hooks")
    private val eventsDir = baseDir.resolve("events")
    private val json = Json { prettyPrint = true; ignoreUnknownKeys = true }

    fun deployIfNeeded() {
        if (isDeployed()) return
        deploy()
    }

    private fun isDeployed(): Boolean {
        if (!configFile.exists()) return false
        return try {
            val config = json.decodeFromString<JsonObject>(configFile.readText())
            config["hooksDeployed"]?.let {
                (it as? JsonPrimitive)?.booleanOrNull == true
            } ?: false
        } catch (e: Exception) {
            System.err.println("[agent-pulse] Failed to read config file, assuming not deployed: ${e.message}")
            false
        }
    }

    private fun deploy() {
        Files.createDirectories(hooksDir)
        Files.createDirectories(eventsDir)

        // Load report.sh from the bundled classpath resource (src/main/resources/hooks/report.sh).
        // Using getResourceAsStream avoids Kotlin string escaping and keeps the script
        // readable in git with proper syntax highlighting.
        val reportShContent = HookDeployer::class.java.getResourceAsStream("/hooks/report.sh")
            ?.bufferedReader()?.readText()
            ?: throw IllegalStateException("Bundled resource /hooks/report.sh not found — packaging error")

        val reportSh = hooksDir.resolve("report.sh")
        reportSh.writeText(reportShContent)
        reportSh.toFile().setExecutable(true)

        // Mark as deployed
        val config = if (configFile.exists()) {
            try { json.decodeFromString<JsonObject>(configFile.readText()) }
            catch (e: Exception) {
                System.err.println("[agent-pulse] Config file is malformed, resetting: ${e.message}")
                JsonObject(emptyMap())
            }
        } else {
            JsonObject(emptyMap())
        }

        val updated = JsonObject(config.toMutableMap().apply {
            put("hooksDeployed", JsonPrimitive(true))
        })
        configFile.writeText(json.encodeToString(JsonObject.serializer(), updated))

        println("[agent-pulse] Hook infrastructure deployed")
    }
}
