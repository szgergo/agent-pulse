# Step 9: otlp — Embedded OTLP receiver

> **⚠️ READ `shared-context.md` FIRST** — it contains all design principles, architecture,
> SQLite safety rules, connection hygiene, tech stack, and project structure that apply to this step.
> Path: `planning/implementation/shared-context.md`

---


**Goal**: A lightweight HTTP/JSON OTLP endpoint on localhost that receives Claude Code telemetry and Cursor hook data.

**Pre-check**: Step 8 PR is merged.

**Why**: Without OTLP, Claude Code monitoring is "Basic" (process + MEMORY.md only). With it, Claude Code jumps to "Excellent" — tokens, cost, tool calls, session IDs, everything. See `agent-monitoring-research.md` §Architectural Implication.

**App state AFTER**: Claude Code sessions (when OTel-configured) show full metrics in the dashboard. A setup wizard guides users through enabling telemetry.

- [ ] **9.1 Add Ktor dependency to build.gradle.kts**
  ```kotlin
  implementation("io.ktor:ktor-server-netty:2.3.12")
  implementation("io.ktor:ktor-server-content-negotiation:2.3.12")
  implementation("io.ktor:ktor-serialization-kotlinx-json:2.3.12")
  ```

- [ ] **9.2 Create OtlpReceiver.kt**
    - Start Ktor embedded server on `localhost:4318` (OTLP HTTP/JSON standard port)
    - `POST /v1/metrics` — parse OTLP metrics JSON, extract Claude Code metrics:
        - `claude_code.session.count`, `claude_code.token.usage`, `claude_code.cost.usage`
        - `claude_code.lines_of_code.count`, `claude_code.active_time.total`
    - `POST /v1/logs` — parse OTLP log events JSON, extract:
        - `claude_code.user_prompt`, `claude_code.tool_result`, `claude_code.api_request`
    - Route parsed data to the ClaudeCodeProvider for state updates
    - Handle port conflicts: if 4318 is taken, try 4320, log the chosen port
    - **This is a RECEIVE-only server — it NEVER pushes data to agents**

- [ ] **9.3 Create setup wizard UI**
    - One-time prompt: "Enable rich Claude Code monitoring?"
    - Display the env vars the user needs to set:
      ```
      export CLAUDE_CODE_ENABLE_TELEMETRY=1
      export OTEL_METRICS_EXPORTER=otlp
      export OTEL_LOGS_EXPORTER=otlp
      export OTEL_EXPORTER_OTLP_PROTOCOL=http/json
      export OTEL_EXPORTER_OTLP_ENDPOINT=http://localhost:4318
      ```
    - Offer to copy to clipboard
    - **Optional** (with explicit user consent): write managed settings to `/Library/Application Support/ClaudeCode/managed-settings.json` — this is the ONLY exception to the read-only rule and requires admin privileges

- [ ] **9.4 Wire OtlpReceiver into DetectionOrchestrator**
    - Start OTLP receiver on app launch
    - Route incoming metrics/events to ClaudeCodeProvider.updateFromOtel()

- [ ] **9.5 Verify** — configure Claude Code with OTel env vars, verify metrics appear in dashboard

- [ ] **9.6 Commit, push, and open PR**
