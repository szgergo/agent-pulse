# Research: Kotlin + Compose Multiplatform as Alternative Stack

## Summary

**Can we build agent-pulse with Kotlin + Compose for Desktop instead of Tauri + Rust?**

**Yes.** Every feature in the plan has a viable Kotlin/JVM equivalent. The trade-off is a larger binary (~50-65MB vs ~5MB) but a simpler single-language stack that's closer to Java.

## Feature-by-Feature Comparison

### 1. Process Scanning (detect running agents)

| | Tauri (Rust) | Kotlin/JVM |
|---|---|---|
| **Library** | `sysinfo` crate | **OSHI** (`com.github.oshi:oshi-core`) |
| **API** | `System::new().processes()` | `SystemInfo().operatingSystem.processes` |
| **Data** | PID, name, cmd, exe, CPU%, memory, parent PID, start time | PID, name, cmd, exe, CPU%, memory, parent PID, start time ✅ |
| **Process tree** | Manual parent→child grouping | Same — group by `getParentProcessID()` |
| **Cross-platform** | ✅ | ✅ (uses JNA internally) |

**Verdict**: ✅ **OSHI is a direct equivalent of sysinfo**. Same data, same API style. OSHI is mature (10+ years, 3.5k+ GitHub stars).

```kotlin
// OSHI example
val si = SystemInfo()
val processes = si.operatingSystem.getProcesses(null, null, 0)
for (proc in processes) {
    println("PID=${proc.processID} name=${proc.name} CPU=${proc.processCpuLoadBetweenTicks(proc)}")
}
```

### 2. File System Watching (detect new sessions)

| | Tauri (Rust) | Kotlin/JVM |
|---|---|---|
| **Library** | `notify` crate (RecommendedWatcher) | **kfswatch** or **Java WatchService** |
| **macOS backend** | FSEvents (native, event-driven) | ⚠️ See analysis below |
| **Linux backend** | inotify | inotify (WatchService) ✅ |
| **Windows backend** | ReadDirectoryChangesW | ReadDirectoryChangesW (WatchService) ✅ |
| **Debouncing** | `notify-debouncer-mini` (500ms) | Manual (coroutine delay + conflate) |

#### kfswatch Deep Dive

**Repository**: [irgaly/kfswatch](https://github.com/irgaly/kfswatch) — 134 ⭐, actively maintained (latest: v1.4.0), Apache 2.0
**Only 3 open issues** (1 feature request, 1 CI improvement, 1 dependency dashboard). Very clean.

**⚠️ CRITICAL FINDING: kfswatch on JVM uses WatchService, which POLLS on macOS.**

##### How this was discovered

kfswatch *looks* like it solves the macOS file watching problem — it's a Kotlin Multiplatform library that advertises "native" file system watching. Its README has a platform support table that tells the full story:

1. **Read the README's platform table** ([source](https://github.com/irgaly/kfswatch#platform-support)):
   The README explicitly lists which monitoring system each target uses. The key rows:

   | Target | Monitoring System |
   |---|---|
   | **Kotlin/JVM** (all OS) | `java.nio.file.WatchService` |
   | **Kotlin/Native macOS** | Kernel Queues (kqueue) |
   | **Kotlin/Native Linux** | inotify |
   | **Kotlin/Native Windows** | ReadDirectoryChangesW |

   The JVM row is the critical one: **on JVM, kfswatch delegates to `java.nio.file.WatchService` regardless of OS**. It does NOT use platform-native APIs on JVM.

2. **Understood what WatchService does on macOS**:
   Java's `WatchService` implementation on macOS does NOT use FSEvents or kqueue. Instead, it uses a **polling** implementation that periodically scans directories for changes. This is a well-known limitation documented in:
   - [JDK-7133447](https://bugs.openjdk.org/browse/JDK-7133447) — "WatchService does not use native FSEvents on macOS"
   - [OpenJDK source: `PollingWatchService.java`](https://github.com/openjdk/jdk/blob/master/src/java.base/share/classes/sun/nio/fs/PollingWatchService.java) — the actual macOS implementation
   - The polling interval is typically 2-10 seconds, meaning file changes are detected with that delay

   On **Linux**, `WatchService` correctly delegates to `inotify` (native, event-driven). On **Windows**, it delegates to `ReadDirectoryChangesW` (native). **macOS is the only platform where WatchService polls.**

3. **Confirmed kfswatch source code**:
   The kfswatch JVM implementation ([`KfsDirectoryWatcherJvm.kt`](https://github.com/irgaly/kfswatch/blob/main/kfswatch/src/jvmMain/kotlin/io/github/irgaly/kfswatch/KfsDirectoryWatcher.kt)) directly uses `java.nio.file.WatchService`. No native bridge, no FSEvents JNI — just the standard Java API.

4. **Checked if Kotlin/Native could help**:
   Kotlin/Native macOS target uses Kernel Queues (kqueue), which ARE native and event-driven. However, **Compose for Desktop requires the JVM target** — you cannot use Kotlin/Native targets with Compose for Desktop. So the native macOS kqueue path is unreachable for our use case.

5. **Checked open issues** ([issues page](https://github.com/irgaly/kfswatch/issues)):
   Only 3 issues, none related to macOS polling — suggesting the author considers this a known WatchService limitation, not a kfswatch bug.

##### Impact Summary

From the README's platform table:
| Target | Monitoring System |
|---|---|
| **Kotlin/JVM** on macOS | `WatchService` ← **polling on vanilla OpenJDK, but see JBR section below** |
| Kotlin/Native macOS | Kernel Queues (native, event-driven) |
| Kotlin/JVM on Linux | `WatchService` → inotify (native) ✅ |
| Kotlin/JVM on Windows | `WatchService` → ReadDirectoryChangesW (native) ✅ |

Since Compose for Desktop runs on **JVM** (not Kotlin/Native), kfswatch would use WatchService on macOS, which means **polling**. This is the same limitation as using raw `java.nio.file.WatchService` directly.

**Kotlin/Native macOS** uses Kernel Queues (event-driven), but you cannot use Kotlin/Native with Compose for Desktop — Compose for Desktop requires JVM.

**Impact for agent-pulse**: The ~2-10 second polling delay on macOS means:
- New Copilot sessions won't be detected instantly (2-10s delay vs instant with Rust's FSEvents)
- Slightly higher CPU usage from polling
- For a dashboard that refreshes anyway, this is **acceptable but not ideal**

**Possible workaround**: Use the 30-second fallback process scan more aggressively (e.g., every 5-10s) and treat file watching as a "bonus" fast-path. On Linux/Windows, it's instant; on macOS, it's polling either way.

**Alternative**: Use JNA/JNI to call macOS FSEvents directly from Kotlin/JVM, but this adds significant complexity.

**Bottom line**: kfswatch is well-built and production-ready, but on JVM+macOS it doesn't solve the polling problem. For agent-pulse, this means slightly delayed detection on macOS compared to the Rust stack.

```kotlin
// kfswatch usage — clean API, just not native on macOS JVM
val watcher = KfsDirectoryWatcher(scope = CoroutineScope(Dispatchers.Default))
watcher.add("~/.copilot/session-state/")
launch {
    watcher.onEventFlow.collect { event ->
        println("File changed: ${event.path}")
        performScan()
    }
}
```

**kfswatch also does NOT support recursive watching.** Only immediate children of the watched directory emit events. For `~/.copilot/session-state/`, we'd need to:
- Watch `~/.copilot/session-state/` for new session UUID directories (Create events)
- Then add each new UUID directory to the watcher to detect `inuse.*.lock` changes
- This is doable but more complex than Rust's `notify` which supports `RecursiveMode::Recursive`

**Verdict**: ⚠️ **Functional but with limitations on macOS JVM.** Polling-based (not event-driven), no recursive watching. Still works — just not as elegant or responsive as the Rust `notify` crate.

### 3. SQLite Reading (Copilot's session.db)

| | Tauri (Rust) | Kotlin/JVM |
|---|---|---|
| **Library** | `rusqlite` (bundled SQLite) | **sqlite-jdbc** (`org.xerial:sqlite-jdbc`) |
| **API** | `Connection::open_with_flags(path, READONLY)` | `DriverManager.getConnection("jdbc:sqlite:$path")` |
| **Cross-platform** | ✅ (bundled) | ✅ (bundled native libs in JAR) |

**Verdict**: ✅ **Direct equivalent.** sqlite-jdbc bundles native SQLite for all platforms.

```kotlin
val conn = DriverManager.getConnection("jdbc:sqlite:${sessionDir}/session.db")
val rs = conn.createStatement().executeQuery("SELECT * FROM sessions")
while (rs.next()) { /* read data */ }
conn.close()
```

### 4. System Tray

| | Tauri (Rust) | Kotlin/JVM |
|---|---|---|
| **Library** | Tauri `tray-icon` feature | Compose `Tray` composable |
| **API** | `TrayIconBuilder::new()` | `Tray(icon, menu = { ... })` |
| **Menu** | `Menu::with_items()` | Declarative `Item("...", onClick = {})` |
| **Click handling** | `on_tray_icon_event` callback | Click handler on Tray composable |
| **Notifications** | Via OS APIs | Built-in `trayState.sendNotification()` |

**Verdict**: ✅ **Compose's Tray composable is arguably nicer** — declarative, built-in, no boilerplate.

```kotlin
Tray(
    state = trayState,
    icon = painterResource("icon.png"),
    menu = {
        Item("Show/Hide", onClick = { toggleWindow() })
        Separator()
        Item("Quit", onClick = ::exitApplication)
    }
)
```

### 5. Global Hotkey (system-wide keyboard shortcut)

| | Tauri (Rust) | Kotlin/JVM |
|---|---|---|
| **Library** | `tauri-plugin-global-shortcut` | **JNativeHook** (`com.github.kwhat:jnativehook`) |
| **macOS** | Carbon RegisterEventHotKey | Cocoa event taps (needs Accessibility permission) |
| **Windows** | Win32 RegisterHotKey | Win32 RegisterHotKey via JNI |
| **Linux** | X11 XGrabKey | X11 via JNI |

**Verdict**: ✅ JNativeHook works but requires **Accessibility permission** on macOS (System Preferences → Privacy → Accessibility). This is an extra step users must perform. Tauri's plugin doesn't need this.

```kotlin
GlobalScreen.registerNativeHook()
GlobalScreen.addNativeKeyListener(object : NativeKeyListener {
    override fun nativeKeyPressed(e: NativeKeyEvent) {
        if (e.modifiers == NativeKeyEvent.CTRL_MASK or NativeKeyEvent.SHIFT_MASK 
            && e.keyCode == NativeKeyEvent.VC_BACKQUOTE) {
            toggleWindow()
        }
    }
})
```

### 6. Spotlight Integration

| | Tauri (Rust) | Kotlin/JVM |
|---|---|---|
| **Approach** | Swift bridge CLI (separate binary) | **Same** — Swift bridge CLI |
| **Communication** | Spawn process, pipe JSON stdin | Same — `ProcessBuilder`, pipe JSON stdin |

**Verdict**: ✅ **Identical approach.** Core Spotlight requires Swift/ObjC either way. The bridge CLI is the same regardless of backend language.

### 7. Distribution & Binary Size

| | Tauri (Rust) | Kotlin/JVM |
|---|---|---|
| **macOS** | .dmg (~5MB) | .dmg (~50-65MB) |
| **With ProGuard** | N/A | .dmg (~48-52MB) |
| **Windows** | .msi (~5MB) | .msi (~50-65MB) |
| **Packaging tool** | Tauri bundler | `jpackage` (Gradle plugin) or Conveyor |
| **JVM bundled** | No (native binary) | Yes (bundled JRE ~40MB) |

**Verdict**: ⚠️ **10-13x larger binary.** The JVM runtime adds ~40-50MB. For a system tray utility, this is noticeable but acceptable (JetBrains Toolbox itself is ~120MB installed).

### 8. Development Experience

| | Tauri (Rust + React) | Kotlin + Compose |
|---|---|---|
| **Languages** | Rust + TypeScript (2 languages) | Kotlin only (1 language) |
| **Frontend** | React + Tailwind CSS | Compose for Desktop |
| **Type sharing** | Manual mirror types | Single types |
| **Hot reload** | Vite HMR (frontend only) | Compose hot reload (experimental) |
| **Build time** | Rust compile: slow first, fast incremental | Kotlin compile: fast |
| **Learning curve** | Rust ownership model | Java-like (familiar) |
| **IDE** | VS Code + rust-analyzer | IntelliJ IDEA (best-in-class for Kotlin) |

**Verdict**: ✅ **Kotlin wins for developer experience** — single language, familiar to Java devs, excellent IDE support.

## Kotlin Tech Stack (Proposed)

| Layer | Technology | Equivalent of |
|---|---|---|
| Framework | **Compose for Desktop** | Tauri |
| Language | **Kotlin/JVM** | Rust |
| UI | **Compose UI** (declarative) | React + Tailwind |
| Process info | **OSHI** | sysinfo crate |
| File watching | **kfswatch** | notify crate |
| SQLite | **sqlite-jdbc** | rusqlite |
| Global hotkey | **JNativeHook** | tauri-plugin-global-shortcut |
| System tray | **Compose Tray** | Tauri tray-icon |
| Spotlight | Swift bridge CLI (same) | Swift bridge CLI (same) |
| Packaging | **jpackage** / Conveyor | Tauri bundler |

## Pros & Cons Summary

### Pros of switching to Kotlin
1. **Single language** — no Rust + TypeScript split, just Kotlin
2. **Java familiarity** — user already knows Java
3. **Simpler data sharing** — no IPC between Rust and JS, everything is Kotlin objects
4. **IntelliJ-first** — best tooling for Kotlin lives in IntelliJ
5. **JetBrains ecosystem** — same tech as Toolbox, backed by JetBrains

### Cons of switching to Kotlin
1. **Binary size** — ~50-65MB vs ~5MB (10x larger)
2. **Memory usage** — JVM baseline ~50-100MB vs native ~5-10MB (for a tray app, this matters)
3. **macOS file watching** — kfswatch uses WatchService on JVM = polling (see Section 2 deep dive). **Solved by hybrid approach (Option C)** — use a Rust `notify` watcher via FFM
4. **Global hotkey** — JNativeHook needs Accessibility permission on macOS
5. **Startup time** — JVM cold start ~1-2s vs native ~50ms (tray app starts on boot)
6. **Plan rewrite** — entire plan's code snippets would need to be rewritten in Kotlin/Compose

---

## Option C: Hybrid Approach — Kotlin/Compose UI + Rust Native Watcher

**The idea**: Write the UI and business logic in Kotlin/Compose (familiar, productive), but keep the file watcher in Rust (native FSEvents on macOS, instant detection). Connect them via FFI.

This eliminates the biggest weakness of the pure Kotlin stack (polling FS on macOS) while keeping the biggest strength (single UI language, great tooling).

### How to connect Rust ↔ Kotlin/JVM

There are **four** viable approaches, ranked by recommendation:

#### 1. FFM API (Java Foreign Function & Memory) — ⭐ RECOMMENDED

**What**: Java's official replacement for JNI, finalized in Java 22 (JEP 454). First LTS support in Java 25. Pure Java/Kotlin code — no C glue, no header files, no `javah`.

**Does it work from Kotlin?** ✅ **Yes.** FFM is a standard `java.lang.foreign` package in `java.base`. Kotlin/JVM has full interop with all Java APIs. Multiple sources confirm FFM usage from Kotlin with working examples (see code below). There's even experimental work on Kotlin-specific FFI wrappers like [`ffi-kotlin`](https://github.com/whyoleg/ffi-kotlin).

**⚠️ Kotlin-specific gotcha: `MethodHandle.invokeExact()`**

`invokeExact()` is a **signature-polymorphic** Java method — the bytecode type of every argument and the return type must match *exactly*. Kotlin's compiler sometimes adds boxing or implicit casts that break this contract, leading to `WrongMethodTypeException` at runtime.

**Two clean workarounds:**

1. **Use `invoke()` instead of `invokeExact()`** — `invoke()` performs type coercion automatically. The performance difference is negligible (~5-10ns more per call). For a file watcher that fires events a few times per second, this is irrelevant.

2. **Write the FFM bridge in a tiny Java helper class** — ~30 lines of Java that Kotlin calls as regular methods. This gives `invokeExact()` performance with no Kotlin type issues. Example:

```java
// Java helper — src/main/java/RustWatcherBridge.java
public class RustWatcherBridge {
    private static final MethodHandle WATCHER_NEW;
    static {
        var lookup = SymbolLookup.libraryLookup(libPath, Arena.global());
        WATCHER_NEW = Linker.nativeLinker().downcallHandle(
            lookup.findOrThrow("watcher_new"),
            FunctionDescriptor.of(ADDRESS, ADDRESS, JAVA_LONG, ADDRESS, ADDRESS)
        );
    }
    public static MemorySegment startWatcher(String path, MemorySegment callback) throws Throwable {
        try (var arena = Arena.ofConfined()) {
            var cPath = arena.allocateFrom(path);
            return (MemorySegment) WATCHER_NEW.invokeExact(cPath, (long) path.length(), callback, MemorySegment.NULL);
        }
    }
}
```

```kotlin
// Kotlin side — just calls the Java bridge
val handle = RustWatcherBridge.startWatcher("/Users/me/.copilot/session-state", callbackStub)
```

**Recommendation**: Use approach #1 (`invoke()`) for simplicity. If profiling ever shows it matters (it won't for a file watcher), switch to approach #2.

**How it works** (from [IBM's FFM article](https://developer.ibm.com/articles/j-ffm/) and [JEP 454](https://openjdk.org/jeps/454)):
1. Rust compiles as `cdylib` → produces `.dylib` (macOS), `.so` (Linux), `.dll` (Windows)
2. Kotlin loads the library via `SymbolLookup.libraryLookup()` — no `System.loadLibrary()` needed
3. Kotlin describes function signatures with `FunctionDescriptor`
4. `Linker.downcallHandle()` creates a `MethodHandle` for calling Rust functions
5. `Linker.upcallStub()` creates native function pointers for Kotlin callbacks → **this is how the watcher sends events back**
6. `Arena` manages native memory lifecycle with deterministic cleanup

**Kotlin code to call Rust watcher via FFM**:
```kotlin
import java.lang.foreign.*
import java.lang.foreign.ValueLayout.*
import java.lang.invoke.MethodHandle
import java.lang.invoke.MethodHandles
import java.lang.invoke.MethodType

object RustFileWatcher {
    private val LIB_PATH = extractNativeLib("libagent_pulse_watcher")
    private val lookup = SymbolLookup.libraryLookup(LIB_PATH, Arena.global())
    private val linker = Linker.nativeLinker()

    // Rust: extern "C" fn watcher_new(path: *const u8, len: usize, cb: fn(*const u8, usize, i32), ctx: *mut c_void) -> *mut Watcher
    private val WATCHER_NEW: MethodHandle = linker.downcallHandle(
        lookup.findOrThrow("watcher_new"),
        FunctionDescriptor.of(ADDRESS, ADDRESS, JAVA_LONG, ADDRESS, ADDRESS)
    )

    // Rust: extern "C" fn watcher_close(watcher: *mut Watcher)
    private val WATCHER_CLOSE: MethodHandle = linker.downcallHandle(
        lookup.findOrThrow("watcher_close"),
        FunctionDescriptor.ofVoid(ADDRESS)
    )

    // Kotlin callback that Rust calls on file events
    @JvmStatic
    fun onFileEvent(pathPtr: MemorySegment, pathLen: Long, eventType: Int) {
        val path = pathPtr.reinterpret(pathLen).getString(0)
        println("FS event: type=$eventType path=$path")
        // dispatch to Compose state / coroutine flow
    }

    fun start(watchPath: String): MemorySegment {
        val arena = Arena.global()
        val cPath = arena.allocateFrom(watchPath)

        // Create upcall stub — Rust will call this Kotlin method
        val callbackHandle = MethodHandles.lookup().findStatic(
            RustFileWatcher::class.java, "onFileEvent",
            MethodType.methodType(Void.TYPE, MemorySegment::class.java, Long::class.javaPrimitiveType, Int::class.javaPrimitiveType)
        )
        val callbackStub = linker.upcallStub(
            callbackHandle,
            FunctionDescriptor.ofVoid(ADDRESS, JAVA_LONG, JAVA_INT),
            arena
        )

        return WATCHER_NEW.invokeExact(cPath, watchPath.length.toLong(), callbackStub, MemorySegment.NULL) as MemorySegment
    }

    fun stop(watcher: MemorySegment) {
        WATCHER_CLOSE.invokeExact(watcher)
    }
}
```

**Rust side** (the `cdylib` watcher):
```rust
// Cargo.toml: crate-type = ["cdylib"]
use notify::{RecommendedWatcher, RecursiveMode, Watcher};
use notify_debouncer_mini::{new_debouncer, DebouncedEventKind};
use std::ffi::{c_char, c_int, c_void};
use std::path::Path;
use std::time::Duration;

type Callback = extern "C" fn(*const c_char, usize, c_int);

struct FileWatcher {
    _watcher: RecommendedWatcher,
}

#[no_mangle]
pub extern "C" fn watcher_new(
    path: *const u8, path_len: usize,
    callback: Callback,
    _context: *mut c_void,
) -> *mut FileWatcher {
    let path_str = unsafe { std::str::from_utf8_unchecked(std::slice::from_raw_parts(path, path_len)) };

    let (tx, rx) = std::sync::mpsc::channel();
    let mut debouncer = new_debouncer(Duration::from_millis(500), tx).unwrap();
    debouncer.watcher().watch(Path::new(path_str), RecursiveMode::Recursive).unwrap();

    // Spawn thread to forward debounced events via callback
    std::thread::spawn(move || {
        for result in rx {
            if let Ok(events) = result {
                for event in events {
                    let path_bytes = event.path.to_string_lossy();
                    let event_kind = match event.kind {
                        DebouncedEventKind::Any => 0,
                        DebouncedEventKind::AnyContinuous => 1,
                    };
                    callback(path_bytes.as_ptr() as *const c_char, path_bytes.len(), event_kind);
                }
            }
        }
    });

    Box::into_raw(Box::new(FileWatcher { _watcher: debouncer.into_inner() }))
}

#[no_mangle]
pub extern "C" fn watcher_close(watcher: *mut FileWatcher) {
    if !watcher.is_null() {
        unsafe { drop(Box::from_raw(watcher)); }
    }
}
```

**Performance** (benchmarked, [JMH data](https://github.com/zakgof/java-native-benchmark)):

| Approach | Call overhead (ns) | Notes |
|---|---|---|
| **FFM (Java 22+)** | **20–100 ns** | Fastest. 10-20% faster than JNI |
| JNI (jni-rs) | 80–150 ns | Legacy, verbose |
| JNA | 350–800 ns | Simplest, slowest |
| UniFFI/Gobley | 350–800 ns | Uses JNA internally |

FFM is not just "as fast as JNI" — it's **faster**, because it avoids the JNI state transition overhead. The JIT compiler can inline downcall handles like regular method handles.

**Key advantages for agent-pulse**:
- ✅ **No C glue code** — pure Kotlin on the JVM side, pure Rust on the native side
- ✅ **Upcalls** — Rust calls back into Kotlin via function pointers (for watcher events)
- ✅ **Type-safe** — `Arena` prevents use-after-free, segments have bounds checking
- ✅ **jextract support** — can auto-generate Kotlin bindings from C headers (use `cbindgen` on Rust side)
- ✅ **Standard API** — part of `java.base`, no external dependencies
- ✅ **Performance** — faster than JNI, orders of magnitude faster than JNA

**Caveats**:
- ⚠️ **Java 22+ required** — Compose Desktop supports this (ships with bundled JDK)
- ⚠️ **Integrity by Default warnings** — need `--enable-native-access=ALL-UNNAMED` flag (from Java 25, will eventually require explicit opt-in)
- ⚠️ **Manual FunctionDescriptor writing** — for our tiny 3-function API this is trivial; for large APIs, use jextract + cbindgen
- ⚠️ **Kotlin `invokeExact()` gotcha** — use `invoke()` instead, or write a thin Java bridge class (see above). Not a blocker, just a known quirk.

**IBM's specific guidance for Rust**: The IBM FFM article explicitly mentions that "Rust libraries should use `cbindgen`" to generate C headers, and then `jextract` can auto-generate Java/Kotlin bindings from those headers. This means the pipeline is:
```
Rust code → cbindgen → C header (.h) → jextract → Kotlin bindings (auto-generated)
```
This is a fully automated binding pipeline with zero manual JNI/JNA code.

#### 2. Gobley + UniFFI — Automated but slower

**What**: [Gobley](https://gobley.dev/) (346⭐, v0.3.7) is a Gradle plugin that uses Mozilla's [UniFFI](https://github.com/mozilla/uniffi-rs) to auto-generate Kotlin bindings from Rust.

**How it works**:
1. Write Rust functions with `#[uniffi::export]`
2. Gobley's Gradle plugin runs `cargo build` + UniFFI bindgen on each build
3. Auto-generated Kotlin code appears in your source set
4. Call Rust from Kotlin as if it were native Kotlin code

```rust
// Rust side — annotate with UniFFI macros
#[uniffi::export]
fn start_watcher(path: String, listener: Arc<dyn WatcherListener>) {
    // ... start notify watcher, call listener.on_event() for each event
}

#[uniffi::export(callback_interface)]
pub trait WatcherListener: Send + Sync {
    fn on_event(&self, path: String, event_type: i32);
}

uniffi::setup_scaffolding!();
```

```kotlin
// Kotlin side — auto-generated, just implement the callback
class MyListener : WatcherListener {
    override fun onEvent(path: String, eventType: Int) {
        println("Event: $path ($eventType)")
    }
}

// Usage
startWatcher("/Users/me/.copilot/session-state", MyListener())
```

**Pros**:
- ✅ Zero boilerplate — bindings are fully auto-generated
- ✅ Async Rust functions map to Kotlin `suspend fun`
- ✅ Callback interfaces work cleanly
- ✅ Gradle integration — `cargo build` runs automatically on `./gradlew build`

**Cons**:
- ❌ Uses **JNA internally** on JVM — 350-800ns call overhead (4-10x slower than FFM)
- ❌ Additional dependency (Gobley Gradle plugins, UniFFI Rust crate)
- ❌ Build complexity (Cargo + Gradle interop, version pinning)
- ❌ Relatively new project (346⭐ vs FFM which is part of the JDK)

**Verdict**: Great DX, but the JNA overhead is unnecessary for our tiny 3-function API. FFM is simpler and faster for this use case.

#### 3. Manual JNI (jni-rs crate)

The traditional approach. Rust uses the `jni` crate (1,535⭐) to implement Java native methods.

```rust
use jni::JNIEnv;
use jni::objects::{JClass, JString};
use jni::sys::jlong;

#[no_mangle]
pub extern "system" fn Java_com_agentpulse_RustWatcher_startWatcher(
    mut env: JNIEnv, _class: JClass, path: JString,
) -> jlong {
    let path: String = env.get_string(&path).unwrap().into();
    // ... start watcher, return handle
    0
}
```

**Verdict**: ❌ Verbose, fragile (function names must match Java package), requires C header generation, slower than FFM. No reason to use this for a new project in 2025+.

#### 4. JNA (direct, no UniFFI)

Load the Rust `.dylib` and call `extern "C"` functions via JNA's dynamic dispatch.

```kotlin
interface WatcherLib : Library {
    fun watcher_new(path: String, callback: WatcherCallback): Pointer
    fun watcher_close(handle: Pointer)
    companion object {
        val INSTANCE: WatcherLib = Native.load("agent_pulse_watcher", WatcherLib::class.java)
    }
}
```

**Verdict**: ❌ Simpler than JNI but 350-800ns overhead per call. Acceptable for infrequent calls, but FFM is strictly better (faster, safer, no dependency).

### Hybrid Architecture Summary

```
┌──────────────────────────────────────────────────────┐
│                  Kotlin/Compose Desktop               │
│  ┌──────────┐  ┌───────────┐  ┌────────────────┐    │
│  │ Tray UI  │  │ Dashboard │  │ Agent Provider │    │
│  │ (Compose)│  │ (Compose) │  │   Registry     │    │
│  └──────────┘  └───────────┘  └───────┬────────┘    │
│                                       │              │
│  ┌────────────────────────────────────┼──────────┐   │
│  │            FFM Bridge              │          │   │
│  │  SymbolLookup → MethodHandle       │          │   │
│  │  Upcall stubs for callbacks  ◄─────┘          │   │
│  └────────────┬───────────────────────────────────┘   │
│               │ (downcall / upcall)                   │
├───────────────┼──────────────────────────────────────┤
│               ▼                                       │
│  ┌─────────────────────────────────┐                 │
│  │   Rust cdylib (~800KB .dylib)   │                 │
│  │  ┌──────────────────────────┐   │                 │
│  │  │  notify crate            │   │                 │
│  │  │  FSEvents (macOS)        │   │                 │
│  │  │  inotify (Linux)         │   │                 │
│  │  │  ReadDirectoryChanges(W) │   │                 │
│  │  └──────────────────────────┘   │                 │
│  │  ┌──────────────────────────┐   │                 │
│  │  │  notify-debouncer-mini   │   │                 │
│  │  │  500ms debounce          │   │                 │
│  │  └──────────────────────────┘   │                 │
│  └─────────────────────────────────┘                 │
└──────────────────────────────────────────────────────┘
```

### Build Pipeline

```
  Rust (watcher crate)                  Kotlin/Compose (app)
  ────────────────────                  ────────────────────
  cargo build --release                 ./gradlew build
       │                                     │
       ▼                                     │
  target/release/                            │
    libagent_pulse_watcher.dylib             │
       │                                     │
       ├── cbindgen → watcher.h              │
       │       │                             │
       │       └── jextract → Kotlin bindings ──► auto-generated FFM code
       │                                     │
       └── copied into resources/lib/ ───────┘
                                             │
                                        jpackage
                                             │
                                        agent-pulse.app (~55MB)
                                          └── includes .dylib (~800KB)
```

### Compiled Size Estimates

| Component | Size |
|---|---|
| Rust watcher `.dylib` (optimized + stripped) | ~600-800 KB |
| Kotlin/Compose app (JVM + Compose runtime) | ~50-60 MB |
| **Total bundled app** | **~55 MB** |

### What the Rust watcher exposes (entire API surface — 3 functions)

```c
// C header (generated by cbindgen, consumed by jextract)
typedef void (*WatcherCallback)(const char* path, size_t path_len, int32_t event_type, void* context);

// Start watching a directory recursively. Returns opaque handle.
void* watcher_new(const char* path, size_t path_len, WatcherCallback callback, void* context);

// Add another path to an existing watcher.
bool watcher_add_path(void* handle, const char* path, size_t path_len);

// Stop watching and free resources.
void watcher_close(void* handle);
```

That's it. Three functions. The FFM binding code in Kotlin is ~40 lines. The Rust library is ~100 lines + notify crate.

### Existing solutions: watcher-rs

[**e-dant/watcher**](https://github.com/e-dant/watcher) already provides a Rust file watcher with built-in C FFI bindings. It has Go, Node, Python wrappers. No JVM wrapper exists yet — but its C API is almost identical to what we'd build:

```c
// watcher-rs C API (already exists)
void* wtr_watcher_open(const char* path, wtr_watcher_callback callback, void* context);
bool wtr_watcher_close(void* watcher);
```

We could either:
- **Use watcher-rs directly** — just write the FFM Kotlin bindings (~30 lines)
- **Build our own** on the `notify` crate — more control, slightly smaller binary

### Java Version Requirement

FFM is **finalized** (not preview) in Java 22+. Key timeline:
- Java 22 (Mar 2024): FFM finalized (JEP 454)
- **Java 25 (Sep 2025): First LTS with FFM** — recommended target
- Java 25+: `--enable-native-access` warnings for restricted methods

Compose for Desktop bundles its own JDK via `jpackage`, so we control the Java version. Using Java 25 (current LTS) is the natural choice.

---

## 🔥 Game-Changer: JetBrains Runtime Has Native FSEvents WatchService

### The Discovery

While researching [OpenJDK PR #10140](https://github.com/openjdk/jdk/pull/10140), a crucial finding emerged that potentially **eliminates the need for a Rust watcher entirely**.

### OpenJDK PR #10140 — What Happened

**Author**: [@mkartashev](https://github.com/mkartashev) (JetBrains engineer)
**Title**: "8293067: (fs) Implement WatchService using system library (macOS)"
**Filed**: Sep 2, 2022 | **Closed**: Feb 1, 2023 | **Status**: ❌ CLOSED WITHOUT MERGE

This PR attempted to add a native FSEvents-based `WatchService` to OpenJDK mainline. Key details from the PR:
- Uses macOS FSEvents API for instant directory change detection
- Supports `FILE_TREE` — native recursive watching (which polling `WatchService` does not)
- One service thread per WatchService instance, inactive unless changes occur
- Keeps file snapshots to detect individual file changes (FSEvents reports directories only)
- Had 106 comments, extensive code review, but was **never merged** — CSR (Compatibility & Specification Review) was not approved

The PR description explicitly states:
> "This code (albeit in a slightly modified form) has been in use at JetBrains for around half a year and a few bugs have been found and fixed during that time period."

### The Key Comment

On Feb 3, 2023, after the PR was closed, someone asked if the FSEvents WatchService would be available elsewhere. **mkartashev himself replied** ([comment #1415761125](https://github.com/openjdk/jdk/pull/10140#issuecomment-1415761125)):

> **"JetBrains Runtime has FSEvents-based implementation of WatchService on macOS."**

When asked if it's the same code, he confirmed ([comment #1415920211](https://github.com/openjdk/jdk/pull/10140#issuecomment-1415920211)):

> **"It is the implementation this one was based upon. No known bugs exist there at this moment in time (tests were run on IntelliJ infrastructure, of course)."**

### JetBrains Runtime (JBR) — Confirmed Implementation

**JBR** is JetBrains' fork of OpenJDK, used to run all JetBrains IDEs (IntelliJ, WebStorm, etc.).

**Source code confirmed** in GitHub:
- [`MacOSXWatchService.java`](https://github.com/JetBrains/JetBrainsRuntime/blob/a9c2d9575b025e35b10d83da1764f3b095801f89/src/java.base/macosx/classes/sun/nio/fs/MacOSXWatchService.java) — Java implementation
- [`MacOSXWatchService.c`](https://github.com/JetBrains/JetBrainsRuntime/blob/a9c2d9575b025e35b10d83da1764f3b095801f89/src/java.base/macosx/native/libnio/fs/MacOSXWatchService.c) — Native C code using FSEvents API

**YouTrack issue**: [JBR-3862](https://youtrack.jetbrains.com/issue/JBR-3862) — "Implement native WatchService on MacOS"

**Key facts**:
- Available in JBR 17, 21, and 25
- **Enabled by default** — no flags needed
- Can revert to polling with `-Dwatch.service.polling=true` (for debugging)
- Battle-tested: powers file watching in IntelliJ IDEA, WebStorm, Android Studio, etc.
- Supports `FILE_TREE` for recursive watching (unlike vanilla OpenJDK `PollingWatchService`)

### Why This Matters for agent-pulse

**Compose for Desktop can bundle JBR instead of vanilla OpenJDK.**

In `build.gradle.kts`:
```kotlin
compose.desktop {
    application {
        // Point to JBR SDK instead of vanilla OpenJDK
        javaHome = "/path/to/jbrsdk-21.0.x"
        // Or use the toolchain:
        // javaHome = jbrSdk.resolve("Contents/Home").toString()
    }
}
```

JBR is actually the **recommended** runtime for Compose Desktop apps — JetBrains themselves use it for Fleet (which is a Compose Desktop app).

**The chain of implications**:
1. Compose Desktop bundles JBR → ✅
2. JBR's `WatchService` uses FSEvents on macOS → ✅
3. `kfswatch` on JVM delegates to `WatchService` → ✅
4. Therefore: **kfswatch + JBR = native FSEvents on macOS** → ✅
5. **No polling. No Rust watcher. No FFI. Pure Kotlin all the way.** → 🎉

### What About Recursive Watching?

Standard `WatchService` (even in vanilla OpenJDK) does NOT support recursive watching — you must register each directory individually. But JBR's implementation **does** support `FILE_TREE` via the `ExtendedWatchEventModifier`:

```kotlin
import com.sun.nio.file.ExtendedWatchEventModifier
import java.nio.file.*

val watcher = FileSystems.getDefault().newWatchService()
val path = Path.of(System.getProperty("user.home"), ".copilot", "session-state")

// With JBR: this uses FSEvents natively and watches recursively!
path.register(
    watcher,
    arrayOf(
        StandardWatchEventKinds.ENTRY_CREATE,
        StandardWatchEventKinds.ENTRY_DELETE,
        StandardWatchEventKinds.ENTRY_MODIFY
    ),
    ExtendedWatchEventModifier.FILE_TREE  // JBR supports this!
)
```

Note: `kfswatch` doesn't support recursive watching directly. But with JBR, you can use `WatchService` directly with `FILE_TREE` — no need for kfswatch at all.

### Potential Risks / Considerations

1. **JBR is not vanilla OpenJDK** — you're coupling to JetBrains' fork. But:
   - JBR tracks OpenJDK closely (currently based on OpenJDK 21 and 25)
   - JetBrains publishes regular releases with security patches
   - All JetBrains IDEs depend on it — it's not going away
   - Compose Desktop already recommends JBR

2. **Binary size**: JBR SDK is ~200MB download, but `jpackage` strips it down. The bundled JRE in the final app is ~50-60MB (same as vanilla OpenJDK).

3. **Cross-platform**: JBR's FSEvents WatchService is macOS-only. But that's exactly the platform where vanilla WatchService polls. On Linux (inotify) and Windows (ReadDirectoryChangesW), vanilla OpenJDK's WatchService is already native — JBR just adds the missing macOS piece.

4. **No OpenJDK mainline path**: The upstream PR was closed and there's been no new attempt as of July 2023 (last comment on the PR). So JBR remains the only source for native macOS WatchService on JVM. If OpenJDK ever adds it, you can switch to vanilla OpenJDK with no code changes.

---

## Updated Recommendation (Four Options)

### Option A: Pure Tauri/Rust
- ✅ Smallest binary (5MB), lowest memory, instant FS detection
- ✅ Plan.md already written with full code
- ❌ Must write Rust + TypeScript (two languages)
- **Best if**: you want maximum efficiency and don't mind Rust

### Option B: Pure Kotlin/Compose (vanilla OpenJDK)
- ✅ Single language (Kotlin), great tooling, JetBrains ecosystem
- ❌ macOS file watching is polling (2-10s delay) — kfswatch/WatchService limitation
- ❌ 50MB+ binary, higher memory
- **Best if**: you want simplicity and don't care about FS detection speed

### Option C: Kotlin/Compose + Rust Watcher (Hybrid via FFM)
- ✅ Kotlin UI + business logic (familiar, productive)
- ✅ Native FSEvents on macOS via Rust `notify` crate (instant detection)
- ✅ FFM API is the modern standard — faster than JNI, no C glue code
- ✅ Tiny Rust surface (3 functions, ~800KB .dylib)
- ⚠️ Two languages (Kotlin + Rust), two build systems
- ⚠️ Requires Java 22+ (use Java 25 LTS via jpackage)
- **Best if**: you want native performance but can't use JBR for some reason

### Option D: Pure Kotlin/Compose + JBR — ⭐ NEW RECOMMENDATION
- ✅ **Single language** — pure Kotlin, no Rust, no FFI, no native code
- ✅ **Native FSEvents on macOS** — JBR's WatchService uses FSEvents natively
- ✅ **Recursive watching** — JBR supports `FILE_TREE` modifier (vanilla OpenJDK doesn't)
- ✅ JetBrains ecosystem — Compose, Kotlin, JBR are all maintained by the same org
- ✅ **Battle-tested** — powers all JetBrains IDEs (IntelliJ, WebStorm, Android Studio, Fleet)
- ✅ Same ~50MB binary as Option B (JBR stripped by jpackage = same size as vanilla JDK)
- ✅ Cross-platform native file watching: FSEvents (macOS/JBR) + inotify (Linux) + ReadDirChanges (Windows)
- ⚠️ Coupled to JetBrains' OpenJDK fork (low risk — it powers all their IDEs)
- ⚠️ If OpenJDK ever adds native macOS WatchService, this advantage disappears (but code works either way)
- **Best if**: you want maximum simplicity with zero compromise on macOS file watching performance

### Why Option D is now recommended over Option C

Option C (Kotlin + Rust via FFM) was recommended because the macOS polling problem seemed unsolvable without native code. **JBR solves this entirely within the JVM.** Comparing Option C vs D:

| Aspect | Option C (Hybrid) | Option D (JBR) |
|---|---|---|
| Languages | Kotlin + Rust | Kotlin only |
| Build systems | Gradle + Cargo | Gradle only |
| FFI complexity | FFM bindings, cbindgen, jextract | None |
| macOS FS detection | Rust `notify` → FSEvents | JBR WatchService → FSEvents |
| Binary size | ~55MB (50MB JRE + 0.8MB .dylib) | ~50MB |
| Maintenance burden | Two codebases | One codebase |
| File watching code | ~40 lines Kotlin + ~100 lines Rust | ~20 lines Kotlin (standard WatchService API) |
| Java version needed | 22+ (for FFM) | Any (JBR 17, 21, or 25) |

Option D achieves **the same outcome** (native FSEvents on macOS) with **dramatically less complexity**. The Rust watcher was a surgical fix for a JVM limitation — but JBR already has that fix built in.

The only scenario where Option C still wins: if you absolutely cannot use JBR (e.g., corporate policy requiring vanilla OpenJDK). For agent-pulse, this is not a constraint.

### Final Verdict

**Option D (Pure Kotlin/Compose + JBR)** is the optimal choice. It combines:
- The simplicity of Option B (single language, single build system)
- The native macOS performance of Option A/C (FSEvents, no polling)
- The JetBrains ecosystem alignment that makes Compose Desktop a natural fit

The plan.md would need to be rewritten to replace Tauri/Rust/React with Kotlin/Compose/JBR, but the architecture (AgentProvider trait, process scanner, file watcher, tray UI) maps directly.
