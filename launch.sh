#!/bin/bash
# Plutonium launch script (LWJGL 3 / GPU rewrite)
set -e

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"

# Metal fast-math lets the shader compiler contract mul+add into FMA, which breaks
# GL's exact depth-tie behavior (batched text at the same depth as the geometry under
# it gets LEQUAL-rejected). Verified 2026-07-12: text invisible with fast math on.
export MVK_CONFIG_FAST_MATH_ENABLED=0
# Runtime: Java 21 (GLFW needs -XstartOnFirstThread on macOS). Compiler: a Java 8 javac,
# since the sources target 1.8. Override with JAVA=/path/to/java JAVAC=/path/to/javac.
# `tools/patchset.py setup` records the JDKs it found or downloaded in jars/jdk.env.
if [ -f "$SCRIPT_DIR/jars/jdk.env" ]; then
    eval "$(sed -e 's/^JAVA=/: "${JAVA:=/;s/^JAVAC=/: "${JAVAC:=/;s/"$/"}"/' "$SCRIPT_DIR/jars/jdk.env")"
fi
JAVA8="${JAVA:-/Library/Java/JavaVirtualMachines/temurin-21.jdk/Contents/Home/bin/java}"
JAVA8_JAVAC="${JAVAC:-/Library/Java/JavaVirtualMachines/temurin-8.jdk/Contents/Home/bin/javac}"
NATIVES="$SCRIPT_DIR/jars/versions/1.8.9/1.8.9-natives"
BIN="$SCRIPT_DIR/bin/minecraft"
SRC="$SCRIPT_DIR/src/minecraft"
GAME_DIR="$SCRIPT_DIR/jars"

# Build classpath: LWJGL 3 jars + all other libs (exclude LWJGL 2, jinput, minecraft.jar, natives)
LWJGL3_CP=$(find "$SCRIPT_DIR/jars/libraries/lwjgl3" -name "*.jar" ! -name "*natives*" | tr '\n' ':')
OTHER_CP=$(find "$SCRIPT_DIR/jars" -name "*.jar" \
    ! -path "*/lwjgl3/*" \
    ! -path "*/natives/*" \
    ! -name "1.8.9.jar" \
    ! -name "lwjgl-*.jar" \
    ! -name "lwjgl_util-*.jar" \
    ! -name "jinput-*.jar" \
    ! -name "lwjgl-platform-*.jar" \
    | tr '\n' ':')
CP_COMPILE="${LWJGL3_CP}${OTHER_CP}${BIN}"
CP_RUN="${BIN}:${SRC}:${LWJGL3_CP}${OTHER_CP}"

# M0 Vulkan smoke test (standalone window; optional frame-count arg auto-exits)
if [ "$1" = "--vk-smoke" ]; then
    echo "==> Launching VkSmokeTest (MoltenVK)..."
    cd "$SCRIPT_DIR"
    exec "$JAVA8" \
        -XstartOnFirstThread \
        -Djava.awt.headless=true \
        -Dorg.lwjgl.librarypath="$NATIVES" \
        -Djava.library.path="$NATIVES" \
        -cp "$CP_RUN" \
        plutonium.gpu.vk.VkSmokeTest "${@:2}"
fi

# Recompile changed files if requested
if [ "$1" = "--compile" ] || [ "$1" = "-c" ]; then
    echo "==> Compiling..."
    mkdir -p "$BIN"
    find "$SRC" -name "*.java" > /tmp/plutonium_src.txt
    "$JAVA8_JAVAC" \
        -classpath "$CP_COMPILE" \
        -sourcepath "$SRC" \
        -d "$BIN" \
        -source 1.8 -target 1.8 \
        @/tmp/plutonium_src.txt 2>&1 | grep -v "^Note:" | grep -v "^$" || true
    echo "==> Compilation done."
fi

# Optional diagnostics (combinable with -c): --gpu-capture writes the first rendered
# frame to a .gputrace for Xcode; --net-debug prints the per-second [NET] cost line.
EXTRA_JVM_ARGS=""
for arg in "$@"; do
    if [ "$arg" = "--gpu-capture" ]; then
        CAPTURE_FILE="$GAME_DIR/logs/frame-$(date +%Y%m%d-%H%M%S).gputrace"
        export MTL_CAPTURE_ENABLED=1
        export MVK_CONFIG_AUTO_GPU_CAPTURE_SCOPE=2
        export MVK_CONFIG_AUTO_GPU_CAPTURE_OUTPUT_FILE="$CAPTURE_FILE"
        echo "==> GPU capture armed: first frame -> $CAPTURE_FILE (open in Xcode)"
    elif [ "$arg" = "--net-debug" ]; then
        EXTRA_JVM_ARGS="$EXTRA_JVM_ARGS -Dplutonium.netdebug=true"
    elif [ "$arg" = "--gpu-debug" ]; then
        EXTRA_JVM_ARGS="$EXTRA_JVM_ARGS -Dplutonium.gpudebug=true"
    fi
done

echo "==> Launching Plutonium (GPU)..."
mkdir -p "$GAME_DIR/logs"   # the JVM refuses to start if the gc.log directory is missing
cd "$SCRIPT_DIR"
"$JAVA8" \
    $EXTRA_JVM_ARGS \
    -XstartOnFirstThread \
    -Djava.awt.headless=true \
    -Xms"${HEAP:-2G}" -Xmx"${HEAP:-2G}" \
    -XX:+UseG1GC \
    -XX:MaxGCPauseMillis=20 \
    -XX:+UnlockExperimentalVMOptions \
    -XX:G1NewSizePercent=40 \
    -XX:G1MaxNewSizePercent=50 \
    -XX:G1HeapRegionSize=16M \
    -XX:G1ReservePercent=15 \
    -XX:G1MixedGCCountTarget=4 \
    -XX:InitiatingHeapOccupancyPercent=20 \
    -XX:SurvivorRatio=32 \
    -XX:MaxTenuringThreshold=1 \
    -XX:+AlwaysPreTouch \
    -XX:+ParallelRefProcEnabled \
    -XX:MaxDirectMemorySize=3G \
    -Xlog:gc:file="$GAME_DIR/logs/gc.log":time,uptime \
    -XX:+OptimizeStringConcat \
    -Dplutonium.discordRpc=true \
    -Dorg.lwjgl.librarypath="$NATIVES" \
    -cp "$CP_RUN" \
    -Djava.library.path="$NATIVES" \
    Start \
    --gameDir "$GAME_DIR"
