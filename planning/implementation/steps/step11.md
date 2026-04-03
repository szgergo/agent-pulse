# Step 11: hotkey — Global hotkey (Ctrl+Shift+Backtick)

> **⚠️ READ `shared-context.md` FIRST** — it contains all design principles, architecture,
> SQLite safety rules, connection hygiene, tech stack, and project structure that apply to this step.
> Path: `planning/implementation/shared-context.md`

---


**Goal**: Register a system-wide keyboard shortcut to toggle the agent-pulse window, matching JetBrains Toolbox's approach.

**Pre-check**: Step 10 PR is merged.

- [ ] **11.1 Add JNA and JBR API dependencies to build.gradle.kts**
  ```kotlin
  // Global hotkey via JNA + Carbon (same approach as JetBrains Toolbox)
  implementation("net.java.dev.jna:jna:5.15.0")
  implementation("net.java.dev.jna:jna-platform:5.15.0")

  // JBR API — SystemShortcuts for conflict detection, desktop extras
  implementation("org.jetbrains.runtime:jbr-api:1.10.1")
  ```

- [ ] **11.2 Create src/main/kotlin/com/agentpulse/GlobalHotKey.kt**
  This uses JNA to call macOS Carbon `RegisterEventHotKey` — the same approach JetBrains Toolbox uses.
  Carbon is technically deprecated by Apple but still functional in current macOS and used by all JetBrains products, Alfred, Raycast, and many other system tray apps.
  ```kotlin
  package com.agentpulse

  import com.sun.jna.*
  import com.sun.jna.ptr.PointerByReference

  /**
   * Global hotkey registration via JNA + macOS Carbon.
   * Same approach as JetBrains Toolbox for system-wide shortcuts.
   *
   * Why not JNativeHook? JNativeHook uses CGEventTap which requires
   * Accessibility permissions. Carbon RegisterEventHotKey does not.
   * JetBrains Toolbox uses this Carbon approach for the same reason.
   *
   * Why not JBR API? JBR API's SystemShortcuts is read-only (queries
   * existing OS shortcuts). It doesn't register new global hotkeys.
   * We use it for conflict detection, not for registration.
   */
  class GlobalHotKey(private val onTrigger: () -> Unit) {

      @Suppress("FunctionName")
      interface CarbonLib : Library {
          companion object {
              val INSTANCE: CarbonLib = Native.load("Carbon", CarbonLib::class.java)
          }
          fun GetApplicationEventTarget(): Pointer
          fun RegisterEventHotKey(
              keyCode: Int, modifiers: Int, id: EventHotKeyID.ByValue,
              target: Pointer, options: Int, outRef: PointerByReference
          ): Int
          fun UnregisterEventHotKey(hotKeyRef: Pointer): Int
          fun InstallEventHandler(
              target: Pointer, handler: Pointer, numTypes: Int,
              list: Pointer, userData: Pointer?, outRef: PointerByReference?
          ): Int
      }

      @Structure.FieldOrder("signature", "id")
      open class EventHotKeyID : Structure() {
          @JvmField var signature: Int = 0
          @JvmField var id: Int = 0
          class ByValue : EventHotKeyID(), Structure.ByValue
      }

      private var hotKeyRef: Pointer? = null

      fun register() {
          try {
              val carbon = CarbonLib.INSTANCE
              val id = EventHotKeyID.ByValue().apply {
                  signature = 0x4150  // 'AP' for agent-pulse
                  id = 1
              }
              val outRef = PointerByReference()
              // Ctrl=0x1000, Shift=0x0200, Backtick keycode=50 on macOS
              val result = carbon.RegisterEventHotKey(
                  50, 0x1000 or 0x0200, id,
                  carbon.GetApplicationEventTarget(), 0, outRef
              )
              if (result == 0) {
                  hotKeyRef = outRef.value
                  println("[agent-pulse] Global hotkey registered: Ctrl+Shift+Backtick")
              } else {
                  System.err.println("[agent-pulse] Hotkey registration failed (code $result)")
              }
          } catch (e: Exception) {
              System.err.println("[agent-pulse] Global hotkey unavailable: ${e.message}")
          }
      }

      fun unregister() {
          hotKeyRef?.let {
              try { CarbonLib.INSTANCE.UnregisterEventHotKey(it) } catch (_: Exception) {}
          }
      }
  }
  ```
  > **Note**: The event handler wiring (InstallEventHandler + callback) needs additional JNA callback plumbing. The above is the registration skeleton — the implementation agent should complete the Carbon event loop integration. If Carbon proves too complex, fall back to JNativeHook as a simpler alternative (but note it requires Accessibility permissions).

- [ ] **11.3 Wire GlobalHotKey into Main.kt**
  Add to the `application` block in Main.kt:
  ```kotlin
  val hotkey = remember { GlobalHotKey { isVisible = !isVisible } }
  LaunchedEffect(Unit) { hotkey.register() }
  ```
  Update the Quit menu item to call `hotkey.unregister()` before `exitApplication()`.

- [ ] **11.4 Verify** — `Ctrl+Shift+Backtick` toggles the window (may need macOS accessibility permission)

- [ ] **11.5 Commit, push, and open PR**
  ```bash
  git commit -m "feat: add global hotkey (Ctrl+Shift+Backtick) via JNA+Carbon

  - JNA + macOS Carbon RegisterEventHotKey (same approach as JetBrains Toolbox)
  - No Accessibility permission needed (unlike CGEventTap/JNativeHook)
  - JBR API SystemShortcuts for future conflict detection

  Co-authored-by: Copilot <223556219+Copilot@users.noreply.github.com>"
  ```
