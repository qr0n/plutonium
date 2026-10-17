# Plutonium

A Minecraft 1.8.9 client with a Vulkan renderer (MoltenVK on macOS), a module system,
a ClickGUI and a pile of HUD mods.

This repository contains **no Minecraft source code**. Plutonium is distributed the way
Forge and Paper distribute their changes: as patches against a decompile you produce
yourself from your own copy of the game, plus the files Plutonium adds.

```
patchset/
  patches/      unified diffs against the vanilla MCP 9.18 decompile
  files/        Plutonium's own sources and assets
  base.sha256   checksum of every file of the vanilla base the patches expect
tools/patchset.py   builds the base, applies the patches, fetches runtime deps
```

## Play

Download the launcher from [Releases](https://github.com/qr0n/plutonium/releases). On first
launch it downloads Minecraft 1.8.9 from Mojang, a Java 21 runtime and the latest Plutonium
release, then patches the official client jar locally. Releases contain only Plutonium's
changes, as binary deltas against Mojang's jar (`tools/patchset.py release` builds one,
`tools/patchset.py install` applies one).

## Requirements

- **Python 3.8+**. That's the only thing to install; the tooling uses just the standard library.
- Vulkan: macOS on Apple Silicon (MoltenVK is fetched for you), or a Windows GPU driver with
  `VK_KHR_dynamic_rendering`, `VK_EXT_extended_dynamic_state` and `VK_KHR_push_descriptor`.
  See [WINDOWS.md](WINDOWS.md).

## Build

```sh
python3 tools/patchset.py setup   # about 3 minutes on first run
./launch.sh --compile
```

`setup` does everything:

1. Downloads the 1.8.9 client jar, version json and libraries from Mojang (checksum-verified).
2. Downloads MCP 9.18 (with the 9.19 mappings for 1.8.9) from
   [dxxxxy/mcp1.8.9op](https://github.com/dxxxxy/mcp1.8.9op) at a pinned commit, and ports its
   scripts to Python 3 locally ([tools/mcp-py3](tools/mcp-py3), kept as patches so no MCP code
   is redistributed here).
3. Decompiles, then refuses to continue unless the result matches `patchset/base.sha256`
   byte-for-byte.
4. Applies the patch set into `src/minecraft`.
5. Fetches runtime libraries, LWJGL 3 natives, MoltenVK 1.4.1 (macOS) and the game assets into
   `jars/`.
6. Uses your installed Java 8 (to compile) and Java 21 (to run), or downloads Temurin builds
   if they're missing. On Apple Silicon the Java 8 build is x64 and runs under Rosetta.

The individual steps are also available as `base`, `apply` and `deps`.

**Linux:** MCP formats code with the system `astyle`, and only astyle 2.02 reproduces the
expected base; other versions fail the checksum step.

## Making changes

Edit `src/minecraft` like any source tree, then turn it back into a patch set:

```sh
python3 tools/patchset.py gen --work src/minecraft --out patchset
```

`gen` diffs the git-tracked files of `src/minecraft` against the base, so keep that tree
in its own git repository (or commit your changes) before regenerating. `check` runs a
gen + apply round trip and fails unless it reproduces the tree byte-for-byte.
