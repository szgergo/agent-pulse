package com.agentpulse

import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Tray
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.WindowPosition
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import com.agentpulse.agent_pulse.generated.resources.Res
import com.agentpulse.agent_pulse.generated.resources.tray_icon
import com.agentpulse.deploy.HookDeployer
import com.agentpulse.provider.AgentStateManager
import com.agentpulse.provider.ClaudeCodeProvider
import com.agentpulse.provider.CodexProvider
import com.agentpulse.provider.CopilotCliProvider
import com.agentpulse.provider.CursorProvider
import com.agentpulse.provider.GeminiProvider
import com.agentpulse.watcher.HookEventWatcher
import java.awt.GraphicsEnvironment
import java.awt.MouseInfo
import kotlinx.coroutines.delay
import org.jetbrains.compose.resources.painterResource
import kotlin.time.Duration.Companion.milliseconds

private val SPINNER = charArrayOf('⠋', '⠙', '⠹', '⠸', '⠼', '⠴', '⠦', '⠧', '⠇', '⠏')

private val CaliforniaNight = Color(0xFF0D1321)
private val CaliforniaCard = Color(0xFF1D2D44)
private val CaliforniaSunset = Color(0xFFFFA94D)
private val CaliforniaSky = Color(0xFF7BDFF2)
private val CaliforniaSand = Color(0xFFF8F1D3)

private val californiaVibesScheme = darkColorScheme(
    primary = CaliforniaSunset,
    secondary = CaliforniaSky,
    background = CaliforniaNight,
    surface = CaliforniaNight,
    surfaceVariant = CaliforniaCard,
    onPrimary = Color(0xFF1C1200),
    onSecondary = Color(0xFF072126),
    onBackground = Color.White,
    onSurface = Color.White,
    onSurfaceVariant = CaliforniaSand,
)

enum class AgentStatus { ONLINE, BUSY, STARTING, OFFLINE }

data class Agent(
    val name: String,
    val status: AgentStatus,
    val task: String,
    val duration: String = "",
    val tokensIn: String = "",
    val tokensOut: String = "",
)

val dummyAgents = listOf(
    Agent("Copilot Agent", AgentStatus.ONLINE, "waiting for assignment", "1h 23m"),
    Agent("Aider Agent", AgentStatus.BUSY, "Implementing dummy auth", "4 min", "102k", "38k"),
    Agent("Claude Code", AgentStatus.STARTING, "Refactor payment module", "12 sec", "1.2k"),
    Agent("Cursor Agent", AgentStatus.OFFLINE, "Fix CSS layout"),
)

fun main() {
    // --- Step 3: Hook infrastructure — plain Kotlin, runs exactly once ---
    val providers = listOf(
        CopilotCliProvider(),
        ClaudeCodeProvider(),
        CursorProvider(),
        CodexProvider(),
        GeminiProvider(),
    )
    val stateManager = AgentStateManager(providers)

    // Deploy hook infrastructure on first run
    HookDeployer().deployIfNeeded()

    // Start event watcher — non-blocking, launches coroutines on its own IO scope.
    // Lives outside application { } so it is never restarted on recomposition and
    // survives window open/close cycles in this system-tray app.
    val watcher = HookEventWatcher(stateManager)
    watcher.start()

    // Clean shutdown: cancel watcher's coroutines when the JVM exits
    Runtime.getRuntime().addShutdownHook(Thread { watcher.stop() })

    // stateManager.agents is available for Step 5 to observe
    application {
        var isVisible by remember { mutableStateOf(false) }
        var spinnerIndex by remember { mutableStateOf(0) }
        val popupWidthPx = 360
        val popupHeightPx = 440

        LaunchedEffect(Unit) {
            while (true) {
                delay(100.milliseconds)
                spinnerIndex = (spinnerIndex + 1) % SPINNER.size
            }
        }

        val spinner = SPINNER[spinnerIndex]

        val windowState = rememberWindowState(
            size = DpSize(popupWidthPx.dp, popupHeightPx.dp),
            position = WindowPosition(Alignment.TopEnd),
        )

        Tray(
            icon = painterResource(Res.drawable.tray_icon),
            tooltip = "agent-pulse",
            onAction = {
                MouseInfo.getPointerInfo()?.location?.let { pointer ->
                    val bounds = GraphicsEnvironment
                        .getLocalGraphicsEnvironment()
                        .defaultScreenDevice
                        .defaultConfiguration
                        .bounds

                    val targetX = (pointer.x - popupWidthPx / 2)
                        .coerceIn(bounds.x, bounds.x + bounds.width - popupWidthPx)
                    val targetY = (pointer.y + 8)
                        .coerceIn(bounds.y, bounds.y + bounds.height - popupHeightPx)

                    windowState.position = WindowPosition(targetX.dp, targetY.dp)
                }
                isVisible = !isVisible
            },
        )


        if (isVisible) {
            Window(
                onCloseRequest = { isVisible = false },
                state = windowState,
                title = "agent-pulse",
                resizable = false,
                undecorated = true,
                alwaysOnTop = true,
                transparent = true,
            ) {
                MaterialTheme(colorScheme = californiaVibesScheme) {
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .clip(RoundedCornerShape(16.dp))
                            .background(MaterialTheme.colorScheme.background)
                    ) {
                        Column(modifier = Modifier.padding(16.dp)) {
                            val quitInteraction = remember { MutableInteractionSource() }
                            val isQuitHovered by quitInteraction.collectIsHoveredAsState()
                            val quitButtonColor by animateColorAsState(
                                targetValue = if (isQuitHovered) CaliforniaSunset.copy(alpha = 0.38f)
                                else CaliforniaCard.copy(alpha = 0.72f),
                                label = "quitButtonHover"
                            )

                            // Header with title and Quit button
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Text(
                                    "🫀 agent-pulse",
                                    style = MaterialTheme.typography.titleMedium,
                                    fontWeight = FontWeight.Bold,
                                    color = Color.White,
                                )
                                Spacer(Modifier.weight(1f))
                                Button(
                                    onClick = { exitApplication() },
                                    shape = RoundedCornerShape(percent = 50),
                                    interactionSource = quitInteraction,
                                    colors = ButtonDefaults.buttonColors(
                                        containerColor = quitButtonColor,
                                        contentColor = Color.White,
                                    ),
                                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp),
                                ) {
                                    Text("Quit", fontSize = 12.sp)
                                }
                            }
                            Text(
                                "${dummyAgents.count { it.status == AgentStatus.ONLINE || it.status == AgentStatus.BUSY }} of ${dummyAgents.size} agents active",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            Spacer(Modifier.height(16.dp))

                            dummyAgents.forEach { agent ->
                                AgentCard(agent, spinner)
                                Spacer(Modifier.height(8.dp))
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun AgentCard(agent: Agent, spinner: Char) {
    val statusColor = when (agent.status) {
        AgentStatus.ONLINE -> Color(0xFF4CAF50)
        AgentStatus.BUSY -> Color(0xFF2196F3)
        AgentStatus.STARTING -> Color(0xFFFFC107)
        AgentStatus.OFFLINE -> Color(0xFF757575)
    }

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
        ),
        shape = RoundedCornerShape(8.dp),
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    modifier = Modifier
                        .size(8.dp)
                        .clip(CircleShape)
                        .background(statusColor)
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    agent.name,
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = Color.White,
                )
                Spacer(Modifier.weight(1f))
                Text(
                    if (agent.status == AgentStatus.BUSY) "$spinner ${agent.status.name.lowercase()}"
                    else agent.status.name.lowercase(),
                    style = MaterialTheme.typography.labelSmall,
                    color = statusColor,
                    fontFamily = FontFamily.Monospace,
                )
            }

            Spacer(Modifier.height(4.dp))
            Text(
                agent.task,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            if (agent.duration.isNotEmpty() || agent.tokensIn.isNotEmpty()) {
                Spacer(Modifier.height(4.dp))
                Row {
                    if (agent.duration.isNotEmpty()) {
                        Text(
                            "⏱ ${agent.duration}",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            fontSize = 10.sp,
                        )
                    }
                    if (agent.tokensIn.isNotEmpty()) {
                        Spacer(Modifier.width(12.dp))
                        Text(
                            "↑${agent.tokensIn}" + if (agent.tokensOut.isNotEmpty()) "  ↓${agent.tokensOut}" else "",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            fontSize = 10.sp,
                        )
                    }
                }
            }
        }
    }
}
