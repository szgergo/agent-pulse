# agent-pulse

Universal AI Agent Dashboard — a lightweight system tray app that monitors AI coding agents running on your machine.

## Development

**Prerequisites**: JDK 17+ (for building), [JBR 25](https://github.com/JetBrains/JetBrainsRuntime/releases) (for running)

```bash
./gradlew run          # launch the app
./gradlew build        # compile + tests
```

## SonarCloud

Static analysis runs automatically on every push to `main` and on pull requests via `.github/workflows/sonarcloud.yml`.

The `org.sonarqube` Gradle plugin is configured in `build.gradle.kts` with:
- `sonar.projectKey = szgergo_agent-pulse`
- `sonar.organization = szgergo-1`

Analysis runs: `./gradlew build sonar --info`

**Prerequisite**: `SONAR_TOKEN` must be set in GitHub repository secrets. If missing, the workflow skips with a warning.
