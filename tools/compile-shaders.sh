#!/bin/bash
# Compile Vulkan GLSL sources to SPIR-V. The .spv artifacts are committed so the
# javac-only build never needs a shader compiler; this script is a one-time dev
# tool (brew install glslang) run only when a .vert/.frag changes.
set -e

DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)/src/minecraft/assets/minecraft/shaders/vk"

if ! command -v glslangValidator >/dev/null 2>&1; then
    echo "glslangValidator not found — install with: brew install glslang" >&2
    exit 1
fi

for f in "$DIR"/*.vert "$DIR"/*.frag; do
    [ -e "$f" ] || continue
    echo "==> $(basename "$f")"
    glslangValidator -V --target-env vulkan1.1 -I"$DIR" -o "$f.spv" "$f"
done
echo "==> SPIR-V up to date."
