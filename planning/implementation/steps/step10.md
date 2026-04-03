# Step 10: packaging — Build, CI, README

> **⚠️ READ `shared-context.md` FIRST** — it contains all design principles, architecture,
> SQLite safety rules, connection hygiene, tech stack, and project structure that apply to this step.
> Path: `planning/implementation/shared-context.md`

---


**Goal**: .dmg build, GitHub Actions CI, comprehensive README.

**Pre-check**: Step 9 PR is merged.

- [ ] **10.1 Verify .dmg build**
  ```bash
  ./gradlew packageDmg
  ```
  Check `build/compose/binaries/main/dmg/` for output. Install and test.

- [ ] **10.2 Create .github/workflows/build.yml**
  ```yaml
  name: Build
  on:
    push:
      branches: [main]
    pull_request:
      branches: [main]

  jobs:
    build-macos:
      runs-on: macos-latest
      steps:
        - uses: actions/checkout@v4
        - uses: actions/setup-java@v4
          with:
            distribution: 'jetbrains'
            java-version: '25'
        - uses: gradle/actions/setup-gradle@v4
        - run: ./gradlew build
        - run: ./gradlew packageDmg
        - uses: actions/upload-artifact@v4
          with:
            name: agent-pulse-macos
            path: build/compose/binaries/main/dmg/*.dmg
  ```

- [ ] **10.3 Write README.md**
    - Title, tagline, feature list, supported agents table, install instructions, build from source, architecture overview, adding a provider guide, license (MIT)

- [ ] **10.4 Create GitHub repo and push**
  ```bash
  gh repo create agent-pulse --public --description "🫀 Universal AI Agent Dashboard" --source .
  git push -u origin main
  ```

- [ ] **10.5 Commit, push, and open PR**
