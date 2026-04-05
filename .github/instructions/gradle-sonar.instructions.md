---
applyTo: "build.gradle.kts,.github/workflows/*.yml,gradle/**/*.properties,gradle.properties"
---

# Gradle and SonarCloud guidance

## Gradle

- Keep Kotlin, Compose, and serialization plugin versions compatible.
- Avoid incidental build logic churn in feature PRs.
- Preserve reproducible wrapper-driven commands.

## SonarCloud

- Keep `sonar.projectKey` and `sonar.organization` in sync with SonarCloud project metadata.
- Ensure `SONAR_TOKEN` is referenced only through GitHub secrets.
- For analysis failures, verify project key/org and default branch mapping first.

## GitHub Actions

- Keep actions references pinned according to repository policy.
- Keep Java setup explicit and deterministic.
- Use `fetch-depth: 0` for Sonar relevance when needed.

