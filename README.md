# agent-pulse

## Security scanning bootstrap

This repository already includes a bootstrap GitHub Actions workflow at `.github/workflows/sonarcloud.yml`.

It is intentionally safe to enable before the Kotlin/Gradle app scaffold exists:

- if the Gradle project files are not present yet, the workflow exits successfully without running analysis
- if the `SONAR_TOKEN` repository secret is not configured yet, the workflow exits successfully without running analysis
- once both prerequisites exist, the workflow runs `./gradlew --no-daemon build sonar`

To fully activate SonarCloud:

1. create/import the `szgergo/agent-pulse` project in SonarCloud
2. add the `SONAR_TOKEN` repository secret in GitHub
3. add the SonarQube Gradle plugin and `sonar {}` configuration to `build.gradle.kts`

