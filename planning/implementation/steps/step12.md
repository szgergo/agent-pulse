# Step 12: spotlight — macOS Spotlight integration

> **⚠️ READ `shared-context.md` FIRST** — it contains all design principles, architecture,
> SQLite safety rules, connection hygiene, tech stack, and project structure that apply to this step.
> Path: `planning/implementation/shared-context.md`

---


**Goal**: Running agents appear in macOS Spotlight with rich metadata and deep links.

**Pre-check**: Step 11 PR is merged.

- [ ] **12.1 Create spotlight-bridge Swift CLI**
    - `spotlight-bridge/Package.swift` — swift-tools-version 5.9, platforms macOS 12+, CoreSpotlight + CoreServices
    - `spotlight-bridge/Sources/main.swift` — read JSON from stdin, dispatch to CSSearchableIndex
    - Build: `cd spotlight-bridge && swift build -c release`

  ```swift
  // Package.swift
  // swift-tools-version: 5.9
  import PackageDescription
  let package = Package(
      name: "spotlight-bridge",
      platforms: [.macOS(.v12)],
      targets: [
          .executableTarget(name: "spotlight-bridge", path: "Sources"),
      ]
  )
  ```

  ```swift
  // Sources/main.swift — reads JSON commands from stdin, indexes to Spotlight
  import CoreSpotlight
  import Foundation

  struct IndexCommand: Codable {
      let action: String  // "add" or "remove"
      let id: String
      let title: String?
      let description: String?
      let agentType: String?
  }

  let index = CSSearchableIndex.default()

  while let line = readLine() {
      guard let data = line.data(using: .utf8),
            let cmd = try? JSONDecoder().decode(IndexCommand.self, from: data) else { continue }

      switch cmd.action {
      case "add":
          let attrs = CSSearchableItemAttributeSet(contentType: .item)
          attrs.title = cmd.title
          attrs.contentDescription = cmd.description
          attrs.keywords = [cmd.agentType ?? "agent", "ai", "coding"]
          let item = CSSearchableItem(uniqueIdentifier: cmd.id,
                                       domainIdentifier: "com.agentpulse",
                                       attributeSet: attrs)
          index.indexSearchableItems([item]) { error in
              if let error { fputs("Index error: \(error)\n", stderr) }
          }
      case "remove":
          index.deleteSearchableItems(withIdentifiers: [cmd.id]) { _ in }
      default:
          fputs("Unknown action: \(cmd.action)\n", stderr)
      }
  }
  ```

- [ ] **12.2 Create SpotlightIndexer.kt**
    - Implements `SearchIndexer`
    - Spawns `spotlight-bridge` CLI, pipes JSON to stdin (read-only: we index our own data, not agent data)
    - Rich descriptions for semantic search

  ```kotlin
  class SpotlightIndexer : SearchIndexer {
      private var process: Process? = null
      private var writer: java.io.BufferedWriter? = null

      fun start() {
          val bridgePath = // Resolve bundled spotlight-bridge binary
              Path.of(System.getProperty("compose.application.resources.dir") ?: ".")
                  .resolve("spotlight-bridge")
          if (!bridgePath.exists()) return

          process = ProcessBuilder(bridgePath.toString())
              .redirectErrorStream(false).start()
          writer = process!!.outputStream.bufferedWriter()
      }

      override fun add(agent: AgentState) {
          val cmd = buildJsonObject {
              put("action", "add")
              put("id", agent.id)
              put("title", "${agent.agentType.icon} ${agent.summary ?: agent.agentType.displayName}")
              put("description", buildString {
                  append("${agent.agentType.displayName} agent")
                  agent.model?.let { append(" · $it") }
                  agent.cwd?.let { append(" · $it") }
              })
              put("agentType", agent.agentType.name)
          }.toString()
          writer?.write(cmd)
          writer?.newLine()
          writer?.flush()
      }

      override fun remove(id: String) {
          val cmd = """{"action":"remove","id":"$id"}"""
          writer?.write(cmd)
          writer?.newLine()
          writer?.flush()
      }

      fun stop() { writer?.close(); process?.destroy() }
  }
  ```

- [ ] **12.3 Wire into DetectionOrchestrator**
    - Replace `NoopIndexer` with `SpotlightIndexer` on macOS

- [ ] **12.4 Test end-to-end**
    - Build app, start agents, Cmd+Space → type "copilot" → see results

- [ ] **12.5 Commit, push, and open PR**
