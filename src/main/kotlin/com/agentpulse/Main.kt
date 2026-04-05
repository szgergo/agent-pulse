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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
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
import com.agentpulse.deploy.AgentPulseHookDeployer
import com.agentpulse.deploy.HookDeployer
import com.agentpulse.provider.AgentStateManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import com.agentpulse.provider.ClaudeCodeProvider
import com.agentpulse.provider.CodexProvider
import com.agentpulse.provider.CopilotCliProvider
import com.agentpulse.provider.CursorProvider
import com.agentpulse.provider.GeminiProvider
import com.agentpulse.watcher.HookEventWatcher
import java.awt.Desktop
import java.awt.GraphicsEnvironment
import java.awt.MouseInfo
import java.awt.event.WindowEvent
import java.awt.event.WindowFocusListener
import org.jetbrains.compose.resources.painterResource

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

    // Deploy hook infrastructure — each deployer runs in parallel on IO to avoid
    // blocking the main thread before the tray icon renders.
    val deployers: List<HookDeployer> = listOf(
        AgentPulseHookDeployer(),
        // Step 4: CopilotHookDeployer(),
    )
    val startupScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    deployers.forEach { deployer ->
        startupScope.launch { deployer.deployAgentHook() }
    }

    // Start event watcher — non-blocking, launches coroutines on its own IO scope.
    // Lives outside application { } so it is never restarted on recomposition and
    // survives window open/close cycles in this system-tray app.
    val watcher = HookEventWatcher(stateManager)
    watcher.start()

    // Clean shutdown: cancel startup + watcher coroutines when the JVM exits
    Runtime.getRuntime().addShutdownHook(Thread {
        watcher.stop()
        startupScope.cancel()
    })

    // stateManager.agents is available for Step 5 to observe
    application {
        var isVisible by remember { mutableStateOf(false) }
        val popupWidthPx = 360
        val popupHeightPx = 440

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
                DisposableEffect(window) {
                    val listener = object : WindowFocusListener {
                        override fun windowGainedFocus(e: WindowEvent) {
                            // No-op: only lost focus should dismiss the popup.
                        }
                        override fun windowLostFocus(e: WindowEvent) {
                            isVisible = false
                        }
                    }
                    window.addWindowFocusListener(listener)
                    acquireWindowFocus(window)
                    onDispose { window.removeWindowFocusListener(listener) }
                }
                PopupContent(onQuit = { exitApplication() })
            }
        }
    }
}

// On macOS, a tray-opened window does not automatically become the active application —
// so we must steal focus explicitly before WindowFocusListener can fire windowLostFocus.
// This is a macOS-only workaround; on other platforms it is a no-op.
private fun acquireWindowFocus(window: java.awt.Window) {
    if (!System.getProperty("os.name", "").contains("Mac", ignoreCase = true)) return
    window.toFront()
    runCatching {
        if (Desktop.isDesktopSupported()) {
            val desktop = Desktop.getDesktop()
            if (desktop.isSupported(Desktop.Action.APP_REQUEST_FOREGROUND)) {
                desktop.requestForeground(true)
            }
        }
    }.onFailure { error ->
        System.err.println("Failed to request app foreground for popup window: ${error.message}")
    }
    window.requestFocus()
}

@Composable
private fun PopupContent(onQuit: () -> Unit) {
    val quitInteraction = remember { MutableInteractionSource() }
    val isQuitHovered by quitInteraction.collectIsHoveredAsState()
    val quitButtonColor by animateColorAsState(
        targetValue = if (isQuitHovered) CaliforniaSunset.copy(alpha = 0.38f)
        else CaliforniaCard.copy(alpha = 0.72f),
        label = "quitButtonHover"
    )
    MaterialTheme(colorScheme = californiaVibesScheme) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .clip(RoundedCornerShape(16.dp))
                .background(MaterialTheme.colorScheme.background)
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
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
                        onClick = onQuit,
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
            }
        }
    }
}
