# JBR WatchService vs IntelliJ NativeFileWatcher — Deep Research

## Problem Statement

Our `step03.md` HookEventWatcher uses `watchService.poll(200, TimeUnit.MILLISECONDS)` in a loop.
This is a **polling pattern** — the thread spins every 200ms checking for events.
Since we're running on JBR (JetBrains Runtime), which has a **native FSEvents-backed WatchService**,
this polling is unnecessary and wasteful. We want to use the native event delivery properly.

---

## Three Layers of File Watching in the JetBrains Ecosystem

### Layer 1: Standard OpenJDK WatchService on macOS — `PollingWatchService`

**Source**: [`openjdk/jdk` — `BsdFileSystem.java`](https://github.com/openjdk/jdk/blob/master/src/java.base/macosx/classes/sun/nio/fs/BsdFileSystem.java)

```java
@Override
public WatchService newWatchService() throws IOException {
    // use polling implementation until we implement a BSD/kqueue one
    return new PollingWatchService();
}
```

**How it works**: Pure Java polling. Literally calls `stat()` on every file in the watched directory
on a timer (default 10 seconds). No native OS integration whatsoever on macOS.

**Verdict**: 🔴 Terrible for our use case. High latency, high CPU for frequent events.

---

### Layer 2: JBR's `MacOSXWatchService` — Native FSEvents via JNI

**Source**: [`JetBrains/JetBrainsRuntime` — `MacOSXWatchService.java` + `MacOSXWatchService.c`](https://github.com/JetBrains/JetBrainsRuntime/blob/main/src/java.base/macosx/classes/sun/nio/fs/MacOSXWatchService.java)

JBR **replaces** the stock `PollingWatchService` with a native FSEvents implementation:

```java
// JBR's BsdFileSystem.java
@Override
public WatchService newWatchService() throws IOException {
    final boolean usePollingWatchService = Boolean.getBoolean("watch.service.polling");
    return usePollingWatchService ? new PollingWatchService() : new MacOSXWatchService();
}
```

**Architecture**:

```
┌───────────────────────────────────────────────────────────┐
│  Java Thread (your code)                                  │
│  ┌──────────────────────────────────────────────────────┐ │
│  │ val key = watchService.take()  // BLOCKS here        │ │
│  │   └── AbstractWatchService.pendingKeys.take()        │ │
│  │       └── LinkedBlockingDeque.take()                 │ │
│  └──────────────────────────────────────────────────────┘ │
│                          ▲                                │
│                          │ enqueueKey(key)                │
│                          │                                │
│  ┌──────────────────────────────────────────────────────┐ │
│  │ CFRunLoopThread (daemon)                             │ │
│  │   └── CFRunLoopRun() ← blocks on native run loop    │ │
│  │       └── FSEvents callback fires                   │ │
│  │           └── MacOSXWatchKey.handleEvents()          │ │
│  │               └── diffing dir snapshot vs new state  │ │
│  │               └── signal key → enqueue to deque      │ │
│  └──────────────────────────────────────────────────────┘ │
│                          ▲                                │
│                          │ native callback                │
│  ┌──────────────────────────────────────────────────────┐ │
│  │ macOS kernel (FSEvents)                              │ │
│  │   FSEventStreamCreate(path, latency, flags)          │ │
│  │   → coalesces events for `latency` seconds           │ │
│  │   → fires callback on CFRunLoop thread               │ │
│  └──────────────────────────────────────────────────────┘ │
└───────────────────────────────────────────────────────────┘
```

**Key design details**:

1. **Dedicated `CFRunLoopThread`** (daemon) runs `CFRunLoopRun()` — the native macOS event loop.
2. **FSEventStream** is created per watched directory with `FSEventStreamCreate()`.
3. When macOS delivers events, the C callback (`MacOSXWatchService.c`) calls back into Java via JNI.
4. Java-side `MacOSXWatchKey.handleEvents()` diffs a directory snapshot to produce CREATE/MODIFY/DELETE.
5. Signaled keys go into `AbstractWatchService.pendingKeys` — a `LinkedBlockingDeque`.
6. Your thread blocks on `watchService.take()` or `watchService.poll()` — these read from the deque.

**FSEvents latency** (coalescing interval, set at stream creation):

| WatchEvent.Modifier | Latency | FSEvents behavior |
|---|---|---|
| `SENSITIVITY_HIGH` | **0.1s** (100ms) | Events delivered within ~100ms |
| *(default)* | **0.5s** (500ms) | Events delivered within ~500ms |
| `SENSITIVITY_LOW` | **1.0s** | Events batched up to 1 second |

This is the **FSEvents coalescing latency** — the OS kernel batches filesystem events for this
duration before delivering them to avoid flooding. This is NOT the same as Java-side polling.

**The `take()` vs `poll()` distinction**:

```java
// AbstractWatchService — shared between JBR and OpenJDK
public final WatchKey take() throws InterruptedException {
    checkOpen();
    WatchKey key = pendingKeys.take();  // BLOCKS on LinkedBlockingDeque
    checkKey(key);
    return key;
}

public final WatchKey poll(long timeout, TimeUnit unit) throws InterruptedException {
    checkOpen();
    WatchKey key = pendingKeys.poll(timeout, unit);  // Blocks up to timeout
    checkKey(key);
    return key;
}
```

- `take()` **blocks indefinitely** until an event arrives. Zero CPU when idle. ✅
- `poll(200ms)` blocks for up to 200ms, returns null if nothing. Must be called in a loop. ❌

**With JBR's native FSEvents, `take()` is the correct pattern.** The native callback thread
pushes events into the blocking deque, and `take()` wakes up your thread immediately.

**Verdict**: 🟢 This is what we should use. True native events, zero polling.

---

### Layer 3: IntelliJ's `NativeFileWatcherImpl` + `fsnotifier` Binary

**Source**: [`JetBrains/intellij-community`](https://github.com/JetBrains/intellij-community)
- [`NativeFileWatcherImpl.java`](https://github.com/JetBrains/intellij-community/blob/master/platform/platform-impl/src/com/intellij/openapi/vfs/impl/local/NativeFileWatcherImpl.java)
- [`native/fsNotifier/mac/fsnotifier.c`](https://github.com/JetBrains/intellij-community/blob/master/native/fsNotifier/mac/fsnotifier.c)

IntelliJ does **not** use `java.nio.file.WatchService` at all. Instead, it runs a separate
**native C binary** (`fsnotifier`) and communicates via stdin/stdout pipes.

**Architecture**:

```
┌─────────────────────────────────────────────────────────────┐
│  IntelliJ Platform (Java)                                   │
│  ┌────────────────────────────────────────────────────────┐ │
│  │ NativeFileWatcherImpl                                  │ │
│  │   extends PluggableFileWatcher                         │ │
│  │   ↕ stdin/stdout pipe (UTF-8 text protocol)            │ │
│  │   ↕ OSProcessHandler reads stdout BLOCKING             │ │
│  │   ↕ Writes commands: "ROOTS\n/path1\n/path2\n#\n"     │ │
│  └────────────────────────────────────────────────────────┘ │
│                          ↕ pipe                             │
│  ┌────────────────────────────────────────────────────────┐ │
│  │ fsnotifier (native C binary, ~150 lines)               │ │
│  │   FSEventStreamCreate("/", latency=0.3s, kNoDefer)     │ │
│  │   Watches entire filesystem from "/"                   │ │
│  │   Callback → prints: "DIRTY\n/changed/path\n"         │ │
│  │   or: "RECDIRTY\n/changed/dir\n"                      │ │
│  │   Reads stdin for ROOTS/EXIT commands                  │ │
│  └────────────────────────────────────────────────────────┘ │
│                          ▲                                  │
│                          │ FSEvents callback                │
│  ┌────────────────────────────────────────────────────────┐ │
│  │ macOS kernel (FSEvents)                                │ │
│  │   Watching "/" with 0.3s latency, kNoDefer             │ │
│  └────────────────────────────────────────────────────────┘ │
└─────────────────────────────────────────────────────────────┘
```

**Why IntelliJ doesn't use WatchService**:

1. **Historical**: IntelliJ predates JBR's MacOSXWatchService (added ~2021). The fsnotifier
   architecture was built ~2010 when macOS JDK only had PollingWatchService.
2. **Watches entire filesystem**: fsnotifier watches `/` recursively — one FSEventStream for
   everything. WatchService watches individual directories. IntelliJ needs to watch thousands
   of project roots efficiently.
3. **Simpler event model**: fsnotifier reports DIRTY/RECDIRTY/RESET — just "something changed here,
   go rescan". IntelliJ's VFS layer handles the diffing. JBR's WatchService does per-file
   CREATE/MODIFY/DELETE diffing internally.
4. **Crash isolation**: If the watcher crashes, IntelliJ restarts it. With in-process JNI,
   a native crash would take down the entire JVM.

**fsnotifier details for macOS** (from `fsnotifier.c`):
- Single FSEventStream watching `"/"` (entire filesystem)
- Latency: **0.3 seconds**
- Flags: `kFSEventStreamCreateFlagNoDefer` — deliver first event immediately, then batch
- Events: Only reports directory-level changes (DIRTY, RECDIRTY, RESET)
- Communication: Line-based text protocol over stdin/stdout
- Auto-restarts on crash (NativeFileWatcherImpl handles this)

**Debouncing in NativeFileWatcherImpl**:
```java
// Consecutive identical path notifications are deduped (last 2 paths tracked)
private boolean isRepetition(String path) {
    synchronized (myLastChangedPaths) {
        for (var i = 0; i < myLastChangedPaths.length; ++i) { ... }
    }
}
```

**Verdict**: Not relevant for agent-pulse. This architecture is overkill for watching a single
directory. We only need to watch `~/.agent-pulse/events/` — one directory, no recursion.
JBR's WatchService is the perfect fit.

---

## What's Wrong with Our step03.md

The current `HookEventWatcher.startWatching()`:

```kotlin
private fun startWatching() {
    scope.launch {
        val watchService = FileSystems.getDefault().newWatchService()
        eventsDir.register(watchService, StandardWatchEventKinds.ENTRY_CREATE)

        while (isActive) {
            val key = watchService.poll(200, java.util.concurrent.TimeUnit.MILLISECONDS) // ❌
            if (key != null) {
                delay(200)  // ❌ Additional unnecessary delay
                for (event in key.pollEvents()) { ... }
                key.reset()
            }
        }
    }
}
```

**Problems**:

1. **`poll(200ms)` is wasteful** — On JBR, MacOSXWatchService delivers events via
   `LinkedBlockingDeque`. Using `take()` blocks with zero CPU until an event arrives.
   `poll(200ms)` spins the thread every 200ms for no reason.

2. **Extra `delay(200)` is wrong** — This adds a fixed 200ms delay on top of whatever
   FSEvents latency we get (100ms–500ms). With `take()`, the event has already been
   coalesced by FSEvents. Adding more delay just slows things down.

3. **No SENSITIVITY_HIGH modifier** — By default, JBR's MacOSXWatchService uses 0.5s
   FSEvents latency (SENSITIVITY_MEDIUM). For our use case (low-volume event files from
   hook scripts), we should use SENSITIVITY_HIGH (0.1s latency).

4. **Missing coroutine integration** — `take()` blocks the thread. In a coroutine context,
   we should use `runInterruptible(Dispatchers.IO)` to properly integrate with structured
   concurrency and cancellation.

---

## Recommended Fix

```kotlin
import com.sun.nio.file.SensitivityWatchEventModifier  // Available in JBR

private fun startWatching() {
    scope.launch {
        val watchService = FileSystems.getDefault().newWatchService()
        eventsDir.register(
            watchService,
            arrayOf(StandardWatchEventKinds.ENTRY_CREATE),
            SensitivityWatchEventModifier.HIGH,         // 0.1s FSEvents latency
        )

        while (isActive) {
            // Blocks until macOS FSEvents delivers an event — zero CPU when idle
            val key = runInterruptible(Dispatchers.IO) {
                watchService.take()                      // ✅ Native blocking, no polling
            }

            for (event in key.pollEvents()) {
                val filename = event.context() as? Path ?: continue
                val file = eventsDir.resolve(filename)
                if (file.name.endsWith(".json") && !file.name.startsWith(".tmp.")) {
                    processFile(file)
                }
            }
            key.reset()
        }
    }
}
```

**Changes**:
1. `poll(200ms)` → `take()` wrapped in `runInterruptible` for coroutine-safe blocking
2. Remove extra `delay(200)` debounce — FSEvents already coalesces at 100ms
3. Add `SensitivityWatchEventModifier.HIGH` for 100ms FSEvents latency
4. Zero CPU usage when idle — thread sleeps on native blocking deque

---

## `SensitivityWatchEventModifier` Availability

This class is a **JDK internal API** available at `com.sun.nio.file.SensitivityWatchEventModifier`:

```java
public enum SensitivityWatchEventModifier implements WatchEvent.Modifier {
    HIGH(2),
    MEDIUM(10),
    LOW(30);
}
```

On standard OpenJDK PollingWatchService, these values are used as polling intervals in seconds.
On JBR's MacOSXWatchService, they map to FSEvents latency:

| Modifier | OpenJDK Polling Interval | JBR FSEvents Latency |
|---|---|---|
| HIGH | 2 seconds | 0.1 seconds |
| MEDIUM | 10 seconds | 0.5 seconds |
| LOW | 30 seconds | 1.0 second |

Since we're guaranteed to run on JBR (Compose Desktop bundles it), we get the native behavior.
The enum itself is part of the JDK API (not JBR-specific), so no special imports needed.

---

## Summary Comparison Table

| Aspect | OpenJDK (macOS) | JBR (macOS) | IntelliJ fsnotifier |
|---|---|---|---|
| Implementation | `PollingWatchService` | `MacOSXWatchService` | Separate C binary |
| Mechanism | `stat()` polling | FSEvents via JNI | FSEvents via C |
| Latency (best) | 2 seconds | **100ms** | 300ms |
| CPU idle cost | Periodic wakeups | **Zero** (blocked) | Zero (blocked) |
| Per-file events | Yes (via stat diff) | Yes (snapshot diff) | No (directory only) |
| Crash isolation | N/A | JVM crash risk | Process restart |
| API | `WatchService` | `WatchService` | Pipe protocol |
| Our use case fit | ❌ | ✅ **Perfect** | ❌ Overkill |

---

## Key Takeaway for agent-pulse

**We should use `WatchService.take()` (blocking) with `SensitivityWatchEventModifier.HIGH`.**

JBR's `MacOSXWatchService` does all the heavy lifting:
- Native FSEvents integration (no polling)
- 100ms event coalescing (with SENSITIVITY_HIGH)
- Directory snapshot diffing (per-file CREATE/MODIFY/DELETE)
- Thread-safe delivery via `LinkedBlockingDeque`

We do NOT need:
- Our own poll loop (`poll(200ms)`)
- Our own debounce (`delay(200)`)
- The IntelliJ fsnotifier binary approach
- Any external native code

**The standard `java.nio.file.WatchService` API on JBR gives us native FSEvents for free.**

---

## Source References

- JBR `MacOSXWatchService.java`: https://github.com/JetBrains/JetBrainsRuntime/blob/main/src/java.base/macosx/classes/sun/nio/fs/MacOSXWatchService.java
- JBR `MacOSXWatchService.c` (JNI): https://github.com/JetBrains/JetBrainsRuntime/blob/main/src/java.base/macosx/native/libnio/fs/MacOSXWatchService.c
- JBR `BsdFileSystem.java` (factory): https://github.com/JetBrains/JetBrainsRuntime/blob/main/src/java.base/macosx/classes/sun/nio/fs/BsdFileSystem.java
- JBR `AbstractWatchService.java` (take/poll): https://github.com/JetBrains/JetBrainsRuntime/blob/main/src/java.base/share/classes/sun/nio/fs/AbstractWatchService.java
- OpenJDK `BsdFileSystem.java` (PollingWatchService): https://github.com/openjdk/jdk/blob/master/src/java.base/macosx/classes/sun/nio/fs/BsdFileSystem.java
- IntelliJ `NativeFileWatcherImpl.java`: https://github.com/JetBrains/intellij-community/blob/master/platform/platform-impl/src/com/intellij/openapi/vfs/impl/local/NativeFileWatcherImpl.java
- IntelliJ `fsnotifier.c` (macOS): https://github.com/JetBrains/intellij-community/blob/master/native/fsNotifier/mac/fsnotifier.c
