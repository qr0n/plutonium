# Plutonium launch script for Windows (LWJGL 3 / Vulkan).
#
# This mirrors launch.sh but drops the macOS-only pieces:
#   - no -XstartOnFirstThread (that JVM flag exists only on macOS and aborts elsewhere)
#   - no MoltenVK: on Windows LWJGL loads the system Vulkan loader (vulkan-1.dll), which
#     any modern GPU driver installs. The MVK_CONFIG_* env vars are MoltenVK-only and omitted.
#   - no -Dorg.lwjgl.librarypath: LWJGL auto-extracts the *-natives-windows jars from the
#     classpath, so we put those jars on the classpath instead of pointing at a natives dir.
#
# Prereqs: a JDK 21 runtime on PATH or via $env:JAVA_HOME. To recompile on Windows you also
# need a JDK 8 (for -source/-target 1.8) via $env:JAVA8_HOME. See WINDOWS.md.
#
# Usage:
#   .\launch-windows.ps1                 # run the client
#   .\launch-windows.ps1 -Compile        # recompile changed sources first, then run
#   .\launch-windows.ps1 -VkSmoke        # standalone Vulkan smoke-test window
#   .\launch-windows.ps1 -NetDebug -GpuDebug   # extra diagnostics

param(
    [switch]$Compile,
    [switch]$VkSmoke,
    [switch]$NetDebug,
    [switch]$GpuDebug,
    [string]$SmokeFrames = ""
)

$ErrorActionPreference = "Stop"
$ScriptDir = Split-Path -Parent $MyInvocation.MyCommand.Path

# --- locate Java --------------------------------------------------------------
# A bundled runtime (jre\ next to this script, shipped in tester builds) wins over the system one.
$BundledJava = Join-Path $ScriptDir "jre\bin\java.exe"
if     (Test-Path $BundledJava) { $Java = $BundledJava }
elseif ($env:JAVA_HOME)         { $Java = Join-Path $env:JAVA_HOME "bin\java.exe" }
else                            { $Java = "java" }
if     ($env:JAVA8_HOME) { $Javac = Join-Path $env:JAVA8_HOME "bin\javac.exe" }
elseif ($env:JAVA_HOME)  { $Javac = Join-Path $env:JAVA_HOME  "bin\javac.exe" }
else                     { $Javac = "javac" }

$Bin     = Join-Path $ScriptDir "bin\minecraft"
$Src     = Join-Path $ScriptDir "src\minecraft"
$GameDir = Join-Path $ScriptDir "jars"
$Sep     = [System.IO.Path]::PathSeparator   # ';' on Windows

# --- build the classpath (mirrors launch.sh) ----------------------------------
# LWJGL3: base jars + the Windows natives jars; exclude the macOS-arm64 natives jars.
$lwjgl3Dir = Join-Path $ScriptDir "jars\libraries\lwjgl3"
if (-not (Test-Path (Join-Path $lwjgl3Dir "lwjgl-3.3.3-natives-windows.jar"))) {
    Write-Warning "Windows LWJGL natives not found under jars\libraries\lwjgl3."
    Write-Warning "Run  .\scripts\setup-windows.ps1  once to download them, then retry."
}
$lwjgl3 = Get-ChildItem $lwjgl3Dir -Filter *.jar |
    Where-Object { $_.Name -notmatch 'natives-macos' } |
    ForEach-Object { $_.FullName }

# Everything else under jars\, excluding lwjgl3, any natives dir, the game jar and LWJGL2 leftovers.
$other = Get-ChildItem (Join-Path $ScriptDir "jars") -Recurse -Filter *.jar |
    Where-Object {
        $_.FullName -notmatch '\\lwjgl3\\' -and
        $_.FullName -notmatch 'natives'    -and
        $_.Name -ne '1.8.9.jar'            -and
        $_.Name -notmatch '^lwjgl-'        -and
        $_.Name -notmatch '^lwjgl_util-'   -and
        $_.Name -notmatch '^jinput-'       -and
        $_.Name -notmatch '^lwjgl-platform-'
    } | ForEach-Object { $_.FullName }

$cpCompile = (@($lwjgl3) + @($other) + @($Bin)) -join $Sep
$cpRun     = (@($Bin) + @($Src) + @($lwjgl3) + @($other)) -join $Sep

# --- optional recompile of changed sources ------------------------------------
if ($Compile) {
    Write-Host "==> Compiling..."
    $srcList = Join-Path $env:TEMP "plutonium_src.txt"
    # javac @argfiles treat backslashes as escapes, so emit forward-slash paths.
    Get-ChildItem $Src -Recurse -Filter *.java |
        ForEach-Object { $_.FullName.Replace('\', '/') } | Set-Content -Encoding ascii $srcList
    & $Javac -classpath $cpCompile -sourcepath $Src -d $Bin -source 1.8 -target 1.8 "@$srcList"
    if ($LASTEXITCODE -ne 0) { throw "javac failed ($LASTEXITCODE)" }
    Write-Host "==> Compilation done."
}

# --- Vulkan smoke test --------------------------------------------------------
if ($VkSmoke) {
    Write-Host "==> Launching VkSmokeTest (native Vulkan)..."
    $smokeArgs = @("-Djava.awt.headless=true", "-cp", $cpRun, "plutonium.gpu.vk.VkSmokeTest")
    if ($SmokeFrames -ne "") { $smokeArgs += $SmokeFrames }
    & $Java @smokeArgs
    exit $LASTEXITCODE
}

# --- launch -------------------------------------------------------------------
if ($env:HEAP) { $heap = $env:HEAP } else { $heap = "2G" }
# Relative on purpose: -Xlog splits on ':', so an absolute C:\ path breaks the option.
# The launch below runs from $ScriptDir.
$gcLog = "jars/logs/gc.log"

$jvm = @()
if ($NetDebug) { $jvm += "-Dplutonium.netdebug=true" }
if ($GpuDebug) { $jvm += "-Dplutonium.gpudebug=true" }
$jvm += @(
    "-Xms$heap", "-Xmx$heap",
    "-XX:+UseG1GC",
    "-XX:MaxGCPauseMillis=20",
    "-XX:+UnlockExperimentalVMOptions",
    "-XX:G1NewSizePercent=40",
    "-XX:G1MaxNewSizePercent=50",
    "-XX:G1HeapRegionSize=16M",
    "-XX:G1ReservePercent=15",
    "-XX:G1MixedGCCountTarget=4",
    "-XX:InitiatingHeapOccupancyPercent=20",
    "-XX:SurvivorRatio=32",
    "-XX:+AlwaysPreTouch",
    "-XX:+ParallelRefProcEnabled",
    "-XX:MaxDirectMemorySize=3G",
    "-Xlog:gc:file=${gcLog}:time,uptime",
    "-XX:+OptimizeStringConcat",
    "-Dplutonium.discordRpc=true",
    "-Djava.awt.headless=true",
    "-cp", $cpRun,
    "Start", "--gameDir", $GameDir
)

# Console output ([VK] device line, stack traces) is also copied to jars\logs\console.log
# so testers can send it back. Native stderr must not trip $ErrorActionPreference=Stop.
$consoleLog = Join-Path $GameDir "logs\console.log"
New-Item -ItemType Directory -Force -Path (Split-Path $consoleLog) | Out-Null
Write-Host "==> Launching Plutonium (Vulkan / Windows)... console log: $consoleLog"
Set-Location $ScriptDir
$ErrorActionPreference = "Continue"
& $Java @jvm 2>&1 | ForEach-Object { "$_" } | Tee-Object -FilePath $consoleLog
exit $LASTEXITCODE
