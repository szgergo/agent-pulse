# Step 1: scaffold — Compose Desktop tray app

> **⚠️ READ `shared-context.md` FIRST** — it contains all design principles, architecture,
> SQLite safety rules, connection hygiene, tech stack, and project structure that apply to this step.
> Path: `planning/implementation/shared-context.md`

---

> **Status: DONE** — Step 1 is merged to `main` and in active use.
> Build compiles. Tray popup behavior verified locally.

**Goal**: A working Compose Desktop system tray app that shows/hides a window via tray icon click.

**Pre-check**: The `agent-pulse` directory exists with research markdown files. Git is initialized on `main` branch.

**App state AFTER this step**: Running `./gradlew run` starts a tray-only app. On macOS, local runs additionally need macOS-only `-Dapple.awt.UIElement=true` to suppress the Dock icon; packaged app bundles use `LSUIElement` for the same outcome. The tray icon opens/closes an undecorated popup window showing placeholder agent cards. Closing the popup hides it (does not quit the app). Quitting is done from the in-window `Quit` button.

- [x] **1.1 Create Gradle wrapper and project structure**
    - `.gitignore` verified — already had all needed entries
    - Branch created: `step-1-scaffold`
    - Gradle wrapper initialized at 8.11 (`brew install gradle` was required first)
    - Directories created: `src/main/kotlin/com/agentpulse`, `src/main/composeResources/drawable`

- [x] **1.2 Create settings.gradle.kts**
    - Created as planned, no deviations.

- [x] **1.3 Create gradle.properties**
    - Created as planned, no deviations.

- [x] **1.4 Create build.gradle.kts**
    - Created with the following **deviations from original plan**:
      - **Added** `id("org.sonarqube") version "7.2.3.7755"` plugin — required for SonarCloud integration
      - **Added** `kotlin { jvmToolchain { languageVersion = 21; vendor = JETBRAINS } }` — aligns local/CI toolchain and JBR runtime expectations
      - **Changed** `packageVersion` from `"0.1.0"` to `"1.0.0"` — macOS DMG packaging requires MAJOR > 0
      - **Added** `sonar {}` block with `sonar.projectKey = "szgergo_agent-pulse"` and `sonar.organization = "agent-pulse"` — these are the actual SonarCloud project/org keys
      - **Added** conditional macOS JVM arg `-Dapple.awt.UIElement=true` for local `./gradlew run` — suppresses the Dock icon without affecting Windows/Linux
      - **Added** `compose.components.resources` dependency — tray icon is loaded from Compose resources

- [x] **1.5 Create tray icon**
    - Created heart tray icon at `src/main/composeResources/drawable/tray_icon.png`

- [x] **1.6 Create src/main/kotlin/com/agentpulse/Main.kt**
    - Implemented current scaffold UI with tray popup, California-vibes dark theme, and dummy agent cards.
    - Uses Compose resources (`Res.drawable.tray_icon`) via `org.jetbrains.compose.resources.painterResource`.

- [x] **1.7 Verify everything works**
    - [x] `./gradlew build` passes (verified on CI and locally)
    - [x] SonarCloud analysis succeeds on `main` with project key `szgergo_agent-pulse` and organization `agent-pulse`
    - [x] Popup window appears with "🫀 agent-pulse" title and dummy agent list
    - [x] System tray icon appears in macOS menu bar
    - [x] Tray click action toggles popup visibility
    - [x] In-window `Quit` button exits the app
    - [x] Closing window (red X) hides it — does NOT quit app
    - [x] On macOS, `./gradlew run` hides the Dock icon via macOS-only `-Dapple.awt.UIElement=true`

- [x] **1.8 Commit, push, and open PR**
    - Committed, pushed to `origin/step-1-scaffold`, merged into `main`
    - PR #2: https://github.com/szgergo/agent-pulse/pull/2
    - CI now passes on `main` (Build and analyze ✅, SonarCloud analysis ✅)

---

### Additional work done alongside Step 1 (SonarCloud integration)

This was not in the original Step 1 plan but was required to get SonarCloud working:

- **Added** `org.sonarqube` Gradle plugin (7.2.3.7755) to `build.gradle.kts`
- **Simplified** `.github/workflows/sonarcloud.yml` — removed broken fallback scanner path (was failing with `Error 404 on https://api.sonarcloud.io/analysis/analyses`), replaced with single Gradle path: `./gradlew build sonar --info`
- **Updated** SonarCloud organization/key to the actual SonarCloud values: `agent-pulse` / `szgergo_agent-pulse`
- **Deleted** `sonar-project.properties` — config moved into `build.gradle.kts` `sonar {}` block
- **Updated** `README.md` — development instructions and SonarCloud documentation
