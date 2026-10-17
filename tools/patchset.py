#!/usr/bin/env python3
"""
Plutonium patch-set tooling: distribute Plutonium as patches against a vanilla
MCP 9.18 decompile of Minecraft 1.8.9, never as Minecraft source.

  setup  Everything, from nothing: base + verify + apply + deps.
  base   Build the vanilla base: fetch the 1.8.9 client jar, version json and
         libraries from Mojang, decompile with MCP 9.18, add the jar's assets.
  gen    Diff a Plutonium source tree against the base into a patch set:
         unified-diff patches for edited vanilla files, plus Plutonium's own files.
  apply  Rebuild a full source tree from the base + a patch set.
  check  Round-trip: gen + apply into temp dirs and require the result to be
         byte-identical to the source tree.
  deps   Fetch the runtime libraries, LWJGL 3 natives and game assets that
         launch.sh expects under jars/.
  release  Build a binary release: the classes Plutonium changes, reobfuscated and
         stored as deltas against Mojang's client jar (see tools/binpatch.py).
  install  Apply a binary release to the official client jar.

The base must be reproducible for patches to apply, so every patch set records
the SHA-256 of each base file it was generated against (base.sha256), and
apply refuses a base that doesn't match.

Nothing here needs installing beyond Python 3: MCP is fetched from its upstream
repository at a pinned commit and ported to Python 3 on the fly (tools/mcp-py3, kept as
patches so no MCP code is redistributed), and a Java 8 JDK is downloaded if none is found.
"""

import argparse
import concurrent.futures
import difflib
import hashlib
import http.client
import json
import os
import platform
import re
import shutil
import subprocess
import sys
import tarfile
import tempfile
import threading
import time
import urllib.error
import urllib.request
import zipfile

REPO_ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
DEFAULT_BUILD = os.path.join(REPO_ROOT, "build", "patchset")
DEFAULT_WORK = os.path.join(REPO_ROOT, "src", "minecraft")

MC_VERSION = "1.8.9"
VERSION_MANIFEST = "https://piston-meta.mojang.com/mc/game/version_manifest_v2.json"
CLIENT_SHA1 = "3870888a6c3d349d3771a3e9d16c9bf5e076b908"

PATCHSET_MARKER = "patchset.json"
# Some APIs (Adoptium) reject urllib's default agent string
USER_AGENT = "plutonium-patchset/1 (+https://github.com/qr0n/plutonium)"
WORKSPACE_MARKER = ".patchset-workspace"
FORMAT_VERSION = 1
CONTEXT_LINES = 3
NO_NEWLINE = "\\ No newline at end of file\n"
# Never part of a tree's content: OS junk and this tool's own ownership marker.
JUNK_NAMES = {".DS_Store", WORKSPACE_MARKER}
PRIVATE_BEGIN = "// loadout:begin"
PRIVATE_END = "// loadout:end"


def log(msg):
    print(msg, flush=True)


def die(msg):
    print("error: " + msg, file=sys.stderr, flush=True)
    sys.exit(1)


# --------------------------------------------------------------------------- files

def sha256_file(path):
    h = hashlib.sha256()
    with open(path, "rb") as f:
        for chunk in iter(lambda: f.read(1 << 20), b""):
            h.update(chunk)
    return h.hexdigest()


def sha1_file(path):
    h = hashlib.sha1()
    with open(path, "rb") as f:
        for chunk in iter(lambda: f.read(1 << 20), b""):
            h.update(chunk)
    return h.hexdigest()


def walk_files(root):
    """Relative '/'-separated paths of every regular file under root."""
    out = []
    for dirpath, dirnames, filenames in os.walk(root):
        dirnames.sort()
        for name in sorted(filenames):
            if name in JUNK_NAMES:
                continue
            rel = os.path.relpath(os.path.join(dirpath, name), root)
            out.append(rel.replace(os.sep, "/"))
    return out


def tracked_files(work):
    """Files of the work tree that git tracks; falls back to walking when not a repo."""
    try:
        res = subprocess.run(["git", "ls-files", "-z", "--", "."], cwd=work,
                             capture_output=True, check=True)
    except (OSError, subprocess.CalledProcessError):
        return walk_files(work)
    files = [p for p in res.stdout.decode("utf-8").split("\0") if p]
    # ls-files still lists files deleted from the working tree but not yet staged
    return sorted(p for p in files if os.path.isfile(os.path.join(work, p)))


def is_text(path):
    with open(path, "rb") as f:
        head = f.read(8192)
    return b"\0" not in head


def read_lines(path):
    with open(path, "rb") as f:
        return f.read().decode("utf-8", "surrogateescape").splitlines(keepends=True)


def write_lines(path, lines):
    os.makedirs(os.path.dirname(path), exist_ok=True)
    with open(path, "wb") as f:
        f.write("".join(lines).encode("utf-8", "surrogateescape"))


def copy_file(src, dst):
    os.makedirs(os.path.dirname(dst), exist_ok=True)
    shutil.copyfile(src, dst)


def reset_dir(path, marker):
    """Empty a generated directory; refuse to touch one this tool didn't create."""
    if os.path.exists(path):
        if os.listdir(path) and not os.path.exists(os.path.join(path, marker)):
            die("%s exists and wasn't created by this tool (no %s); refusing to overwrite" % (path, marker))
        shutil.rmtree(path)
    os.makedirs(path)


def read_manifest(path):
    manifest = {}
    with open(path, "r", encoding="utf-8") as f:
        for line in f:
            line = line.rstrip("\n")
            if line:
                digest, rel = line.split("  ", 1)
                manifest[rel] = digest
    return manifest


def write_manifest(path, root, files):
    with open(path, "w", encoding="utf-8") as f:
        for rel in files:
            f.write("%s  %s\n" % (sha256_file(os.path.join(root, rel)), rel))


def verify_base(base, manifest):
    """Return the list of problems between a base dir and the manifest it should match."""
    problems = []
    actual = set(walk_files(base))
    for rel, digest in manifest.items():
        path = os.path.join(base, rel)
        if rel not in actual:
            problems.append("missing   " + rel)
        elif sha256_file(path) != digest:
            problems.append("differs   " + rel)
    for rel in sorted(actual - set(manifest)):
        problems.append("unexpected " + rel)
    return problems


# --------------------------------------------------------------------------- base

def download(url, dest, sha1=None, quiet=False):
    if os.path.exists(dest) and (sha1 is None or sha1_file(dest) == sha1):
        return dest
    os.makedirs(os.path.dirname(dest), exist_ok=True)
    tmp = "%s.%d.part" % (dest, threading.get_ident())
    if not quiet:
        log("  fetch " + url)
    for attempt in range(5):
        try:
            req = urllib.request.Request(url, headers={"User-Agent": USER_AGENT})
            with urllib.request.urlopen(req, timeout=60) as resp, open(tmp, "wb") as f:
                shutil.copyfileobj(resp, f)
            break
        except (urllib.error.URLError, OSError, http.client.HTTPException) as e:
            if attempt == 4:
                die("download failed after 5 attempts: %s (%s)" % (url, e))
            time.sleep(2 ** attempt)   # CDNs drop bursts of parallel requests; back off
    if sha1 is not None and sha1_file(tmp) != sha1:
        os.remove(tmp)
        die("checksum mismatch for " + url)
    os.replace(tmp, dest)
    return dest


def fetch_version_json(cache):
    manifest_path = download(VERSION_MANIFEST, os.path.join(cache, "version_manifest_v2.json"))
    with open(manifest_path) as f:
        versions = json.load(f)["versions"]
    entry = next((v for v in versions if v["id"] == MC_VERSION), None)
    if entry is None:
        die("Mojang's version manifest has no %s" % MC_VERSION)
    return download(entry["url"], os.path.join(cache, MC_VERSION + ".json"), entry["sha1"])


def library_relpath(name, classifier=None):
    """libraries/<group>/<artifact>/<version>/<artifact>-<version>[-<classifier>].jar (MCP's layout)."""
    group, artifact, version = name.split(":")[:3]
    filename = "%s-%s%s.jar" % (artifact, version, "-" + classifier if classifier else "")
    return "/".join(["libraries", group.replace(".", "/"), artifact, version, filename])


def populate_jars(jars, version_json, cache):
    """Lay out jars/ the way MCP expects: the client jar, its json and every library."""
    with open(version_json) as f:
        meta = json.load(f)

    client = meta["downloads"]["client"]
    if client["sha1"] != CLIENT_SHA1:
        die("unexpected %s client jar sha1 %s" % (MC_VERSION, client["sha1"]))
    version_dir = os.path.join(jars, "versions", MC_VERSION)
    jar = download(client["url"], os.path.join(cache, "client-%s.jar" % MC_VERSION), client["sha1"])
    copy_file(jar, os.path.join(version_dir, MC_VERSION + ".jar"))
    copy_file(version_json, os.path.join(version_dir, MC_VERSION + ".json"))

    # Every artifact and native classifier, regardless of OS rules: fernflower only reads
    # classes, and natives jars carry none, so the decompile is the same on every platform.
    for lib in meta["libraries"]:
        downloads = lib.get("downloads", {})
        wanted = []
        if "artifact" in downloads:
            wanted.append((library_relpath(lib["name"]), downloads["artifact"]))
        for classifier, info in sorted(downloads.get("classifiers", {}).items()):
            wanted.append((library_relpath(lib["name"], classifier), info))
        for rel, info in wanted:
            cached = download(info["url"], os.path.join(cache, rel), info["sha1"])
            copy_file(cached, os.path.join(jars, rel))

    # MCP refuses to run without an extracted natives dir (it never loads them).
    natives_dir = os.path.join(version_dir, MC_VERSION + "-natives")
    os.makedirs(natives_dir, exist_ok=True)
    for lib in meta["libraries"]:
        for classifier in lib.get("downloads", {}).get("classifiers", {}):
            with zipfile.ZipFile(os.path.join(jars, library_relpath(lib["name"], classifier))) as z:
                for member in z.namelist():
                    if not member.endswith("/") and not member.startswith("META-INF/"):
                        z.extract(member, natives_dir)
    return jar


# MCP 9.18 with the 9.19 mappings for 1.8.9, as published in the upstream workspace this
# project grew out of. Only conf/ and runtime/ are fetched; each file is checked against
# its git blob hash from this exact commit's tree, so the commit id pins the content.
MCP_REPO = "dxxxxy/mcp1.8.9op"
MCP_COMMIT = "0499ce7c9dd831a922c05c8e4a3a0a26b3f6385e"
MCP_PORT = os.path.join(REPO_ROOT, "tools", "mcp-py3")


def git_blob_sha1(path):
    with open(path, "rb") as f:
        data = f.read()
    return hashlib.sha1(b"blob %d\0" % len(data) + data).hexdigest()


def fetch_mcp(cache):
    """Download upstream MCP's conf/ and runtime/ into the cache; returns its directory."""
    dest = os.path.join(cache, "mcp-" + MCP_COMMIT[:12])
    tree_path = os.path.join(cache, "mcp-tree-%s.json" % MCP_COMMIT[:12])
    download("https://api.github.com/repos/%s/git/trees/%s?recursive=1" % (MCP_REPO, MCP_COMMIT), tree_path)
    with open(tree_path) as f:
        tree = json.load(f)
    if tree.get("truncated"):
        die("GitHub truncated the MCP file listing; can't verify the download")
    entries = [e for e in tree["tree"] if e["type"] == "blob" and e["path"].split("/")[0] in ("conf", "runtime")]

    def fetch(entry):
        path = os.path.join(dest, entry["path"])
        if not (os.path.exists(path) and git_blob_sha1(path) == entry["sha"]):
            download("https://raw.githubusercontent.com/%s/%s/%s" % (MCP_REPO, MCP_COMMIT, entry["path"]),
                     path, quiet=True)
            if git_blob_sha1(path) != entry["sha"]:
                os.remove(path)
                die("MCP file %s doesn't match commit %s" % (entry["path"], MCP_COMMIT[:12]))
        if entry["mode"] == "100755":
            os.chmod(path, os.stat(path).st_mode | 0o755)

    log("  %d files from %s@%s" % (len(entries), MCP_REPO, MCP_COMMIT[:7]))
    with concurrent.futures.ThreadPoolExecutor(max_workers=16) as pool:
        list(pool.map(fetch, entries))
    return dest


def prepare_mcp(mcp_src, mcp_dir):
    """Copy MCP into the work dir and port its scripts to Python 3 (tools/mcp-py3)."""
    for sub in ("conf", "runtime"):
        if not os.path.isdir(os.path.join(mcp_src, sub)):
            die("%s has no %s/ (expected an MCP 9.18 install)" % (mcp_src, sub))
        shutil.copytree(os.path.join(mcp_src, sub), os.path.join(mcp_dir, sub))

    patch_root = os.path.join(MCP_PORT, "patches")
    for prel in walk_files(patch_root):
        rel = prel[:-len(".patch")]
        target = os.path.join(mcp_dir, rel)
        try:
            write_lines(target, apply_patch(read_lines(target), read_lines(os.path.join(patch_root, prel)), rel))
        except ValueError as e:
            die("porting MCP to Python 3 failed: %s (is this MCP 9.18/9.19?)" % e)
    files_root = os.path.join(MCP_PORT, "files")
    for rel in walk_files(files_root):
        copy_file(os.path.join(files_root, rel), os.path.join(mcp_dir, rel))

    # The bundled astyle loses its exec bit in some checkouts; without it MCP silently
    # skips reformatting and every file differs from the expected base.
    for name in os.listdir(os.path.join(mcp_dir, "runtime", "bin")):
        if name.startswith("astyle"):
            path = os.path.join(mcp_dir, "runtime", "bin", name)
            os.chmod(path, os.stat(path).st_mode | 0o755)


def is_jdk_home(home, version):
    javac = os.path.join(home, "bin", "javac.exe" if os.name == "nt" else "javac")
    if not os.path.isfile(javac):
        return False
    ver = subprocess.run([javac, "-version"], capture_output=True, text=True)
    out = ver.stderr + ver.stdout
    return ("1.8." in out) if version == 8 else re.search(r"javac %d(\.|\s|$)" % version, out) is not None


def is_java8_home(home):
    return is_jdk_home(home, 8)


def find_java8(arg):
    candidates = [arg, os.environ.get("JAVA8_HOME")]
    if sys.platform == "darwin":
        res = subprocess.run(["/usr/libexec/java_home", "-v", "1.8"], capture_output=True, text=True)
        if res.returncode == 0:
            candidates.append(res.stdout.strip())
        # java_home can answer with the legacy browser-plugin JRE, which has no javac
        jvms = "/Library/Java/JavaVirtualMachines"
        if os.path.isdir(jvms):
            candidates += [os.path.join(jvms, d, "Contents", "Home") for d in sorted(os.listdir(jvms))]
    candidates.append(os.environ.get("JAVA_HOME"))
    return next((h for h in candidates if h and is_java8_home(h)), None)


def find_jdk(version):
    """A system JDK of this major version, or one fetch_jdk already downloaded, or None."""
    if version == 8:
        found = find_java8(None)
        if found:
            return found
    elif sys.platform == "darwin":
        res = subprocess.run(["/usr/libexec/java_home", "-v", str(version)], capture_output=True, text=True)
        if res.returncode == 0 and is_jdk_home(res.stdout.strip(), version):
            return res.stdout.strip()
    return None


def fetch_java8(cache):
    return fetch_jdk(cache, 8)


def fetch_jdk(cache, version):
    """Download a Temurin JDK from Adoptium (sha256 from its API); returns its home."""
    osname = {"darwin": "mac", "win32": "windows"}.get(sys.platform, "linux")
    native = "aarch64" if platform.machine().lower() in ("arm64", "aarch64") else "x64"
    pkg = None
    # Temurin has no Java 8 for Apple Silicon; the x64 build runs under Rosetta, and Java 8
    # is only used to run MCP's tools during setup.
    for arch in dict.fromkeys([native, "x64"]):
        meta_path = os.path.join(cache, "temurin%d-%s-%s.json" % (version, osname, arch))
        download("https://api.adoptium.net/v3/assets/latest/%d/hotspot?architecture=%s&image_type=jdk&os=%s&vendor=eclipse"
                 % (version, arch, osname), meta_path)
        with open(meta_path) as f:
            releases = json.load(f)
        if releases:
            pkg = releases[0]["binary"]["package"]
            break
    if pkg is None:
        die("Adoptium has no Java %d JDK for %s; install one" % (version, osname))
    archive = os.path.join(cache, pkg["name"])
    download(pkg["link"], archive)
    if sha256_file(archive) != pkg["checksum"]:
        os.remove(archive)
        die("checksum mismatch for " + pkg["link"])
    dest = os.path.join(cache, "jdk%d-%s" % (version, pkg["name"].rsplit(".", 2)[0]))
    if not os.path.isdir(dest):
        log("  unpacking " + pkg["name"])
        if archive.endswith(".zip"):
            with zipfile.ZipFile(archive) as z:
                z.extractall(dest)
        else:
            with tarfile.open(archive) as t:
                t.extractall(dest)
    for root, dirs, _ in os.walk(dest):
        if is_jdk_home(root, version):
            return root
    die("no JDK found inside " + pkg["name"])


def cmd_base(args):
    build = os.path.abspath(args.build)
    cache = os.path.join(build, "cache")
    mcp_dir = os.path.join(build, "mcp")
    base = os.path.join(build, "base")
    java_home = find_java8(args.java_home)
    if java_home is None:
        log("== No Java 8 JDK found; fetching Temurin 8")
        java_home = fetch_java8(cache)

    log("== Fetching Minecraft %s from Mojang" % MC_VERSION)
    version_json = fetch_version_json(cache)
    if os.path.exists(mcp_dir):
        shutil.rmtree(mcp_dir)
    os.makedirs(mcp_dir)
    jar = populate_jars(os.path.join(mcp_dir, "jars"), version_json, cache)

    if args.mcp:
        mcp_src = os.path.abspath(args.mcp)
    else:
        log("== Fetching MCP")
        mcp_src = fetch_mcp(cache)
    log("== Decompiling with MCP 9.18 (about a minute)")
    prepare_mcp(mcp_src, mcp_dir)
    env = dict(os.environ, JAVA_HOME=java_home,
               PATH=os.path.join(java_home, "bin") + os.pathsep + os.environ.get("PATH", ""))
    with open(os.path.join(build, "decompile.log"), "w") as logf:
        res = subprocess.run([sys.executable, "runtime/decompile.py", "--client", "--norecompile",
                              "--nocomments", "--nocopy", "-w", os.path.join(mcp_dir, "jars")],
                             cwd=mcp_dir, env=env, stdout=logf, stderr=subprocess.STDOUT)
    if res.returncode != 0 or not os.path.isdir(os.path.join(mcp_dir, "src", "minecraft")):
        die("MCP decompile failed; see %s" % os.path.join(build, "decompile.log"))
    with open(os.path.join(build, "decompile.log")) as f:
        if "reformating disabled" in f.read():
            die("MCP couldn't run astyle, so the output won't match; see decompile.log")

    log("== Assembling base")
    reset_dir(base, WORKSPACE_MARKER)
    src = os.path.join(mcp_dir, "src", "minecraft")
    for rel in walk_files(src):
        dst = os.path.join(base, rel)
        if rel.endswith(".java"):
            # normalise line endings so a Windows decompile hashes the same
            write_lines(dst, [l.replace("\r\n", "\n") for l in read_lines(os.path.join(src, rel))])
        else:
            copy_file(os.path.join(src, rel), dst)
    with zipfile.ZipFile(jar) as z:
        for info in z.infolist():
            if info.is_dir() or not (info.filename.startswith("assets/") or info.filename == "pack.png"):
                continue
            dst = os.path.join(base, info.filename)
            os.makedirs(os.path.dirname(dst), exist_ok=True)
            with z.open(info) as zf, open(dst, "wb") as f:
                shutil.copyfileobj(zf, f)
    open(os.path.join(base, WORKSPACE_MARKER), "w").close()

    files = walk_files(base)
    write_manifest(os.path.join(build, "base.sha256"), base, files)
    log("base ready: %d files in %s" % (len(files), base))

    if args.verify:
        problems = verify_base(base, read_manifest(args.verify))
        if problems:
            die("base doesn't match %s (%d problems), e.g.\n  %s"
                % (args.verify, len(problems), "\n  ".join(problems[:10])))
        log("base matches %s" % args.verify)


# --------------------------------------------------------------------------- deps

MAVEN = "https://repo1.maven.org/maven2"
LWJGL_VERSION = "3.3.3"
LWJGL_MODULES = ["lwjgl", "lwjgl-glfw", "lwjgl-openal", "lwjgl-opengl", "lwjgl-stb", "lwjgl-vulkan"]
# (relative path under jars/libraries, url, sha1) for the runtime jars Mojang doesn't ship
EXTRA_LIBS = [
    ("org/json/json/20210307/json-20210307.jar",
     MAVEN + "/org/json/json/20210307/json-20210307.jar",
     "528c8df3757e8bcf151817b1d7b9b434f6aa08d5"),
    ("club/minnced/java-discord-rpc/2.0.1/java-discord-rpc-2.0.1-all.jar",
     "https://github.com/MinnDevelopment/java-discord-rpc/releases/download/v2.0.1/java-discord-rpc-2.0.1-all.jar",
     "d5d6adb11d48813b368d6265c34bfa8f936b97d7"),
]


# LWJGL 3.3.3 bundles MoltenVK 1.2.5, which lacks VK_EXT_extended_dynamic_state (the
# renderer requires it), so macOS gets Khronos's MoltenVK 1.4.1 over the top.
MOLTENVK_URL = "https://github.com/KhronosGroup/MoltenVK/releases/download/v1.4.1/MoltenVK-macos.tar"
MOLTENVK_TAR_SHA256 = "5ea0c259df7ded9a275444820f09cced54d6e5a7c7a31d262de62a5cdb7e15cf"
MOLTENVK_DYLIB = "MoltenVK/MoltenVK/dynamic/dylib/macOS/libMoltenVK.dylib"
MOLTENVK_DYLIB_SHA256 = "d3ee87b2d98c0b7d5db7bcd1e51b010fe998f755f26c09a83768275499b7a398"


def install_moltenvk(cache, natives):
    tar_path = os.path.join(cache, "MoltenVK-macos-1.4.1.tar")
    download(MOLTENVK_URL, tar_path)
    if sha256_file(tar_path) != MOLTENVK_TAR_SHA256:
        os.remove(tar_path)
        die("checksum mismatch for " + MOLTENVK_URL)
    with tarfile.open(tar_path) as tar:
        src = tar.extractfile(MOLTENVK_DYLIB)
        dest = os.path.join(natives, "libMoltenVK.dylib")
        with open(dest + ".part", "wb") as f:
            shutil.copyfileobj(src, f)
    if sha256_file(dest + ".part") != MOLTENVK_DYLIB_SHA256:
        die("unexpected libMoltenVK.dylib inside " + MOLTENVK_URL)
    os.replace(dest + ".part", dest)


def lwjgl_classifiers():
    machine = platform.machine().lower()
    arm = machine in ("arm64", "aarch64")
    if sys.platform == "darwin":
        return ["natives-macos-arm64" if arm else "natives-macos"]
    if os.name == "nt":
        return ["natives-windows-arm64" if arm else "natives-windows"]
    return ["natives-linux-arm64" if arm else "natives-linux"]


def maven_sha1(url):
    with urllib.request.urlopen(url + ".sha1") as resp:
        return resp.read().decode().split()[0]


def cmd_deps(args):
    """Fetch what launch.sh needs next to the source tree: libraries, natives and assets."""
    build = os.path.abspath(args.build)
    cache = os.path.join(build, "cache")
    game = os.path.abspath(args.game_dir)
    libs = os.path.join(game, "libraries")
    natives = os.path.join(game, "versions", MC_VERSION, MC_VERSION + "-natives")
    with open(fetch_version_json(cache)) as f:
        meta = json.load(f)

    log("== Minecraft libraries")
    for lib in meta["libraries"]:
        group = lib["name"].split(":")[0]
        # LWJGL 2 and JInput are replaced by LWJGL 3; launch.sh never loads them
        if group in ("org.lwjgl.lwjgl", "net.java.jinput") or "artifact" not in lib.get("downloads", {}):
            continue
        rel = library_relpath(lib["name"])
        info = lib["downloads"]["artifact"]
        copy_file(download(info["url"], os.path.join(cache, rel), info["sha1"]), os.path.join(game, rel))

    log("== LWJGL %s" % LWJGL_VERSION)
    os.makedirs(natives, exist_ok=True)
    for module in LWJGL_MODULES:
        for classifier in [None] + lwjgl_classifiers():
            name = "%s-%s%s.jar" % (module, LWJGL_VERSION, "-" + classifier if classifier else "")
            url = "%s/org/lwjgl/%s/%s/%s" % (MAVEN, module, LWJGL_VERSION, name)
            try:
                sha1 = maven_sha1(url)
            except urllib.error.HTTPError:
                continue   # e.g. lwjgl-vulkan only has natives (MoltenVK) on macOS
            jar = download(url, os.path.join(cache, "lwjgl3", name), sha1)
            copy_file(jar, os.path.join(libs, "lwjgl3", name))
            if classifier:
                with zipfile.ZipFile(jar) as z:
                    for member in z.namelist():
                        base_name = member.rsplit("/", 1)[-1]
                        if base_name.endswith((".dylib", ".so", ".dll")):
                            with z.open(member) as src, open(os.path.join(natives, base_name), "wb") as dst:
                                shutil.copyfileobj(src, dst)

    if sys.platform == "darwin":
        log("== MoltenVK 1.4.1")
        install_moltenvk(cache, natives)

    log("== Other libraries")
    for rel, url, sha1 in EXTRA_LIBS:
        copy_file(download(url, os.path.join(cache, "extra", rel), sha1), os.path.join(libs, rel))

    if args.no_assets:
        log("skipping assets (--no-assets)")
        return
    log("== Assets (first run downloads a few hundred MB)")
    index_info = meta["assetIndex"]
    index_path = os.path.join(game, "assets", "indexes", index_info["id"] + ".json")
    download(index_info["url"], index_path, index_info["sha1"])
    with open(index_path) as f:
        objects = json.load(f)["objects"]
    missing = sorted({obj["hash"] for obj in objects.values()
                      if not os.path.exists(os.path.join(game, "assets", "objects", obj["hash"][:2], obj["hash"]))})
    log("  %d of %d assets to fetch" % (len(missing), len(objects)))

    def fetch(digest):
        dest = os.path.join(game, "assets", "objects", digest[:2], digest)
        download("https://resources.download.minecraft.net/%s/%s" % (digest[:2], digest), dest, digest, quiet=True)

    # Hundreds of small files: latency-bound, so fetch them concurrently.
    with concurrent.futures.ThreadPoolExecutor(max_workers=16) as pool:
        for i, _ in enumerate(pool.map(fetch, missing)):
            if (i + 1) % 100 == 0 or i + 1 == len(missing):
                log("  %d/%d assets" % (i + 1, len(missing)))
    log("deps ready in %s" % game)


# --------------------------------------------------------------------------- gen

def load_exclusions(path):
    with open(path, encoding="utf-8") as f:
        return [l.strip() for l in f if l.strip() and not l.lstrip().startswith("#")]


def is_excluded(rel, exclusions):
    return any(rel.startswith(e) if e.endswith("/") else rel == e for e in exclusions)


def strip_private(lines, rel):
    """Drop every loadout:begin..loadout:end region (markers included)."""
    out, depth = [], 0
    for line in lines:
        mark = line.strip()
        if mark == PRIVATE_BEGIN:
            depth += 1
        elif mark == PRIVATE_END:
            if depth == 0:
                die("%s: %s without %s" % (rel, PRIVATE_END, PRIVATE_BEGIN))
            depth -= 1
        elif depth == 0:
            out.append(line)
    if depth:
        die("%s: unterminated %s" % (rel, PRIVATE_BEGIN))
    return out


class WorkTree:
    """The source tree as gen sees it: optionally minus private files and regions."""

    def __init__(self, root, files, exclusions=None):
        self.root = root
        self.public = exclusions is not None
        self.files = [f for f in files if not (self.public and is_excluded(f, exclusions))]

    def read(self, rel):
        path = os.path.join(self.root, rel)
        with open(path, "rb") as f:
            data = f.read()
        if self.public and rel.endswith(".java") and PRIVATE_BEGIN.encode() in data:
            lines = strip_private(data.decode("utf-8", "surrogateescape").splitlines(keepends=True), rel)
            data = "".join(lines).encode("utf-8", "surrogateescape")
        return data


def make_patch(rel, old_lines, new_lines):
    diff = difflib.unified_diff(old_lines, new_lines, "a/" + rel, "b/" + rel, n=CONTEXT_LINES)
    out = []
    for line in diff:
        if line.endswith("\n"):
            out.append(line)
        else:
            out.append(line + "\n")
            out.append(NO_NEWLINE)
    return out


def is_editor_backup(rel):
    name = rel.rsplit("/", 1)[-1]
    return name.endswith(("~", "#")) or name.startswith(".#")


def generate(work, base, out, files=None, exclusions=None):
    """Write a patch set for work vs base into out. Returns (patched, added, deleted) counts."""
    tree = WorkTree(work, files if files is not None else tracked_files(work), exclusions)
    work_files = tree.files
    # Editor backups of vanilla files are full Minecraft source under another name;
    # exporting them as "own files" would publish exactly what the patch set exists to avoid.
    backups = [rel for rel in work_files if is_editor_backup(rel)]
    if backups:
        die("editor backup files in the tree (delete them first):\n  " + "\n  ".join(backups))
    base_files = walk_files(base)
    base_set = set(base_files)

    reset_dir(out, PATCHSET_MARKER)
    patched = added = 0
    binary_edits = []
    for rel in work_files:
        data = tree.read(rel)
        if rel in base_set:
            ref = os.path.join(base, rel)
            with open(ref, "rb") as b:
                ref_data = b.read()
            if data == ref_data:
                continue
            if b"\0" in data[:8192] or not is_text(ref):
                binary_edits.append(rel)
                continue
            new_lines = data.decode("utf-8", "surrogateescape").splitlines(keepends=True)
            write_lines(os.path.join(out, "patches", rel + ".patch"), make_patch(rel, read_lines(ref), new_lines))
            patched += 1
        else:
            dst = os.path.join(out, "files", rel)
            os.makedirs(os.path.dirname(dst), exist_ok=True)
            with open(dst, "wb") as f:
                f.write(data)
            added += 1
    if binary_edits:
        die("binary vanilla files were modified (can't be patched): " + ", ".join(binary_edits[:10]))

    work_set = set(work_files)
    deleted = [rel for rel in base_files if rel not in work_set]
    with open(os.path.join(out, "deleted.txt"), "w", encoding="utf-8") as f:
        f.writelines(rel + "\n" for rel in deleted)

    write_manifest(os.path.join(out, "base.sha256"), base, base_files)
    with open(os.path.join(out, PATCHSET_MARKER), "w") as f:
        json.dump({"format": FORMAT_VERSION, "minecraft": MC_VERSION, "mcp": "9.18",
                   "patches": patched, "files": added, "deleted": len(deleted)}, f, indent=2)
        f.write("\n")
    return patched, added, len(deleted)


def cmd_gen(args):
    work = os.path.abspath(args.work)
    base = os.path.abspath(args.base)
    exclusions = load_exclusions(args.public) if args.public else None
    patched, added, deleted = generate(work, base, os.path.abspath(args.out), exclusions=exclusions)
    log("%spatch set: %d patches, %d own files, %d deletions -> %s"
        % ("public " if exclusions is not None else "", patched, added, deleted, args.out))


# --------------------------------------------------------------------------- apply

HUNK_RE = re.compile(r"^@@ -(\d+)(?:,(\d+))? \+(\d+)(?:,(\d+))? @@")


def parse_patch(lines):
    """Yield hunks as (old_start, old_lines, new_lines) from a unified diff."""
    hunks = []
    i = 0
    while i < len(lines) and not lines[i].startswith("@@"):
        i += 1
    while i < len(lines):
        m = HUNK_RE.match(lines[i])
        if not m:
            raise ValueError("bad hunk header: %r" % lines[i])
        old_start = int(m.group(1))
        old_count = int(m.group(2) or "1")
        new_count = int(m.group(4) or "1")
        i += 1
        old, new = [], []
        while i < len(lines) and (len(old) < old_count or len(new) < new_count or lines[i] == NO_NEWLINE):
            line = lines[i]
            if line == NO_NEWLINE:
                # strip the newline from the line just before the marker
                for side in (old, new):
                    if side and side[-1][1] == i - 1:
                        side[-1] = (side[-1][0][:-1], side[-1][1])
            elif line[0] == " ":
                old.append((line[1:], i))
                new.append((line[1:], i))
            elif line[0] == "-":
                old.append((line[1:], i))
            elif line[0] == "+":
                new.append((line[1:], i))
            else:
                raise ValueError("bad hunk line: %r" % line)
            i += 1
        hunks.append((old_start, [l for l, _ in old], [l for l, _ in new]))
    return hunks


def apply_patch(target_lines, patch_lines, rel):
    """Apply hunks strictly: context must match exactly where the patch says it is."""
    result = []
    pos = 0
    for old_start, old, new in parse_patch(patch_lines):
        start = old_start - 1 if old else old_start
        if start < pos or target_lines[start:start + len(old)] != old:
            raise ValueError("%s: hunk at line %d doesn't apply (base differs)" % (rel, old_start))
        result.extend(target_lines[pos:start])
        result.extend(new)
        pos = start + len(old)
    result.extend(target_lines[pos:])
    return result


def materialise(patchset, base, out):
    """Rebuild a full tree in out from base + patchset. Returns the number of files written."""
    reset_dir(out, WORKSPACE_MARKER)
    with open(os.path.join(patchset, "deleted.txt"), encoding="utf-8") as f:
        deleted = {line.rstrip("\n") for line in f if line.strip()}

    for rel in walk_files(base):
        if rel not in deleted:
            copy_file(os.path.join(base, rel), os.path.join(out, rel))

    patch_root = os.path.join(patchset, "patches")
    failures = []
    if os.path.isdir(patch_root):
        for prel in walk_files(patch_root):
            rel = prel[:-len(".patch")]
            target = os.path.join(out, rel)
            try:
                write_lines(target, apply_patch(read_lines(target), read_lines(os.path.join(patch_root, prel)), rel))
            except (ValueError, OSError) as e:
                failures.append(str(e))
    if failures:
        die("%d patches failed:\n  %s" % (len(failures), "\n  ".join(failures[:20])))

    files_root = os.path.join(patchset, "files")
    if os.path.isdir(files_root):
        for rel in walk_files(files_root):
            copy_file(os.path.join(files_root, rel), os.path.join(out, rel))

    open(os.path.join(out, WORKSPACE_MARKER), "w").close()
    return len(walk_files(out))


def check_base(base, patchset, force):
    problems = verify_base(base, read_manifest(os.path.join(patchset, "base.sha256")))
    if problems:
        msg = ("base at %s doesn't match the one this patch set was made from (%d problems), e.g.\n  %s"
               % (base, len(problems), "\n  ".join(problems[:10])))
        if not force:
            die(msg + "\nrebuild it with `patchset.py base`, or pass --force to try anyway")
        log("warning: " + msg)


def cmd_apply(args):
    patchset = os.path.abspath(args.patchset)
    base = os.path.abspath(args.base)
    if not os.path.exists(os.path.join(patchset, PATCHSET_MARKER)):
        die("%s is not a patch set (no %s)" % (patchset, PATCHSET_MARKER))
    check_base(base, patchset, args.force)
    count = materialise(patchset, base, os.path.abspath(args.out))
    log("applied: %d files in %s" % (count, args.out))


# --------------------------------------------------------------------------- check

def cmd_check(args):
    work = os.path.abspath(args.work)
    base = os.path.abspath(args.base)
    files = tracked_files(work)
    with tempfile.TemporaryDirectory(prefix="patchset-check-") as tmp:
        patchset = os.path.join(tmp, "patchset")
        rebuilt = os.path.join(tmp, "rebuilt")
        patched, added, deleted = generate(work, base, patchset, files)
        materialise(patchset, base, rebuilt)

        rebuilt_files = set(walk_files(rebuilt))
        problems = ["missing   " + rel for rel in files if rel not in rebuilt_files]
        problems += ["extra     " + rel for rel in sorted(rebuilt_files - set(files))]
        for rel in files:
            if rel in rebuilt_files and sha256_file(os.path.join(work, rel)) != sha256_file(os.path.join(rebuilt, rel)):
                problems.append("differs   " + rel)
        if problems:
            die("round trip failed (%d problems):\n  %s" % (len(problems), "\n  ".join(problems[:20])))
    log("round trip OK: %d files reproduced exactly from base + %d patches, %d own files, %d deletions"
        % (len(files), patched, added, deleted))


def cmd_setup(args):
    """base -> verify against the patch set -> apply -> deps."""
    patchset = os.path.abspath(args.patchset)
    if not os.path.exists(os.path.join(patchset, PATCHSET_MARKER)):
        die("%s is not a patch set (no %s)" % (patchset, PATCHSET_MARKER))
    args.verify = os.path.join(patchset, "base.sha256")
    cmd_base(args)
    log("== Applying patch set")
    base = os.path.join(os.path.abspath(args.build), "base")
    count = materialise(patchset, base, os.path.abspath(args.out))
    log("applied: %d files in %s" % (count, args.out))
    cmd_deps(args)

    # launch.sh compiles with Java 8's javac and runs on Java 21; record where they are.
    cache = os.path.join(os.path.abspath(args.build), "cache")
    homes = {}
    for version in (8, 21):
        homes[version] = find_jdk(version)
        if homes[version] is None:
            log("== No Java %d found; fetching Temurin %d" % (version, version))
            homes[version] = fetch_jdk(cache, version)
    env_file = os.path.join(os.path.abspath(args.game_dir), "jdk.env")
    with open(env_file, "w") as f:
        f.write('JAVAC="%s"\nJAVA="%s"\n' % (os.path.join(homes[8], "bin", "javac"), os.path.join(homes[21], "bin", "java")))
    log("Java 8 (compile): %s\nJava 21 (run):    %s" % (homes[8], homes[21]))
    log("\nDone. Build and run with:  ./launch.sh --compile")


def main():
    p = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    sub = p.add_subparsers(dest="command", required=True)
    base_default = os.path.join(DEFAULT_BUILD, "base")

    b = sub.add_parser("base", help="build the vanilla MCP 9.18 base")
    b.add_argument("--build", default=DEFAULT_BUILD, help="working/cache directory (default build/patchset)")
    b.add_argument("--mcp", help="use a local MCP 9.18 (conf/ and runtime/) instead of downloading it")
    b.add_argument("--java-home", help="Java 8 JDK home for MCP")
    b.add_argument("--verify", metavar="BASE_SHA256", help="require the base to match this manifest")
    b.set_defaults(func=cmd_base)

    d = sub.add_parser("deps", help="fetch runtime libraries, natives and assets for launch.sh")
    d.add_argument("--build", default=DEFAULT_BUILD, help="download cache directory (default build/patchset)")
    d.add_argument("--game-dir", default=os.path.join(REPO_ROOT, "jars"), help="game directory (default jars/)")
    d.add_argument("--no-assets", action="store_true", help="skip the sound/language asset download")
    d.set_defaults(func=cmd_deps)

    st = sub.add_parser("setup", help="one command: build base, apply the patch set, fetch deps")
    st.add_argument("--patchset", default=os.path.join(REPO_ROOT, "patchset"))
    st.add_argument("--out", default=DEFAULT_WORK, help="source tree to create (default src/minecraft)")
    st.add_argument("--build", default=DEFAULT_BUILD)
    st.add_argument("--mcp", help="use a local MCP 9.18 instead of downloading it")
    st.add_argument("--java-home", help="Java 8 JDK home (downloaded if omitted and none is found)")
    st.add_argument("--game-dir", default=os.path.join(REPO_ROOT, "jars"))
    st.add_argument("--no-assets", action="store_true")
    st.set_defaults(func=cmd_setup)

    g = sub.add_parser("gen", help="generate a patch set from a source tree")
    g.add_argument("--work", default=DEFAULT_WORK, help="Plutonium source tree (default src/minecraft)")
    g.add_argument("--base", default=base_default)
    g.add_argument("--out", required=True, help="patch set output directory")
    g.add_argument("--public", metavar="EXCLUDE_LIST",
                   help="public export: skip the listed paths and strip loadout:begin/end regions")
    g.set_defaults(func=cmd_gen)

    a = sub.add_parser("apply", help="rebuild a source tree from base + patch set")
    a.add_argument("--patchset", required=True)
    a.add_argument("--base", default=base_default)
    a.add_argument("--out", required=True, help="output source tree")
    a.add_argument("--force", action="store_true", help="apply even if the base checksums differ")
    a.set_defaults(func=cmd_apply)

    c = sub.add_parser("check", help="verify gen + apply reproduces the source tree exactly")
    c.add_argument("--work", default=DEFAULT_WORK)
    c.add_argument("--base", default=base_default)
    c.set_defaults(func=cmd_check)

    r = sub.add_parser("release", help="build a binary release (deltas against Mojang's client jar)")
    r.add_argument("--work", default=DEFAULT_WORK, help="source tree to build (default src/minecraft)")
    r.add_argument("--public", metavar="EXCLUDE_LIST", help="build the public export of the tree instead")
    r.add_argument("--build", default=DEFAULT_BUILD, help="the build dir `base` used (default build/patchset)")
    r.add_argument("--game-dir", default=os.path.join(REPO_ROOT, "jars"), help="where `deps` put the runtime libraries")
    r.add_argument("--java-home", help="Java 8 JDK home (downloaded if omitted and none is found)")
    r.add_argument("--label", help="release name (default: git short sha)")
    r.add_argument("--out", help="output zip (default build/patchset/release/plutonium-<mc>-<label>.zip)")
    r.set_defaults(func=lambda a: binpatch().cmd_release(a))

    i = sub.add_parser("install", help="apply a binary release to the official client jar")
    i.add_argument("release", help="release zip")
    i.add_argument("--out", required=True, help="client jar to write")
    i.add_argument("--jar", help="official %s client jar (downloaded from Mojang if omitted)" % MC_VERSION)
    i.add_argument("--build", default=DEFAULT_BUILD, help="download cache directory (default build/patchset)")
    i.set_defaults(func=lambda a: binpatch().cmd_install(a))

    args = p.parse_args()
    args.func(args)


def binpatch():
    # imported lazily: it imports this module
    sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
    import binpatch as module
    return module


if __name__ == "__main__":
    main()
