# Tech Stack Decision: Kotlin/Compose Desktop + JetBrains Runtime

## Decision Summary

**agent-pulse** will be built with **Kotlin + Compose for Desktop + JetBrains Runtime (JBR)**.

This decision was driven by a critical discovery: the standard JVM cannot watch the macOS file system efficiently. agent-pulse relies on near-instant detection of file changes (lock files, session state) to track running AI agents. On macOS, the JVM's WatchService silently falls back to polling every 2-10 seconds -- unacceptable for a real-time dashboard. JetBrains Runtime solves this with a native FSEvents-based WatchService, and Compose Desktop bundles JBR by default, making the fix zero-configuration.

| Layer | Technology |
|---|---|
| **Language** | Kotlin/JVM |
| **UI Framework** | Compose for Desktop |
| **Runtime** | JetBrains Runtime (JBR) 21 LTS |
| **Process Scanning** | [OSHI](https://github.com/oshi/oshi) |
| **File Watching** | java.nio.file.WatchService (JBR native FSEvents impl) |
| **SQLite** | [sqlite-jdbc](https://github.com/xerial/sqlite-jdbc) |
| **System Tray** | Compose Tray composable |
| **Global Hotkey** | [JNativeHook](https://github.com/kwhat/jnativehook) |
| **Spotlight Bridge** | Swift CLI (bundled) |
| **Packaging** | Compose Gradle plugin (jpackage + jlink) |
| **Desktop Extras** | [JBR API](https://jetbrains.github.io/JetBrainsRuntimeApi/) (custom title bars, rounded corners, HiDPI) |

---

## The Problem: macOS File Watching on the JVM

### Why File Watching Matters

agent-pulse detects running AI agents by watching their data directories for changes:
- ~/.copilot/session-state/ -- lock files (inuse.PID.lock) appear/disappear as sessions start/stop
- ~/.claude/projects/ -- session directories change as Claude Code runs
- ~/.codex/ -- similar pattern

Real-time file watching is the **primary detection mechanism**. A 2-10 second delay means agents appear/disappear with noticeable lag -- a poor UX for a dashboard that should feel instant.

### The Discovery: OpenJDK Polls on macOS

Java's java.nio.file.WatchService API promises native file system event delivery. On Linux (inotify) and Windows (ReadDirectoryChangesW), it delivers. **On macOS, it silently falls back to polling.**

**Evidence chain:**

1. **OpenJDK source code** -- sun.nio.fs.PollingWatchService is used on macOS:
   - [PollingWatchService.java](https://github.com/openjdk/jdk/blob/master/src/java.base/share/classes/sun/nio/fs/PollingWatchService.java) -- scans directories by reading modification timestamps in a loop
   - Default interval: **10 seconds** (configurable down to ~2s, but still polling)

2. **OpenJDK bug tracker** -- [JDK-7133447](https://bugs.openjdk.org/browse/JDK-7133447): "WatchService should use FSEvents on macOS" -- filed in 2012, **still open as of 2025**

3. **OpenJDK PR 10140** -- [github.com/openjdk/jdk/pull/10140](https://github.com/openjdk/jdk/pull/10140): An FSEvents-based WatchService was implemented by [@mkartashev](https://github.com/mkartashev), reviewed, and ultimately **closed without merging** (September 2022 - January 2023). The PR was complex (~2000 lines of C + Java) and ran into edge cases with the OpenJDK review process.

4. **kfswatch confirms the problem** -- [kfswatch](https://github.com/irgaly/kfswatch) is a Kotlin Multiplatform file-watching library. On Kotlin/Native (macOS), it uses FSEvents directly. On Kotlin/JVM, it delegates to java.nio.file.WatchService -- inheriting the polling behavior. From the [README](https://github.com/irgaly/kfswatch#platform-implementation):

   > | Platform | Implementation |
   > |---|---|
   > | JVM | java.nio.file.WatchService |
   > | macOS (Native) | FSEvents |

   This means even purpose-built Kotlin file watchers cannot escape the JVM polling limitation.

### Why Polling Is Unacceptable

| Metric | Polling (OpenJDK) | Native FSEvents |
|---|---|---|
| **Latency** | 2-10 seconds | < 100 milliseconds |
| **CPU at idle** | Periodic directory scans | Zero (kernel push) |
| **Recursive watching** | Manual traversal | FILE_TREE flag, single call |
| **Scalability** | Degrades with directory size | O(1) kernel events |

For a system tray app that should feel as responsive as JetBrains Toolbox or Raycast, polling is a dealbreaker.

---

## The Solution: JetBrains Runtime (JBR)

### What Is JBR?

[JetBrains Runtime](https://github.com/JetBrains/JetBrainsRuntime) is a fork of OpenJDK maintained by JetBrains. It powers **every JetBrains IDE** (IntelliJ IDEA, WebStorm, PyCharm, etc.), **Android Studio**, and **JetBrains Toolbox**. It is not experimental -- it is the runtime that millions of developers use daily.

JBR includes platform-specific enhancements that OpenJDK lacks, including:
- **Native FSEvents WatchService on macOS** (the fix we need)
- Custom window title bar APIs
- Enhanced font rendering
- HiDPI scaling improvements
- Rounded corner support for windows

### How JBR Solves the File Watching Problem

JBR includes MacOSXWatchService -- a native FSEvents-based WatchService implementation that replaces OpenJDK PollingWatchService on macOS.

**Source code**: [MacOSXWatchService.java](https://github.com/JetBrains/JetBrainsRuntime/blob/a9c2d9575b025e35b10d83da1764f3b095801f89/src/java.base/macosx/classes/sun/nio/fs/MacOSXWatchService.java) + [native C companion](https://github.com/JetBrains/JetBrainsRuntime/blob/a9c2d9575b025e35b10d83da1764f3b095801f89/src/java.base/macosx/native/libnio/fs/MacOSXWatchService.c)

**How we know it works**: In the closed OpenJDK PR 10140, @mkartashev (the PR author) [confirmed](https://github.com/openjdk/jdk/pull/10140#issuecomment-1415761125):

> "JetBrains Runtime has FSEvents-based implementation of WatchService on macOS"

This is the same developer who wrote the OpenJDK PR -- they ported it to JBR where it shipped successfully.

**JBR YouTrack issue**: [JBR-3862](https://youtrack.jetbrains.com/issue/JBR-3862) -- tracks the native WatchService feature. Available in JBR 17, 21, and 25. Enabled by default -- no configuration needed.

### Key Technical Benefits

1. **Zero code changes** -- java.nio.file.WatchService is a standard API. Code written against it works on any JDK. On JBR, the implementation silently upgrades from polling to FSEvents.

2. **Recursive watching** -- JBR implementation supports the FILE_TREE modifier for recursive directory watching. Standard OpenJDK does not support this on macOS (you would need to register each subdirectory manually).

3. **Compose Desktop bundles JBR by default** -- When using the [Compose Gradle plugin](https://github.com/JetBrains/compose-multiplatform), the jpackage/jlink step automatically bundles JBR. No manual runtime configuration.

4. **JBR API bonus** -- The optional [JBR API](https://jetbrains.github.io/JetBrainsRuntimeApi/) (org.jetbrains.runtime:jbr-api:1.10.1) provides:
   - Custom title bar with embedded controls (like JetBrains Toolbox)
   - Rounded window corners
   - Enhanced HiDPI support
   - These are nice-to-have for a polished system tray app

### Products Built on JBR

JBR is production-proven at massive scale:

| Product | Company | Users |
|---|---|---|
| IntelliJ IDEA, WebStorm, PyCharm, etc. | JetBrains | Millions |
| Android Studio | Google | Millions |
| JetBrains Toolbox | JetBrains | Millions |
| JProfiler | ej-technologies | Enterprise |
| YourKit | YourKit | Enterprise |

---

## Legal Analysis

### License: GPL-2.0 with Classpath Exception

JBR is licensed identically to vanilla OpenJDK: **GPL-2.0 with Classpath Exception**.

- **[LICENSE](https://github.com/JetBrains/JetBrainsRuntime/blob/main/LICENSE)**: GPL-2.0 base
- **[ADDITIONAL_LICENSE_INFO](https://github.com/JetBrains/JetBrainsRuntime/blob/main/ADDITIONAL_LICENSE_INFO)**: Classpath Exception grant

The **Classpath Exception** is the critical part. It explicitly permits linking/bundling with applications regardless of license:

> "Linking this library statically or dynamically with other modules is making a combined work based on this library. Thus, the terms and conditions of the GNU General Public License cover the whole combination. As a special exception, the copyright holders of this library give you permission to link this library with independent modules to produce an executable, regardless of the license terms of these independent modules."

This means:
- Bundle JBR with a proprietary application
- Bundle JBR with an MIT/Apache/BSD application
- Distribute the combined application commercially
- No obligation to open-source your application code

### JetBrains-Specific Code (MacOSXWatchService)

One nuance: MacOSXWatchService.java has a JetBrains copyright header with plain GPL-2.0 (no explicit Classpath Exception in that file header). However:

- It is an **internal** class in sun.nio.fs -- application code never imports it directly
- Applications use the **public API** (java.nio.file.WatchService) which **does** have the Classpath Exception
- This is the same model as every other sun.* internal class in OpenJDK -- the Classpath Exception covers the public API boundary
- Every JetBrains product and third-party product on JBR operates under this same understanding

### Redistribution Requirements

When distributing agent-pulse with bundled JBR:
1. Include the JBR LICENSE file
2. Include ADDITIONAL_LICENSE_INFO (Classpath Exception text)
3. Provide a link to JBR source: https://github.com/JetBrains/JetBrainsRuntime
4. No need to open-source agent-pulse itself

### Recommended JBR Version

**JBR 21 LTS** (build 21.0.10-b1163.110)
- Long-term support until March 2026+
- Stable, well-tested
- All features we need (FSEvents WatchService, JBR API)

---

## Risks and Mitigations

| Risk | Severity | Mitigation |
|---|---|---|
| JBR diverges from OpenJDK in breaking ways | Low | JBR tracks OpenJDK closely; JetBrains depends on compatibility for their own IDEs |
| JBR discontinued | Very Low | Used by all JetBrains products + Android Studio; too critical to abandon |
| Bundle size larger than Tauri | Medium | ~80-120MB vs ~5MB for Tauri. Acceptable for a developer tool; JetBrains Toolbox ships the same way |
| FSEvents WatchService has bugs | Low | Battle-tested in IntelliJ (which watches entire project trees); fallback to periodic process scan exists |
| Kotlin/Compose Desktop less mature than React | Low | Compose Desktop is used by JetBrains Toolbox; actively maintained with growing ecosystem |

---

## Rejected Alternatives

### Option A: Tauri 2.0 (Rust + React/TypeScript)

Tauri was the original plan.md tech stack -- a Rust backend with React/TypeScript frontend producing ~5MB native binaries. It uses the notify crate which provides native FSEvents on macOS, solving the file watching problem from the start. The two-language split (Rust + TypeScript) adds development friction: separate build tools, separate type systems, IPC serialization between backend and frontend. For a single developer building a side project, maintaining proficiency in both ecosystems increases cognitive load. The Kotlin/Compose stack provides the same capabilities in a single language with a single build system (Gradle), while JBR gives us native file watching without needing Rust.

**Why rejected**: Two-language stack (Rust + TypeScript) increases complexity for a solo developer. Kotlin/Compose + JBR achieves the same goals in a single language.

### Option B: Kotlin/Compose + Vanilla OpenJDK

This was the natural starting point for a Kotlin desktop app -- use standard OpenJDK and the standard java.nio.file.WatchService. The problem is that on macOS, WatchService falls back to PollingWatchService which scans directories on a timer (2-10 second intervals). For a real-time agent dashboard, this polling delay is unacceptable. The discovery of this limitation (via OpenJDK source, JDK-7133447, kfswatch behavior analysis, and the closed PR 10140) is what drove the search for alternatives.

**Why rejected**: macOS file watching polls at 2-10 second intervals, making real-time agent detection impossible without workarounds.

### Option C: Kotlin/Compose + Rust File Watcher via FFM (Hybrid)

After discovering the macOS polling problem in Option B, a hybrid approach was explored: keep Kotlin/Compose for the UI but write a thin Rust file-watching library using the notify crate, called from Kotlin via Java Foreign Function and Memory API (FFM/Panama, JEP 454, finalized in Java 22). The research confirmed this is technically viable -- FFM provides 20-100ns call overhead, and the pipeline (Rust to cbindgen to C headers to jextract to Java bindings) works. However, it adds significant complexity: two build systems (Gradle + Cargo), native binary compilation per platform, and a known MethodHandle.invokeExact() gotcha in Kotlin where the compiler generates wrong bytecode (requiring wrapper functions or @JvmStatic workarounds). The IBM FFM article ([developer.ibm.com/articles/j-ffm](https://developer.ibm.com/articles/j-ffm/)) validated the approach but also highlighted the complexity.

**Why rejected**: JBR native FSEvents WatchService provides the same result (instant macOS file watching) with zero additional complexity -- no Rust, no FFM, no cross-language build pipeline. JBR made this entire category of workaround unnecessary.

---

## Conclusion

The JetBrains Runtime transforms what would be a two-language hack (Kotlin + Rust) or an unacceptable compromise (polling) into a clean, single-language solution. By bundling JBR through Compose Desktop default packaging, agent-pulse gets native macOS file watching, a proven desktop UI framework, and bonus desktop APIs -- all in Kotlin, all with one build system, all with a license that permits any distribution model.

The evidence is clear:
- OpenJDK has known this is a problem since 2012 ([JDK-7133447](https://bugs.openjdk.org/browse/JDK-7133447))
- The fix was implemented but could not land in OpenJDK ([PR 10140](https://github.com/openjdk/jdk/pull/10140))
- The fix shipped in JBR and powers every JetBrains IDE ([JBR-3862](https://youtrack.jetbrains.com/issue/JBR-3862))
- JBR is GPL-2.0 + Classpath Exception -- safe to bundle ([LICENSE](https://github.com/JetBrains/JetBrainsRuntime/blob/main/LICENSE))
- Compose Desktop bundles JBR by default -- zero configuration

This is the right stack for agent-pulse.
