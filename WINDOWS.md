# Running Plutonium on Windows

This branch (`windows-port`) adds a Windows launcher for the Vulkan client. The renderer
itself is unchanged — it already talks to Vulkan through GLFW + LWJGL 3 and enables the
macOS portability extensions *conditionally*, so the same bytecode runs on a native
Windows Vulkan driver. The only platform-specific parts were in the launch script.

> **Status:** prepared correct-by-construction on macOS. It compiles (platform-independent
> bytecode) but has **not been run on Windows yet** — see "What to watch for" below.

## Prerequisites

- **JDK 21** (Temurin/Adoptium is fine) for the runtime. Put it on `PATH` or set
  `JAVA_HOME`.
- **JDK 8** only if you want to *recompile* on Windows (sources target 1.8). Set
  `JAVA8_HOME`. If you compiled on the Mac and copied `bin/minecraft` over, you don't
  need this.
- **A GPU with up-to-date Vulkan drivers.** Windows gets its Vulkan loader (`vulkan-1.dll`)
  from the graphics driver — NVIDIA/AMD/Intel all ship it. No MoltenVK, no Vulkan SDK
  required. The device must expose `VK_KHR_dynamic_rendering`, `VK_EXT_extended_dynamic_state`
  and `VK_KHR_push_descriptor` (all standard on drivers from the last few years).

## Setup (once)

`jars/libraries` is git-ignored, so native libraries are downloaded, not committed. From
the repo root in PowerShell:

```powershell
.\scripts\setup-windows.ps1
```

This pulls the LWJGL 3.3.3 **Windows x64** native jars into `jars\libraries\lwjgl3`.
(If you copied a working macOS tree over, these may already be present — the script skips
what exists.)

## Run

```powershell
.\launch-windows.bat            # or:  .\launch-windows.ps1
.\launch-windows.ps1 -Compile   # recompile changed sources first (needs JAVA8_HOME)
.\launch-windows.ps1 -VkSmoke   # standalone Vulkan smoke-test window
```

Optional env vars: `HEAP` (default `2G`), `JAVA_HOME`, `JAVA8_HOME`.

## How this differs from `launch.sh` (macOS)

| macOS (`launch.sh`)                     | Windows (`launch-windows.ps1`)                     |
|-----------------------------------------|----------------------------------------------------|
| `-XstartOnFirstThread`                  | dropped — macOS-only flag, unrecognized elsewhere  |
| MoltenVK dylib in the natives dir       | system `vulkan-1.dll` from the GPU driver          |
| `-Dorg.lwjgl.librarypath=<natives dir>` | dropped — LWJGL auto-extracts `*-natives-windows`  |
| `MVK_CONFIG_FAST_MATH_ENABLED=0` etc.   | dropped — MoltenVK-only env vars                   |
| macos-arm64 native jars                 | windows x64 native jars (`setup-windows.ps1`)      |

Everything else (JVM/G1 tuning, heap, `Start --gameDir jars`) is identical.

## What to watch for (first Windows run)

Because this hasn't run on a Windows box yet, these are the likely first-failure points,
in order:

1. **Missing device extension.** If startup throws "Required device extension missing",
   the driver is old — update the GPU driver. `dynamic_rendering` is the one to check.
2. **Depth-tie / fast-math text.** On macOS, MoltenVK's fast-math contraction rejected
   batched text at the same depth as geometry, so `MVK_CONFIG_FAST_MATH_ENABLED=0` was
   set. Native Windows drivers honor SPIR-V precision, so this shouldn't recur — but if
   text or same-depth overlays go missing, that's the first thing to suspect.
3. **`java.awt.headless=true`** is kept for parity with the working macOS config. If
   clipboard copy/paste throws `HeadlessException`, flip it to `false` in
   `launch-windows.ps1`.
4. **HiDPI scaling.** The `GLFW_COCOA_RETINA_FRAMEBUFFER` hint is a no-op on Windows, so
   the framebuffer already matches the window size in pixels — the intended behavior. If
   the UI looks mis-scaled on a high-DPI display, check `WindowManager.getScaleFactor()`
   (framebuffer vs window size) rather than assuming the renderer is wrong.
