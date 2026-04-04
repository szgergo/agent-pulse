package com.agentpulse.watcher

import com.agentpulse.model.AgentStatus
import com.agentpulse.model.AgentType
import com.agentpulse.model.ClaudePayload
import com.agentpulse.model.CodexPayload
import com.agentpulse.model.CopilotPayload
import com.agentpulse.model.CursorPayload
import com.agentpulse.model.GeminiPayload
import com.agentpulse.model.HookEvent
import com.agentpulse.model.HookEventType
import com.agentpulse.model.HookPayload
import com.agentpulse.provider.AgentStateManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.runInterruptible
import java.nio.file.FileSystems
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardWatchEventKinds
import java.time.Instant
import kotlin.io.path.deleteIfExists
import kotlin.io.path.exists
import kotlin.io.path.fileSize
import kotlin.io.path.listDirectoryEntries
import kotlin.io.path.name
import kotlin.io.path.nameWithoutExtension
import kotlin.io.path.readText

class HookEventWatcher(
    private val stateManager: AgentStateManager,
    private val eventsDir: Path = Path.of(System.getProperty("user.home"), ".agent-pulse", "events"),
) {
    private companion object {
        const val MAX_HOOK_EVENT_SIZE_BYTES = 64 * 1024
        const val PID_VALIDATION_INTERVAL_MS = 5_000L
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private fun Path.isProcessableEvent(): Boolean =
        name.endsWith(".json") && !name.startsWith(".tmp.")

    fun start() {
        Files.createDirectories(eventsDir)
        startWatchingWithRecovery()
        startPidValidation()
    }

    private fun startWatchingWithRecovery() {
        scope.launch {
            // 1. Register WatchService FIRST — starts queuing events immediately.
            //    This must happen before processExistingFiles so no events are missed
            //    in the gap between the directory listing and the watch registration.
            val watchService = FileSystems.getDefault().newWatchService()
            eventsDir.register(
                watchService,
                arrayOf(StandardWatchEventKinds.ENTRY_CREATE),
                com.sun.nio.file.SensitivityWatchEventModifier.HIGH, // 100ms FSEvents latency on JBR
            )
            println("[agent-pulse] Watching: $eventsDir")

            // 2. Process existing files (recovery after restart).
            //    WatchService is already registered, so new events arriving during this
            //    scan are queued in the OS-level buffer (LinkedBlockingDeque on JBR)
            //    and will be picked up by the take() loop below.
            processExistingFiles()

            // 3. Watch loop — processes new events going forward
            while (isActive) {
                // take() blocks natively on JBR's LinkedBlockingDeque — zero CPU when idle
                val key = runInterruptible(Dispatchers.IO) {
                    watchService.take()
                }
                for (event in key.pollEvents()) {
                    if (event.kind() == StandardWatchEventKinds.OVERFLOW) {
                        // Buffer overflow — re-scan directory to catch anything missed
                        processExistingFiles()
                        continue
                    }
                    val filename = event.context() as? Path ?: continue
                    val file = eventsDir.resolve(filename)
                    if (file.isProcessableEvent()) {
                        processFile(file)
                    }
                }
                key.reset()
            }
        }
    }

    private fun processFile(file: Path) {
        // Called from IO scope — file reads are safe.
        // IMPORTANT: file MUST be deleted in the finally block regardless of success/failure.
        // Without this, a malformed or poison file would remain on disk and be re-processed
        // on every restart, causing an infinite error loop.
        if (!file.exists()) return
        try {
            val allParts = file.nameWithoutExtension.split("-")
            if (allParts.size < 4) return

            // Guard: hook event files are ~225 bytes. Skip anything suspiciously large
            // to avoid OOM if a rogue process writes to our events dir.
            val fileSize = file.fileSize()
            if (fileSize > MAX_HOOK_EVENT_SIZE_BYTES) {
                System.err.println("[agent-pulse] Skipping oversized event file (${fileSize} bytes): ${file.name}")
                return
            }

            val timestampStr = allParts.first()
            val pidStr = allParts.last()
            val eventType = allParts[allParts.size - 2]
            val agentName = allParts.subList(1, allParts.size - 2).joinToString("-")
            val agent = AgentType.forName(agentName) ?: return
            val pid = pidStr.toIntOrNull() ?: return
            val epochSeconds = timestampStr.toLongOrNull() ?: return

            val content = file.readText()
            val payload = HookPayload.fromRawPayload(agent, content)

            val hookEvent = HookEvent(
                agent = agent,
                eventType = HookEventType.fromRaw(eventType),
                pid = pid,
                timestamp = Instant.ofEpochSecond(epochSeconds),
                payload = payload,
            )

            stateManager.onEvent(hookEvent)
            println("[agent-pulse] Event: $agentName/$eventType (PID $pid)")
        } catch (e: Exception) {
            System.err.println("[agent-pulse] Failed to process ${file.name}: ${e.message}")
        } finally {
            file.deleteIfExists()
        }
    }

    private fun processExistingFiles() {
        // On startup, there may be many queued events accumulated while agent-pulse was offline.
        // We only care about the latest state per (agent, pid) — older events are stale.
        // Group by (agent, pid), keep only the last file per group, delete the rest.
        val processable = eventsDir.listDirectoryEntries()
            .filter { it.isProcessableEvent() }
            .sortedBy { it.name }  // Chronological — timestamp is first in filename

        val grouped = processable
            .filter { file -> file.nameWithoutExtension.split("-").size >= 4 }
            .groupBy { file ->
                val allParts = file.nameWithoutExtension.split("-")
                val agentName = allParts.subList(1, allParts.size - 2).joinToString("-")
                val pid = allParts.last()
                "$agentName-$pid"  // e.g. "copilot-cli-12345"
            }

        for ((_, files) in grouped) {
            val stale = files.dropLast(1)
            stale.forEach { staleFile ->
                try {
                    staleFile.deleteIfExists()
                } catch (e: Exception) {
                    System.err.println("[agent-pulse] Failed to delete stale event ${staleFile.name}: ${e.message}")
                }
            }
            if (stale.isNotEmpty()) {
                println("[agent-pulse] Startup: skipped ${stale.size} stale events for group")
            }
            files.lastOrNull()?.let { processFile(it) }
        }
    }

    private fun startPidValidation() {
        scope.launch {
            println("[agent-pulse] Starting PID validation: reaping stale agent entries every ${PID_VALIDATION_INTERVAL_MS / 1000}s")
            while (isActive) {
                delay(PID_VALIDATION_INTERVAL_MS)
                stateManager.agents.value
                    .filter { it.status == AgentStatus.Running }
                    .forEach { agent ->
                        val alive = ProcessHandle.of(agent.pid.toLong()).isPresent
                        if (!alive) {
                            val syntheticEvent = HookEvent(
                                agent = agent.agentType,
                                eventType = HookEventType.SessionEnd,
                                pid = agent.pid,
                                timestamp = Instant.now(),
                                payload = when (agent.agentType) {
                                    AgentType.CopilotCli, AgentType.CopilotVsCode, AgentType.CopilotIntelliJ ->
                                        CopilotPayload()
                                    AgentType.ClaudeCode -> ClaudePayload()
                                    AgentType.CursorIde -> CursorPayload()
                                    AgentType.CodexCli -> CodexPayload()
                                    AgentType.GeminiCli -> GeminiPayload()
                                },
                            )
                            stateManager.onEvent(syntheticEvent)
                            println("[agent-pulse] PID ${agent.pid} dead → synthetic sessionEnd")
                        }
                    }
            }
        }
    }

    fun stop() {
        scope.cancel()
    }
}
