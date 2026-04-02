# agent-pulse

## Security scanning

This repository includes SonarCloud analysis via `.github/workflows/sonarcloud.yml`.

The workflow supports two analysis paths:

- if `build.gradle.kts`, `settings.gradle.kts`, and `gradlew` exist, it runs `./gradlew --no-daemon build sonar`
- otherwise, it uses `sonar-project.properties` with the SonarCloud scan action
- if the `SONAR_TOKEN` secret is missing, it skips with an explanatory message

Current repository prerequisites:

1. create/import the `szgergo/agent-pulse` project in SonarCloud
2. set `SONAR_TOKEN` in GitHub repository secrets

When the Gradle scaffold is added later:

1. add the SonarQube Gradle plugin to `build.gradle.kts`
2. add the `sonar {}` block in `build.gradle.kts`

