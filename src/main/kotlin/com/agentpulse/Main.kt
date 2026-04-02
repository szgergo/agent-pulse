package com.agentpulse

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.*

fun main() = application {
    val windowState = rememberWindowState(
        size = DpSize(420.dp, 600.dp),
        position = WindowPosition(Alignment.Center)
    )

    var isVisible by remember { mutableStateOf(true) }

    // System tray
    Tray(
        icon = painterResource("tray-icon.png"),
        tooltip = "agent-pulse",
        onAction = { isVisible = !isVisible },
        menu = {
            Item("Show/Hide", onClick = { isVisible = !isVisible })
            Separator()
            Item("Quit agent-pulse", onClick = {
                exitApplication()
            })
        }
    )

    // Main window — close hides, does not quit
    Window(
        onCloseRequest = { isVisible = false },
        visible = isVisible,
        state = windowState,
        title = "agent-pulse",
        resizable = true,
    ) {
        MaterialTheme(colorScheme = darkColorScheme()) {
            Surface(
                modifier = Modifier.fillMaxSize(),
                color = MaterialTheme.colorScheme.background
            ) {
                Column(
                    modifier = Modifier.padding(20.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Text("🫀 agent-pulse", style = MaterialTheme.typography.headlineMedium)
                    Spacer(Modifier.height(8.dp))
                    Text("Universal AI Agent Dashboard", style = MaterialTheme.typography.bodyLarge)
                    Spacer(Modifier.height(16.dp))
                    Text(
                        "No agents detected yet.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
    }
}

