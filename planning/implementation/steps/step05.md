# Step 5: ui — System tray dashboard UI

> **⚠️ READ `shared-context.md` FIRST** — it contains all design principles, architecture,
> SQLite safety rules, connection hygiene, tech stack, and project structure that apply to this step.
> Path: `planning/implementation/shared-context.md`


**Goal**: Polished Compose Material 3 dashboard showing agents with status and settings.

**Pre-check**: Step 4 PR is merged. `./gradlew run` detects Copilot agents.

**App state AFTER this step**: Dark-themed dashboard with agent cards. Each card shows: status dot, agent icon, name, model, PID, uptime, token bar. Updates reactively via push events from hooks — no periodic scanning. Empty state when no agents. Settings panel for provider toggles and hotkey info.

**Files to create**:
- `src/main/kotlin/com/agentpulse/ui/Theme.kt` — dark color scheme
- `src/main/kotlin/com/agentpulse/ui/AgentCard.kt` — per-agent card composable
- `src/main/kotlin/com/agentpulse/ui/Dashboard.kt` — agent list + header + empty state
- `src/main/kotlin/com/agentpulse/ui/Settings.kt` — provider toggles, hotkey display
- `src/main/kotlin/com/agentpulse/ui/App.kt` — root composable with navigation

- [ ] **5.1 Create Theme.kt**
    - Custom dark color scheme matching a developer tool aesthetic
    - Colors: dark grays (background), accent blues/greens (status), muted text

  ```kotlin
  package com.agentpulse.ui

  import androidx.compose.material3.MaterialTheme
  import androidx.compose.material3.darkColorScheme
  import androidx.compose.runtime.Composable
  import androidx.compose.ui.graphics.Color

  val AgentPulseColors = darkColorScheme(
      primary = Color(0xFF64B5F6),        // Blue accent
      secondary = Color(0xFF81C784),       // Green accent
      background = Color(0xFF1A1A2E),      // Dark blue-gray
      surface = Color(0xFF16213E),         // Slightly lighter
      surfaceVariant = Color(0xFF1F2B47),  // Card background
      onPrimary = Color.White,
      onSecondary = Color.White,
      onBackground = Color(0xFFE0E0E0),
      onSurface = Color(0xFFE0E0E0),
      onSurfaceVariant = Color(0xFFA0A0B0), // Muted text
      error = Color(0xFFEF5350),
  )

  object StatusColors {
      val running = Color(0xFF4CAF50)
      val idle = Color(0xFFFFC107)
      val stopped = Color(0xFF9E9E9E)
      val error = Color(0xFFEF5350)
  }

  @Composable
  fun AgentPulseTheme(content: @Composable () -> Unit) {
      MaterialTheme(
          colorScheme = AgentPulseColors,
          content = content,
      )
  }
  ```

- [ ] **5.2 Create AgentCard.kt**
    - Status dot: green (Running), yellow (Idle), red (Stopped/Error)
    - Agent type icon from `AgentType.icon`
    - First line: icon + name (bold, truncate)
    - Second line: PID · session ID (first 8 chars) · uptime (formatted: "12m", "2h 5m")
    - Model line (if available)
    - CWD line (truncated)
    - Token bar: LinearProgressIndicator with label

  ```kotlin
  package com.agentpulse.ui

  import androidx.compose.foundation.background
  import androidx.compose.foundation.layout.Box
  import androidx.compose.foundation.layout.Column
  import androidx.compose.foundation.layout.Row
  import androidx.compose.foundation.layout.Spacer
  import androidx.compose.foundation.layout.fillMaxWidth
  import androidx.compose.foundation.layout.height
  import androidx.compose.foundation.layout.padding
  import androidx.compose.foundation.layout.size
  import androidx.compose.foundation.layout.width
  import androidx.compose.foundation.shape.CircleShape
  import androidx.compose.material3.Card
  import androidx.compose.material3.CardDefaults
  import androidx.compose.material3.LinearProgressIndicator
  import androidx.compose.material3.MaterialTheme
  import androidx.compose.material3.Text
  import androidx.compose.runtime.Composable
  import androidx.compose.ui.Alignment
  import androidx.compose.ui.Modifier
  import androidx.compose.ui.draw.clip
  import androidx.compose.ui.text.font.FontWeight
  import androidx.compose.ui.text.style.TextOverflow
  import androidx.compose.ui.unit.dp
  import com.agentpulse.model.AgentState
  import com.agentpulse.model.AgentStatus

  @Composable
  fun AgentCard(agent: AgentState) {
      Card(
          modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp),
          colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
      ) {
          Column(modifier = Modifier.padding(12.dp)) {
              // Header row: status dot + icon + name
              Row(verticalAlignment = Alignment.CenterVertically) {
                  Box(
                      modifier = Modifier.size(10.dp).clip(CircleShape).background(
                          when (agent.status) {
                              AgentStatus.Running -> StatusColors.running
                              AgentStatus.Idle -> StatusColors.idle
                              AgentStatus.Stopped -> StatusColors.stopped
                              AgentStatus.Error -> StatusColors.error
                          }
                      )
                  )
                  Spacer(Modifier.width(8.dp))
                  Text(agent.agentType.icon, style = MaterialTheme.typography.titleMedium)
                  Spacer(Modifier.width(6.dp))
                  Text(
                      agent.summary ?: agent.agentType.displayName,
                      style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold),
                      maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f),
                  )
              }

              Spacer(Modifier.height(4.dp))

              // Metadata line: PID · session · uptime
              val uptimeStr = formatUptime(agent.startTime)
              Text(
                  "PID ${agent.pid} · ${agent.sessionId.take(8)} · $uptimeStr",
                  style = MaterialTheme.typography.bodySmall,
                  color = MaterialTheme.colorScheme.onSurfaceVariant,
              )

              // Model (if available)
              agent.model?.let { model ->
                  Text("Model: $model", style = MaterialTheme.typography.bodySmall,
                      color = MaterialTheme.colorScheme.onSurfaceVariant)
              }

              // CWD (truncated)
              agent.cwd?.let { cwd ->
                  Text(cwd.replace(System.getProperty("user.home"), "~"),
                      style = MaterialTheme.typography.bodySmall,
                      color = MaterialTheme.colorScheme.onSurfaceVariant,
                      maxLines = 1, overflow = TextOverflow.Ellipsis)
              }

              // Token bar (if available)
              agent.tokenUsage?.let { tokens ->
                  Spacer(Modifier.height(4.dp))
                  val maxTokens = 200_000L // Rough context window estimate
                  LinearProgressIndicator(
                      progress = { (tokens.toFloat() / maxTokens).coerceIn(0f, 1f) },
                      modifier = Modifier.fillMaxWidth().height(4.dp),
                  )
                  Text("${tokens / 1000}K tokens", style = MaterialTheme.typography.labelSmall,
                      color = MaterialTheme.colorScheme.onSurfaceVariant)
              }

          }
      }
  }

  private fun formatUptime(startTimeMs: Long): String {
      val elapsed = System.currentTimeMillis() - startTimeMs
      val minutes = elapsed / 60_000
      val hours = minutes / 60
      return when {
          hours > 0 -> "${hours}h ${minutes % 60}m"
          minutes > 0 -> "${minutes}m"
          else -> "<1m"
      }
  }
  ```

- [ ] **5.3 Create Dashboard.kt**
    - Takes `StateFlow<List<AgentState>>` as parameter, collect as state
    - Header: "🫀 agent-pulse"
    - Body: LazyColumn of AgentCards, or empty state
    - Footer: "N agents detected" + settings button

  ```kotlin
  package com.agentpulse.ui

  import androidx.compose.foundation.layout.Box
  import androidx.compose.foundation.layout.Column
  import androidx.compose.foundation.layout.Row
  import androidx.compose.foundation.layout.fillMaxSize
  import androidx.compose.foundation.layout.fillMaxWidth
  import androidx.compose.foundation.layout.padding
  import androidx.compose.foundation.lazy.LazyColumn
  import androidx.compose.foundation.lazy.items
  import androidx.compose.material3.MaterialTheme
  import androidx.compose.material3.Text
  import androidx.compose.material3.TextButton
  import androidx.compose.runtime.Composable
  import androidx.compose.runtime.collectAsState
  import androidx.compose.runtime.getValue
  import androidx.compose.ui.Alignment
  import androidx.compose.ui.Modifier
  import androidx.compose.ui.unit.dp
  import com.agentpulse.model.AgentState
  import kotlinx.coroutines.flow.StateFlow

  @Composable
  fun Dashboard(
      agentsFlow: StateFlow<List<AgentState>>,
      onOpenSettings: () -> Unit,
  ) {
      val agents by agentsFlow.collectAsState()

      Column(modifier = Modifier.fillMaxSize()) {
          // Header
          Row(
              modifier = Modifier.fillMaxWidth().padding(12.dp),
              verticalAlignment = Alignment.CenterVertically,
          ) {
              Text("🫀 agent-pulse", style = MaterialTheme.typography.titleLarge,
                  modifier = Modifier.weight(1f))
          }

          // Body: agent list or empty state
          if (agents.isEmpty()) {
              Box(modifier = Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                  Column(horizontalAlignment = Alignment.CenterHorizontally) {
                      Text("No agents detected", style = MaterialTheme.typography.bodyLarge,
                          color = MaterialTheme.colorScheme.onSurfaceVariant)
                      Text("Start a Copilot CLI, Claude Code, or Cursor session",
                          style = MaterialTheme.typography.bodySmall,
                          color = MaterialTheme.colorScheme.onSurfaceVariant)
                  }
              }
          } else {
              LazyColumn(modifier = Modifier.weight(1f)) {
                  items(agents, key = { it.id }) { agent -> AgentCard(agent) }
              }
          }

          // Footer
          Row(
              modifier = Modifier.fillMaxWidth().padding(12.dp),
              verticalAlignment = Alignment.CenterVertically,
          ) {
              Text("${agents.size} agent${if (agents.size != 1) "s" else ""} detected",
                  style = MaterialTheme.typography.bodySmall,
                  color = MaterialTheme.colorScheme.onSurfaceVariant,
                  modifier = Modifier.weight(1f))
              TextButton(onClick = onOpenSettings) { Text("Settings") }
          }
      }
  }
  ```

- [ ] **5.4 Create Settings.kt**
    - Provider enable/disable toggles
    - Global hotkey display (read-only for now)

  ```kotlin
  package com.agentpulse.ui

  import androidx.compose.foundation.layout.Column
  import androidx.compose.foundation.layout.Row
  import androidx.compose.foundation.layout.Spacer
  import androidx.compose.foundation.layout.fillMaxSize
  import androidx.compose.foundation.layout.height
  import androidx.compose.foundation.layout.padding
  import androidx.compose.foundation.layout.width
  import androidx.compose.material3.MaterialTheme
  import androidx.compose.material3.Text
  import androidx.compose.material3.TextButton
  import androidx.compose.runtime.Composable
  import androidx.compose.ui.Alignment
  import androidx.compose.ui.Modifier
  import androidx.compose.ui.unit.dp

  @Composable
  fun Settings(onBack: () -> Unit) {
      Column(modifier = Modifier.fillMaxSize().padding(16.dp)) {
          Row(verticalAlignment = Alignment.CenterVertically) {
              TextButton(onClick = onBack) { Text("← Back") }
              Spacer(Modifier.width(8.dp))
              Text("Settings", style = MaterialTheme.typography.titleLarge)
          }

          Spacer(Modifier.height(16.dp))

          Text("Hook status", style = MaterialTheme.typography.titleSmall)
          Text("Events are pushed from agent hooks — no polling needed.",
              style = MaterialTheme.typography.bodySmall,
              color = MaterialTheme.colorScheme.onSurfaceVariant)

          Spacer(Modifier.height(16.dp))
          Text("Global hotkey", style = MaterialTheme.typography.titleSmall)
          Text("Ctrl+Shift+` (configured in Step 11)",
              style = MaterialTheme.typography.bodySmall,
              color = MaterialTheme.colorScheme.onSurfaceVariant)
      }
  }
  ```

- [ ] **5.5 Create App.kt**
    - Navigation: Dashboard ↔ Settings
    - Wire to AgentSessionManager.agents StateFlow

  ```kotlin
  package com.agentpulse.ui

  import androidx.compose.runtime.Composable
  import androidx.compose.runtime.getValue
  import androidx.compose.runtime.mutableStateOf
  import androidx.compose.runtime.remember
  import androidx.compose.runtime.setValue
  import com.agentpulse.provider.AgentSessionManager

  @Composable
  fun App(stateManager: AgentSessionManager) {
      var screen by remember { mutableStateOf("dashboard") }

      AgentPulseTheme {
          when (screen) {
              "dashboard" -> Dashboard(
                  agentsFlow = stateManager.agents,
                  onOpenSettings = { screen = "settings" },
              )
              "settings" -> Settings(onBack = { screen = "dashboard" })
          }
      }
  }
  ```

- [ ] **5.6 Update Main.kt**
    - Replace placeholder UI with `App(stateManager)` composable

- [ ] **5.7 Verify with real data**
    - Run with active Copilot sessions
    - Agent cards show correct data
    - UI updates reactively when agents start/stop (push events from hooks)

- [ ] **5.8 Commit, push, and open PR**
