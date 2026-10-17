# One-time setup for running Plutonium on Windows.
#
# Downloads the LWJGL 3.3.3 Windows x64 native jars into jars\libraries\lwjgl3.
# jars\libraries is .gitignore'd (natives are never committed), so a fresh checkout
# needs this step. Safe to re-run; existing jars are skipped.

$ErrorActionPreference = "Stop"
$ScriptDir = Split-Path -Parent $MyInvocation.MyCommand.Path
$Root      = Split-Path -Parent $ScriptDir
$Dest      = Join-Path $Root "jars\libraries\lwjgl3"
$Base      = "https://repo1.maven.org/maven2/org/lwjgl"
$Ver       = "3.3.3"

# lwjgl-vulkan has NO Windows native jar — Vulkan is provided by the system loader
# (vulkan-1.dll), installed by any modern GPU driver. Only these modules ship natives.
$Modules = @("lwjgl", "lwjgl-glfw", "lwjgl-openal", "lwjgl-stb", "lwjgl-opengl")

if (-not (Test-Path $Dest)) { New-Item -ItemType Directory -Path $Dest | Out-Null }

foreach ($m in $Modules) {
    $file = "$m-$Ver-natives-windows.jar"
    $out  = Join-Path $Dest $file
    if (Test-Path $out) { Write-Host "skip  $file (already present)"; continue }
    $url = "$Base/$m/$Ver/$file"
    Write-Host "get   $file"
    Invoke-WebRequest -Uri $url -OutFile $out
}

Write-Host ""
Write-Host "Done. Windows natives are in $Dest"
Write-Host "Next: run  .\launch-windows.bat  (needs a JDK 21 on PATH or via `$env:JAVA_HOME)."
