"""
Binary releases: Plutonium as patches against Mojang's own 1.8.9 client jar.

`patchset.py release` compiles a source tree, reobfuscates it back to Mojang's names with
MCP, and keeps only the classes that actually changed. Each one ships as a delta against
the vanilla class it replaces; classes and resources that are Plutonium's own ship as-is.
`patchset.py install` applies a release to the official client jar (downloaded from
Mojang and checked by SHA-1) to produce a runnable client. A release therefore holds no
Mojang class files, and players never need MCP, Python or a JDK; the launcher does what
`install` does.

Changed classes are found the way MCP's reobfuscator does: the vanilla base and the work
tree are compiled with the same javac and flags, and a class whose bytes come out
identical in both is left as Mojang shipped it. Patching a class always replaces its whole
nest (outer class plus inner and anonymous classes), since javac numbers anonymous classes
and synthetic accessors per nest.

Release layout (a zip):
  release.json     format, Minecraft version, client jar SHA-1, main class, environment,
                   runtime (Java version, extra libraries and natives a launcher must
                   fetch), and per-entry SHA-1s for everything below
  patches/<path>   delta (see below) turning the vanilla entry into Plutonium's
  files/<path>     entries with no vanilla counterpart, stored whole
  delete           vanilla entries to drop (inner classes a patched nest no longer has)

Delta format: b"PLDELTA1", then a zlib stream of: varint target length, then ops until the
target is complete. Op 0 = copy (varint source offset, varint length); op 1 = insert
(varint length, bytes). Applying it needs nothing beyond zlib.
"""

import hashlib
import io
import json
import os
import shutil
import subprocess
import sys
import tempfile
import urllib.error
import zipfile
import zlib

import patchset as ps

FORMAT_VERSION = 1
DELTA_MAGIC = b"PLDELTA1"
MAIN_CLASS = "net.minecraft.client.main.Main"
# Metal fast math lets the shader compiler contract mul+add into FMA, which breaks exact
# depth ties: batched text drawn at the same depth as the quad under it is rejected and
# every string in the game disappears. MoltenVK only reads this from the environment.
RUNTIME_ENV = {"MVK_CONFIG_FAST_MATH_ENABLED": "0"}
JAVAC_FLAGS = ["-nowarn", "-g", "-source", "1.8", "-target", "1.8", "-encoding", "UTF-8"]
# Vanilla sources MCP decompiles into something javac rejects. The fix has to be applied
# to the vanilla compile too, or the class would count as changed by Plutonium.
VANILLA_COMPILE_FIXES = {
    "net/minecraft/world/gen/structure/StructureMineshaftPieces.java": [
        ("            int k;\n\n            for (lvt_5_1_ = 0;", "            int k = 0;\n\n            for (int lvt_5_1_ = 0;"),
    ],
}
# MCP's dev launcher, compiled from the tree but never part of a release
NOT_SHIPPED = {"Start.class"}


# --------------------------------------------------------------------------- delta

MIN_MATCH = 8


def _varint(n, out):
    while n >= 0x80:
        out.append((n & 0x7F) | 0x80)
        n >>= 7
    out.append(n)


def _read_varint(buf, pos):
    n = shift = 0
    while True:
        b = buf[pos]
        pos += 1
        n |= (b & 0x7F) << shift
        if b < 0x80:
            return n, pos
        shift += 7


def make_delta(old, new):
    """Greedy copy/insert delta from old to new: an index of every 8-byte window of old,
    longest match among the last few candidates, literals for everything else."""
    index = {}
    for i in range(len(old) - MIN_MATCH + 1):
        index.setdefault(old[i:i + MIN_MATCH], []).append(i)
    ops = bytearray()
    _varint(len(new), ops)
    literal = bytearray()

    def flush_literal():
        if literal:
            ops.append(1)
            _varint(len(literal), ops)
            ops.extend(literal)
            literal.clear()

    i = 0
    while i < len(new):
        best_len = best_off = 0
        for off in index.get(new[i:i + MIN_MATCH], ())[-8:]:
            n = MIN_MATCH
            while new[i + n:i + n + 64] == old[off + n:off + n + 64] and i + n + 64 <= len(new):
                n += 64
            while i + n < len(new) and off + n < len(old) and new[i + n] == old[off + n]:
                n += 1
            if n > best_len:
                best_len, best_off = n, off
        if best_len >= MIN_MATCH:
            flush_literal()
            ops.append(0)
            _varint(best_off, ops)
            _varint(best_len, ops)
            i += best_len
        else:
            literal.append(new[i])
            i += 1
    flush_literal()
    return DELTA_MAGIC + zlib.compress(bytes(ops), 9)


def apply_delta(old, delta):
    if not delta.startswith(DELTA_MAGIC):
        raise ValueError("not a Plutonium delta")
    ops = zlib.decompress(delta[len(DELTA_MAGIC):])
    size, pos = _read_varint(ops, 0)
    out = bytearray()
    while len(out) < size:
        op = ops[pos]
        if op == 0:
            off, pos = _read_varint(ops, pos + 1)
            n, pos = _read_varint(ops, pos)
            if off + n > len(old):
                raise ValueError("delta copies past the end of its source")
            out += old[off:off + n]
        elif op == 1:
            n, pos = _read_varint(ops, pos + 1)
            out += ops[pos:pos + n]
            pos += n
        else:
            raise ValueError("bad delta op %d" % op)
    if len(out) != size:
        raise ValueError("delta produced %d bytes, expected %d" % (len(out), size))
    return bytes(out)


def sha1(data):
    return hashlib.sha1(data).hexdigest()


# --------------------------------------------------------------------------- build

def javac(java_home, src_root, classpath, out):
    """Compile every .java under src_root into out (emptied first)."""
    if os.path.exists(out):
        shutil.rmtree(out)
    os.makedirs(out)
    sources = [os.path.join(src_root, rel) for rel in ps.walk_files(src_root) if rel.endswith(".java")]
    with tempfile.NamedTemporaryFile("w", suffix=".txt", delete=False) as f:
        f.write("\n".join('"%s"' % s.replace("\\", "/") for s in sources))
        argfile = f.name
    try:
        res = subprocess.run([os.path.join(java_home, "bin", "javac")] + JAVAC_FLAGS
                             + ["-cp", os.pathsep.join(classpath), "-d", out, "@" + argfile],
                             capture_output=True, text=True)
    finally:
        os.remove(argfile)
    if res.returncode != 0:
        ps.die("javac failed for %s:\n%s" % (src_root, res.stdout + res.stderr))


def class_digests(root):
    """{class name without .class: md5} in MCP's client.md5 format."""
    out = {}
    for rel in ps.walk_files(root):
        if rel.endswith(".class"):
            with open(os.path.join(root, rel), "rb") as f:
                out[rel[:-len(".class")]] = hashlib.md5(f.read()).hexdigest()
    return out


def jars_under(root, skip=()):
    jars = []
    for rel in ps.walk_files(root):
        if rel.endswith(".jar") and "natives" not in rel and not any(s in rel for s in skip):
            jars.append(os.path.join(root, rel))
    return jars


def compile_vanilla(base, mcp_dir, java_home, out, stamp):
    """Compile the vanilla base (plus VANILLA_COMPILE_FIXES) unless out already matches stamp."""
    stamp_file = os.path.join(out, ".stamp")
    if os.path.exists(stamp_file):
        with open(stamp_file) as f:
            if f.read() == stamp:
                return
    with tempfile.TemporaryDirectory(prefix="plutonium-vanilla-") as tmp:
        for rel in ps.walk_files(base):
            if rel.endswith(".java"):
                ps.copy_file(os.path.join(base, rel), os.path.join(tmp, rel))
        for rel, edits in VANILLA_COMPILE_FIXES.items():
            path = os.path.join(tmp, rel)
            with open(path, encoding="utf-8") as f:
                text = f.read()
            for old, new in edits:
                if old not in text:
                    ps.die("vanilla compile fix for %s no longer matches the base" % rel)
                text = text.replace(old, new)
            with open(path, "w", encoding="utf-8") as f:
                f.write(text)
        log_step("Compiling the vanilla base")
        javac(java_home, tmp, jars_under(os.path.join(mcp_dir, "jars", "libraries")), out)
    with open(stamp_file, "w") as f:
        f.write(stamp)


def runtime_classpath(game_dir):
    libs = os.path.join(game_dir, "libraries")
    if not os.path.isdir(os.path.join(libs, "lwjgl3")):
        ps.die("no runtime libraries in %s; run `patchset.py deps` first" % libs)
    # LWJGL 2 and JInput are replaced by LWJGL 3 (same exclusions as launch.sh)
    return jars_under(libs, skip=("org/lwjgl/lwjgl/", "jinput"))


def reobfuscate(mcp_dir, java_home, vanilla_classes, work_classes):
    """Run MCP's reobfuscator; returns the directory of changed + new classes, Mojang-named."""
    with open(os.path.join(mcp_dir, "temp", "client.md5"), "w") as f:
        for name, digest in sorted(class_digests(vanilla_classes).items()):
            f.write("%s %s\n" % (name, digest))
    bin_dir = os.path.join(mcp_dir, "bin", "minecraft")
    if os.path.exists(bin_dir):
        shutil.rmtree(bin_dir)
    shutil.copytree(work_classes, bin_dir)
    env = dict(os.environ, JAVA_HOME=java_home,
               PATH=os.path.join(java_home, "bin") + os.pathsep + os.environ.get("PATH", ""))
    log_path = os.path.join(mcp_dir, "logs", "plutonium-reobf.log")
    with open(log_path, "w") as logf:
        # -n: drop local variable tables (MCP names would otherwise ride along)
        res = subprocess.run([sys.executable, "runtime/reobfuscate.py", "--client", "-n"],
                             cwd=mcp_dir, env=env, stdout=logf, stderr=subprocess.STDOUT)
    if res.returncode != 0:
        ps.die("MCP reobfuscation failed; see " + log_path)
    return os.path.join(mcp_dir, "reobf", "minecraft")


JNA_VERSION = "5.13.0"
JNA_SHA1 = "1200e7ebeedbe0d10062093f32925a912020e747"
LWJGL_NATIVES = {
    "natives-macos": ("macos", "x86_64"), "natives-macos-arm64": ("macos", "aarch64"),
    "natives-windows": ("windows", "x86_64"), "natives-windows-arm64": ("windows", "aarch64"),
    "natives-linux": ("linux", "x86_64"), "natives-linux-arm64": ("linux", "aarch64"),
}


def runtime_manifest():
    """What a launcher needs beyond Mojang's 1.8.9 libraries: the Java version, the Mojang
    libraries to leave out (by "group:artifact:" name prefix), and every extra jar/native
    with its checksum. Library paths are relative to the libraries dir and go on the
    classpath ahead of Mojang's; entries with "os"/"arch" apply to that platform only, and
    "extract" marks natives jars whose shared libraries are unpacked into the natives dir."""
    # JNA 5 first: Mojang's JNA 3.4 (skipped below) has no arm64 natives, and the copy
    # bundled inside java-discord-rpc is older still
    libs = [{"path": "net/java/dev/jna/jna/%s/jna-%s.jar" % (JNA_VERSION, JNA_VERSION),
             "url": "%s/net/java/dev/jna/jna/%s/jna-%s.jar" % (ps.MAVEN, JNA_VERSION, JNA_VERSION),
             "sha1": JNA_SHA1}]
    for module in ps.LWJGL_MODULES:
        for classifier in [None] + sorted(LWJGL_NATIVES):
            name = "%s-%s%s.jar" % (module, ps.LWJGL_VERSION, "-" + classifier if classifier else "")
            url = "%s/org/lwjgl/%s/%s/%s" % (ps.MAVEN, module, ps.LWJGL_VERSION, name)
            try:
                digest = ps.maven_sha1(url)
            except urllib.error.HTTPError:
                continue   # e.g. lwjgl-vulkan only has natives (MoltenVK) on macOS
            entry = {"path": "org/lwjgl/%s/%s/%s" % (module, ps.LWJGL_VERSION, name), "url": url, "sha1": digest}
            if classifier:
                entry["os"], entry["arch"] = LWJGL_NATIVES[classifier]
                entry["extract"] = "natives"
            libs.append(entry)
    for rel, url, digest in ps.EXTRA_LIBS:
        libs.append({"path": rel, "url": url, "sha1": digest})
    natives = [{"os": "macos", "url": ps.MOLTENVK_URL, "sha256": ps.MOLTENVK_TAR_SHA256, "tar_member": ps.MOLTENVK_DYLIB,
                "file": "libMoltenVK.dylib", "file_sha256": ps.MOLTENVK_DYLIB_SHA256}]
    return {"java": 21, "skip_mojang_libraries": ["org.lwjgl.lwjgl:", "net.java.jinput:", "net.java.dev.jna:jna:"],
            "libraries": libs, "natives": natives,
            "jvm_args_macos": ["-XstartOnFirstThread"], "jvm_args": ["-Djava.awt.headless=true"]}


def nest_of(entry):
    """'abc$1.class' -> 'abc'; nests are what javac compiles together."""
    return entry[:-len(".class")].split("$")[0]


def build_release(reobf_dir, work, vanilla_jar, out_zip, label):
    with zipfile.ZipFile(vanilla_jar) as z:
        vanilla = {n: z.read(n) for n in z.namelist() if not n.endswith("/") and not n.startswith("META-INF/")}

    entries = {}
    for rel in ps.walk_files(reobf_dir):
        if rel not in NOT_SHIPPED:
            with open(os.path.join(reobf_dir, rel), "rb") as f:
                entries[rel] = f.read()
    for rel in ps.walk_files(work):
        if rel.endswith(".java") or ps.is_editor_backup(rel):
            continue
        with open(os.path.join(work, rel), "rb") as f:
            data = f.read()
        if vanilla.get(rel) != data:
            entries[rel] = data

    nests = {nest_of(n) for n in entries if n.endswith(".class")}
    delete = sorted(n for n in vanilla if n.endswith(".class") and nest_of(n) in nests and n not in entries)
    meta = {"format": FORMAT_VERSION, "minecraft": ps.MC_VERSION, "client_sha1": ps.CLIENT_SHA1,
            "label": label, "main_class": MAIN_CLASS, "env": RUNTIME_ENV, "runtime": runtime_manifest(),
            "patches": {}, "files": {}, "delete": delete}

    os.makedirs(os.path.dirname(out_zip), exist_ok=True)
    with zipfile.ZipFile(out_zip, "w", zipfile.ZIP_DEFLATED) as z:
        for name in sorted(entries):
            data = entries[name]
            if name in vanilla:
                meta["patches"][name] = {"from": sha1(vanilla[name]), "to": sha1(data)}
                # deltas are zlib-compressed already
                z.writestr(zipfile.ZipInfo("patches/" + name, date_time=(1980, 1, 1, 0, 0, 0)),
                           make_delta(vanilla[name], data))
            else:
                meta["files"][name] = sha1(data)
                z.writestr(zipfile.ZipInfo("files/" + name, date_time=(1980, 1, 1, 0, 0, 0)),
                           data, zipfile.ZIP_DEFLATED)
        z.writestr("release.json", json.dumps(meta, indent=1, sort_keys=True) + "\n")
    return meta, entries


# --------------------------------------------------------------------------- install

def install(release_zip, vanilla_jar, out_jar):
    """Apply a release to the vanilla client jar, verifying every entry. Returns release.json."""
    with open(vanilla_jar, "rb") as f:
        if sha1(f.read()) != ps.CLIENT_SHA1:
            raise ValueError("%s is not the official %s client jar" % (vanilla_jar, ps.MC_VERSION))
    with zipfile.ZipFile(release_zip) as rz, zipfile.ZipFile(vanilla_jar) as vz:
        meta = json.loads(rz.read("release.json"))
        if meta["format"] != FORMAT_VERSION or meta["client_sha1"] != ps.CLIENT_SHA1:
            raise ValueError("release is for another format or client jar")
        drop = set(meta["delete"])
        tmp = out_jar + ".part"
        with zipfile.ZipFile(tmp, "w", zipfile.ZIP_DEFLATED) as out:
            for info in vz.infolist():
                n = info.filename
                # META-INF holds Mojang's jar signature, which the patched classes would break
                if n.endswith("/") or n.startswith("META-INF/") or n in drop:
                    continue
                data = vz.read(n)
                if n in meta["patches"]:
                    expect = meta["patches"][n]
                    if sha1(data) != expect["from"]:
                        raise ValueError("vanilla %s differs from the one the release was made against" % n)
                    data = apply_delta(data, rz.read("patches/" + n))
                    if sha1(data) != expect["to"]:
                        raise ValueError("patched %s doesn't match the release" % n)
                out.writestr(n, data)
            for n, digest in sorted(meta["files"].items()):
                data = rz.read("files/" + n)
                if sha1(data) != digest:
                    raise ValueError("release file %s is corrupt" % n)
                out.writestr(n, data)
        os.replace(tmp, out_jar)
    return meta


# --------------------------------------------------------------------------- commands

def log_step(msg):
    ps.log("== " + msg)


def cmd_release(args):
    build = os.path.abspath(args.build)
    cache = os.path.join(build, "cache")
    mcp_dir = os.path.join(build, "mcp")
    base = os.path.join(build, "base")
    if not (os.path.isfile(os.path.join(mcp_dir, "runtime", "py2compat.py"))
            and os.path.isfile(os.path.join(mcp_dir, "temp", "client_ro_cls.srg"))):
        ps.die("no MCP workspace from this version of the tool in %s; run `patchset.py base` first" % mcp_dir)
    java_home = ps.find_java8(args.java_home) or ps.fetch_java8(cache)
    out_root = os.path.join(build, "release")
    os.makedirs(out_root, exist_ok=True)

    with tempfile.TemporaryDirectory(prefix="plutonium-release-") as tmp:
        work = os.path.abspath(args.work)
        if args.public:
            log_step("Exporting the public tree")
            exported = os.path.join(tmp, "patchset")
            ps.generate(work, base, exported, ps.tracked_files(work), ps.load_exclusions(args.public))
            work = os.path.join(tmp, "work")
            ps.materialise(exported, base, work)

        with open(os.path.join(build, "base.sha256"), "rb") as f:
            stamp = sha1(f.read() + repr((JAVAC_FLAGS, VANILLA_COMPILE_FIXES, java_home)).encode())
        vanilla_classes = os.path.join(out_root, "vanilla-classes")
        compile_vanilla(base, mcp_dir, java_home, vanilla_classes, stamp)

        log_step("Compiling %s" % work)
        work_classes = os.path.join(tmp, "classes")
        javac(java_home, work, runtime_classpath(os.path.abspath(args.game_dir)), work_classes)

        log_step("Reobfuscating with MCP")
        reobf_dir = reobfuscate(mcp_dir, java_home, vanilla_classes, work_classes)

        log_step("Writing the release")
        label = args.label or git_label()
        out_zip = os.path.abspath(args.out) if args.out else os.path.join(
            out_root, "plutonium-%s-%s.zip" % (ps.MC_VERSION, label))
        vanilla_jar = os.path.join(mcp_dir, "jars", "versions", ps.MC_VERSION, ps.MC_VERSION + ".jar")
        meta, entries = build_release(reobf_dir, work, vanilla_jar, out_zip, label)

        # Round trip: installing the release must reproduce exactly what was built.
        check_jar = os.path.join(tmp, "check.jar")
        install(out_zip, vanilla_jar, check_jar)
        with zipfile.ZipFile(check_jar) as z:
            bad = [n for n, data in entries.items() if z.read(n) != data]
            leftover = [n for n in meta["delete"] if n in z.namelist()]
        if bad or leftover:
            ps.die("release round trip failed: %s" % (bad + leftover)[:10])

    vanilla_classes_count = len(class_digests(vanilla_classes))
    ps.log("release: %d patched vanilla entries, %d new files, %d removed, %d vanilla classes untouched"
           % (len(meta["patches"]), len(meta["files"]), len(meta["delete"]),
              vanilla_classes_count - sum(1 for n in meta["patches"] if n.endswith(".class"))))
    ps.log("%s (%.1f MB), verified by installing it onto the vanilla jar"
           % (out_zip, os.path.getsize(out_zip) / 1e6))


def git_label():
    res = subprocess.run(["git", "rev-parse", "--short", "HEAD"], cwd=ps.REPO_ROOT, capture_output=True, text=True)
    sha = res.stdout.strip() or "dev"
    dirty = subprocess.run(["git", "status", "--porcelain", "--", "src"], cwd=ps.REPO_ROOT,
                           capture_output=True, text=True).stdout.strip()
    return sha + ("-dirty" if dirty else "")


def cmd_install(args):
    cache = os.path.join(os.path.abspath(args.build), "cache")
    vanilla_jar = args.jar
    if vanilla_jar is None:
        with open(ps.fetch_version_json(cache)) as f:
            client = json.load(f)["downloads"]["client"]
        vanilla_jar = ps.download(client["url"], os.path.join(cache, "client-%s.jar" % ps.MC_VERSION), client["sha1"])
    try:
        meta = install(os.path.abspath(args.release), vanilla_jar, os.path.abspath(args.out))
    except (ValueError, KeyError, zipfile.BadZipFile) as e:
        ps.die("install failed: %s" % e)
    ps.log("installed %s -> %s" % (meta["label"], args.out))
    ps.log("run with main class %s, environment %s, plus the runtime libraries from `patchset.py deps`"
           % (meta["main_class"], " ".join("%s=%s" % kv for kv in sorted(meta["env"].items()))))
