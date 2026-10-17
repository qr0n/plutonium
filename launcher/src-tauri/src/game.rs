//! Installs and launches Plutonium from nothing: Mojang's 1.8.9 client jar, libraries and
//! assets, the extra libraries and natives a release lists, a Java runtime from Adoptium,
//! and the release itself, applied to the vanilla jar the way `tools/patchset.py install`
//! does. Every download is checked against a SHA-1/SHA-256 before it is used.

use futures::stream::{self, StreamExt};
use serde::Serialize;
use serde_json::Value;
use sha1::Sha1;
use sha2::{Digest, Sha256};
use std::collections::{HashMap, HashSet};
use std::fs;
use std::io::{Read, Write};
use std::path::{Path, PathBuf};
use std::process::{Child, Command, Stdio};
use std::sync::atomic::{AtomicU64, Ordering};
use std::sync::Arc;

const MC_VERSION: &str = "1.8.9";
const CLIENT_SHA1: &str = "3870888a6c3d349d3771a3e9d16c9bf5e076b908";
const VERSION_MANIFEST: &str = "https://piston-meta.mojang.com/mc/game/version_manifest_v2.json";
const RELEASES_API: &str = "https://api.github.com/repos/qr0n/plutonium/releases/latest";
const RELEASE_FORMAT: u64 = 1;
const DELTA_MAGIC: &[u8] = b"PLDELTA1";
const USER_AGENT: &str = "plutonium-launcher/1 (+https://github.com/qr0n/plutonium)";
const PARALLEL_DOWNLOADS: usize = 16;

type Result<T> = std::result::Result<T, String>;

/// Where everything lives, under the app's data dir.
pub struct Dirs {
    pub game: PathBuf,
    assets: PathBuf,
    libraries: PathBuf,
    natives: PathBuf,
    versions: PathBuf,
    runtime: PathBuf,
}

impl Dirs {
    pub fn new(root: PathBuf) -> Self {
        Self {
            game: root.join("game"),
            assets: root.join("assets"),
            libraries: root.join("libraries"),
            natives: root.join("natives"),
            versions: root.join("versions"),
            runtime: root.join("runtime"),
        }
    }
}

/// Progress sink: (stage, done, total); total 0 means indeterminate.
pub type Report<'a> = &'a (dyn Fn(&str, u64, u64) + Sync);

#[derive(Serialize, Clone)]
pub struct Progress<'a> {
    pub stage: &'a str,
    pub done: u64,
    pub total: u64,
}

// --------------------------------------------------------------------------- platform

fn os_name() -> &'static str {
    if cfg!(target_os = "macos") {
        "macos"
    } else if cfg!(target_os = "windows") {
        "windows"
    } else {
        "linux"
    }
}

fn arch_name() -> &'static str {
    if cfg!(target_arch = "aarch64") {
        "aarch64"
    } else {
        "x86_64"
    }
}

fn applies_here(entry: &Value) -> bool {
    entry.get("os").and_then(Value::as_str).map_or(true, |o| o == os_name())
        && entry.get("arch").and_then(Value::as_str).map_or(true, |a| a == arch_name())
}

// --------------------------------------------------------------------------- downloads

#[derive(Clone, Copy)]
enum Check<'a> {
    Sha1(&'a str),
    Sha256(&'a str),
}

fn hex_sha1(data: &[u8]) -> String {
    hex::encode(Sha1::digest(data))
}

fn hex_sha256(data: &[u8]) -> String {
    hex::encode(Sha256::digest(data))
}

fn file_matches(path: &Path, check: Check) -> bool {
    match (check, fs::read(path)) {
        (Check::Sha1(want), Ok(data)) => hex_sha1(&data) == want,
        (Check::Sha256(want), Ok(data)) => hex_sha256(&data) == want,
        (_, Err(_)) => false,
    }
}

fn http() -> Result<reqwest::Client> {
    reqwest::Client::builder()
        .user_agent(USER_AGENT)
        .build()
        .map_err(|e| e.to_string())
}

async fn fetch_bytes(client: &reqwest::Client, url: &str) -> Result<Vec<u8>> {
    let mut last = String::new();
    for attempt in 0..4u32 {
        if attempt > 0 {
            // CDNs drop bursts of parallel requests; back off
            tokio::time::sleep(std::time::Duration::from_millis(500 << attempt)).await;
        }
        match client.get(url).send().await.and_then(|r| r.error_for_status()) {
            Ok(resp) => match resp.bytes().await {
                Ok(b) => return Ok(b.to_vec()),
                Err(e) => last = e.to_string(),
            },
            Err(e) => last = e.to_string(),
        }
    }
    Err(format!("download failed: {} ({})", url, last))
}

/// Download url to dest unless dest already passes the check; verifies before keeping it.
async fn download(client: &reqwest::Client, url: &str, dest: &Path, check: Check<'_>) -> Result<()> {
    if file_matches(dest, check) {
        return Ok(());
    }
    let data = fetch_bytes(client, url).await?;
    let ok = match check {
        Check::Sha1(want) => hex_sha1(&data) == want,
        Check::Sha256(want) => hex_sha256(&data) == want,
    };
    if !ok {
        return Err(format!("checksum mismatch for {}", url));
    }
    write_atomic(dest, &data)
}

fn write_atomic(dest: &Path, data: &[u8]) -> Result<()> {
    if let Some(parent) = dest.parent() {
        fs::create_dir_all(parent).map_err(|e| format!("{}: {}", parent.display(), e))?;
    }
    let tmp = dest.with_extension("part");
    fs::write(&tmp, data).map_err(|e| format!("{}: {}", tmp.display(), e))?;
    fs::rename(&tmp, dest).map_err(|e| format!("{}: {}", dest.display(), e))
}

fn read_json(path: &Path) -> Result<Value> {
    let data = fs::read(path).map_err(|e| format!("{}: {}", path.display(), e))?;
    serde_json::from_slice(&data).map_err(|e| format!("{}: {}", path.display(), e))
}

/// Download many (url, dest, sha1) files in parallel, reporting progress under stage.
async fn download_all(report: Report<'_>, client: &reqwest::Client, stage: &str, jobs: Vec<(String, PathBuf, String)>) -> Result<()> {
    let jobs: Vec<_> = jobs.into_iter().filter(|(_, dest, sha)| !file_matches(dest, Check::Sha1(sha))).collect();
    let total = jobs.len() as u64;
    if total == 0 {
        return Ok(());
    }
    let done = Arc::new(AtomicU64::new(0));
    report(stage, 0, total);
    let results: Vec<Result<()>> = stream::iter(jobs)
        .map(|(url, dest, sha)| {
            let done = done.clone();
            async move {
                download(client, &url, &dest, Check::Sha1(&sha)).await?;
                let n = done.fetch_add(1, Ordering::Relaxed) + 1;
                report(stage, n, total);
                Ok(())
            }
        })
        .buffer_unordered(PARALLEL_DOWNLOADS)
        .collect()
        .await;
    results.into_iter().collect()
}

// --------------------------------------------------------------------------- Minecraft

/// "group:artifact:version" -> "group/as/path/artifact/version/artifact-version.jar"
fn maven_path(name: &str) -> Option<String> {
    let mut parts = name.split(':');
    let (group, artifact, version) = (parts.next()?, parts.next()?, parts.next()?);
    Some(format!("{}/{}/{}/{}-{}.jar", group.replace('.', "/"), artifact, version, artifact, version))
}

async fn fetch_version_json(report: Report<'_>, client: &reqwest::Client, dirs: &Dirs) -> Result<Value> {
    report("Checking Minecraft", 0, 0);
    let path = dirs.versions.join(MC_VERSION).join(format!("{}.json", MC_VERSION));
    match fetch_bytes(client, VERSION_MANIFEST).await {
        Ok(data) => {
            let manifest: Value = serde_json::from_slice(&data).map_err(|e| e.to_string())?;
            let entry = manifest["versions"]
                .as_array()
                .and_then(|v| v.iter().find(|e| e["id"] == MC_VERSION))
                .ok_or("Mojang's version list has no 1.8.9")?;
            let (url, sha) = (entry["url"].as_str().unwrap_or(""), entry["sha1"].as_str().unwrap_or(""));
            download(client, url, &path, Check::Sha1(sha)).await?;
        }
        // offline: carry on with what was downloaded last time
        Err(e) if path.exists() => log::warn!("version manifest unavailable, using cached: {}", e),
        Err(e) => return Err(e),
    }
    read_json(&path)
}

async fn install_minecraft(report: Report<'_>, client: &reqwest::Client, dirs: &Dirs, meta: &Value, runtime: &Value) -> Result<Vec<PathBuf>> {
    let jar = vanilla_jar(dirs);
    let info = &meta["downloads"]["client"];
    if info["sha1"] != CLIENT_SHA1 {
        return Err("Mojang's 1.8.9 client jar changed checksum".into());
    }
    if !file_matches(&jar, Check::Sha1(CLIENT_SHA1)) {
        report("Downloading Minecraft", 0, 0);
    }
    download(client, info["url"].as_str().unwrap_or(""), &jar, Check::Sha1(CLIENT_SHA1)).await?;

    // "group:artifact:" name prefixes the release replaces (LWJGL 2, JInput, old JNA)
    let skip: Vec<&str> = runtime["skip_mojang_libraries"].as_array().map_or(vec![], |a| a.iter().filter_map(Value::as_str).collect());
    let mut jobs = vec![];
    let mut classpath = vec![];
    for lib in meta["libraries"].as_array().into_iter().flatten() {
        let name = lib["name"].as_str().unwrap_or("");
        let artifact = &lib["downloads"]["artifact"];
        if artifact.is_null() || skip.iter().any(|prefix| name.starts_with(prefix)) {
            continue;
        }
        let rel = maven_path(name).ok_or_else(|| format!("bad library name {}", name))?;
        let dest = dirs.libraries.join(&rel);
        jobs.push((artifact["url"].as_str().unwrap_or("").to_string(), dest.clone(), artifact["sha1"].as_str().unwrap_or("").to_string()));
        classpath.push(dest);
    }
    download_all(report, client, "Downloading libraries", jobs).await?;
    Ok(classpath)
}

async fn install_assets(report: Report<'_>, client: &reqwest::Client, dirs: &Dirs, meta: &Value) -> Result<String> {
    let index = &meta["assetIndex"];
    let id = index["id"].as_str().unwrap_or("legacy").to_string();
    let index_path = dirs.assets.join("indexes").join(format!("{}.json", id));
    download(client, index["url"].as_str().unwrap_or(""), &index_path, Check::Sha1(index["sha1"].as_str().unwrap_or(""))).await?;
    let objects = read_json(&index_path)?;
    let mut seen = HashSet::new();
    let mut jobs = vec![];
    for obj in objects["objects"].as_object().into_iter().flat_map(|o| o.values()) {
        let hash = obj["hash"].as_str().unwrap_or("").to_string();
        if hash.len() < 2 || !seen.insert(hash.clone()) {
            continue;
        }
        let url = format!("https://resources.download.minecraft.net/{}/{}", &hash[..2], hash);
        jobs.push((url, dirs.assets.join("objects").join(&hash[..2]).join(&hash), hash));
    }
    download_all(report, client, "Downloading assets", jobs).await?;
    Ok(id)
}

fn vanilla_jar(dirs: &Dirs) -> PathBuf {
    dirs.versions.join(MC_VERSION).join(format!("{}.jar", MC_VERSION))
}

// --------------------------------------------------------------------------- runtime libraries

async fn install_runtime_libs(report: Report<'_>, client: &reqwest::Client, dirs: &Dirs, runtime: &Value) -> Result<Vec<PathBuf>> {
    let mut jobs = vec![];
    let mut classpath = vec![];
    let mut extract = vec![];
    for lib in runtime["libraries"].as_array().into_iter().flatten().filter(|l| applies_here(l)) {
        let dest = dirs.libraries.join(lib["path"].as_str().unwrap_or(""));
        jobs.push((lib["url"].as_str().unwrap_or("").to_string(), dest.clone(), lib["sha1"].as_str().unwrap_or("").to_string()));
        if lib.get("extract").is_some() {
            extract.push(dest);
        } else {
            classpath.push(dest);
        }
    }
    download_all(report, client, "Downloading LWJGL", jobs).await?;

    fs::create_dir_all(&dirs.natives).map_err(|e| e.to_string())?;
    let natives: Vec<&Value> = runtime["natives"].as_array().into_iter().flatten().filter(|n| applies_here(n)).collect();
    // a release native replaces the jar's copy (LWJGL bundles an older MoltenVK)
    let replaced: HashSet<&str> = natives.iter().filter_map(|n| n["file"].as_str()).collect();
    for jar in &extract {
        extract_natives(jar, &dirs.natives, &replaced)?;
    }
    for native in natives {
        install_native_archive(report, client, dirs, native).await?;
    }
    Ok(classpath)
}

/// Unpack the shared libraries in a natives jar (flattened) into dir, except those in skip.
fn extract_natives(jar: &Path, dir: &Path, skip: &HashSet<&str>) -> Result<()> {
    let file = fs::File::open(jar).map_err(|e| format!("{}: {}", jar.display(), e))?;
    let mut zip = zip::ZipArchive::new(file).map_err(|e| format!("{}: {}", jar.display(), e))?;
    for i in 0..zip.len() {
        let mut entry = zip.by_index(i).map_err(|e| e.to_string())?;
        let name = entry.name().rsplit('/').next().unwrap_or("").to_string();
        if !(name.ends_with(".dylib") || name.ends_with(".so") || name.ends_with(".dll")) || skip.contains(name.as_str()) {
            continue;
        }
        let mut data = vec![];
        entry.read_to_end(&mut data).map_err(|e| e.to_string())?;
        let dest = dir.join(&name);
        if fs::read(&dest).ok().as_deref() != Some(&data[..]) {
            write_atomic(&dest, &data)?;
        }
    }
    Ok(())
}

/// A native library that ships inside a tar (MoltenVK): fetch, check, pull out one member.
async fn install_native_archive(report: Report<'_>, client: &reqwest::Client, dirs: &Dirs, native: &Value) -> Result<()> {
    let file = native["file"].as_str().unwrap_or("");
    let file_sha = native["file_sha256"].as_str().unwrap_or("");
    let dest = dirs.natives.join(file);
    if file_matches(&dest, Check::Sha256(file_sha)) {
        return Ok(());
    }
    report(&format!("Downloading {}", file), 0, 0);
    let url = native["url"].as_str().unwrap_or("");
    let data = fetch_bytes(client, url).await?;
    if hex_sha256(&data) != native["sha256"].as_str().unwrap_or("") {
        return Err(format!("checksum mismatch for {}", url));
    }
    let member = native["tar_member"].as_str().unwrap_or("");
    let mut archive = tar::Archive::new(&data[..]);
    for entry in archive.entries().map_err(|e| e.to_string())? {
        let mut entry = entry.map_err(|e| e.to_string())?;
        if entry.path().map_err(|e| e.to_string())?.to_string_lossy() == member {
            let mut out = vec![];
            entry.read_to_end(&mut out).map_err(|e| e.to_string())?;
            if hex_sha256(&out) != file_sha {
                return Err(format!("unexpected {} inside {}", file, url));
            }
            return write_atomic(&dest, &out);
        }
    }
    Err(format!("{} not found in {}", member, url))
}

// --------------------------------------------------------------------------- Java

fn java_binary(home: &Path) -> PathBuf {
    if cfg!(target_os = "windows") {
        home.join("bin").join("javaw.exe")
    } else {
        home.join("bin").join("java")
    }
}

/// A Temurin JRE of the release's Java version, downloaded once into runtime/.
async fn install_java(report: Report<'_>, client: &reqwest::Client, dirs: &Dirs, version: u64) -> Result<PathBuf> {
    let record = dirs.runtime.join(format!("java{}.json", version));
    if let Ok(rec) = read_json(&record) {
        let java = PathBuf::from(rec["java"].as_str().unwrap_or(""));
        if java.is_file() {
            return Ok(java);
        }
    }
    report(&format!("Downloading Java {}", version), 0, 0);
    let os = match os_name() {
        "macos" => "mac",
        other => other,
    };
    let arch = if arch_name() == "aarch64" { "aarch64" } else { "x64" };
    let api = format!(
        "https://api.adoptium.net/v3/assets/latest/{}/hotspot?architecture={}&image_type=jre&os={}&vendor=eclipse",
        version, arch, os
    );
    let releases: Value = serde_json::from_slice(&fetch_bytes(client, &api).await?).map_err(|e| e.to_string())?;
    let pkg = &releases[0]["binary"]["package"];
    let (link, sha, name) = (
        pkg["link"].as_str().ok_or("Adoptium has no Java runtime for this platform")?,
        pkg["checksum"].as_str().unwrap_or(""),
        pkg["name"].as_str().unwrap_or("jre"),
    );
    let data = fetch_bytes(client, link).await?;
    if hex_sha256(&data) != sha {
        return Err(format!("checksum mismatch for {}", link));
    }
    report(&format!("Unpacking Java {}", version), 0, 0);
    let dest = dirs.runtime.join(name.trim_end_matches(".tar.gz").trim_end_matches(".zip"));
    if dest.exists() {
        fs::remove_dir_all(&dest).map_err(|e| e.to_string())?;
    }
    if name.ends_with(".zip") {
        zip::ZipArchive::new(std::io::Cursor::new(data))
            .and_then(|mut z| z.extract(&dest))
            .map_err(|e| format!("unpacking {}: {}", name, e))?;
    } else {
        tar::Archive::new(flate2::read::GzDecoder::new(&data[..]))
            .unpack(&dest)
            .map_err(|e| format!("unpacking {}: {}", name, e))?;
    }
    let java = find_java_home(&dest).map(|h| java_binary(&h)).ok_or("no java inside the downloaded runtime")?;
    write_atomic(&record, serde_json::json!({ "java": java }).to_string().as_bytes())?;
    Ok(java)
}

fn find_java_home(dir: &Path) -> Option<PathBuf> {
    if java_binary(dir).is_file() {
        return Some(dir.to_path_buf());
    }
    for entry in fs::read_dir(dir).ok()?.flatten() {
        if entry.path().is_dir() {
            if let Some(home) = find_java_home(&entry.path()) {
                return Some(home);
            }
        }
    }
    None
}

// --------------------------------------------------------------------------- the release

fn read_varint(buf: &[u8], pos: &mut usize) -> Result<usize> {
    let (mut n, mut shift) = (0usize, 0);
    loop {
        let b = *buf.get(*pos).ok_or("truncated delta")?;
        *pos += 1;
        n |= ((b & 0x7f) as usize) << shift;
        if b < 0x80 {
            return Ok(n);
        }
        shift += 7;
    }
}

/// Inverse of tools/binpatch.py make_delta (format documented there).
fn apply_delta(old: &[u8], delta: &[u8]) -> Result<Vec<u8>> {
    let body = delta.strip_prefix(DELTA_MAGIC).ok_or("not a Plutonium delta")?;
    let mut ops = vec![];
    flate2::read::ZlibDecoder::new(body).read_to_end(&mut ops).map_err(|e| e.to_string())?;
    let mut pos = 0;
    let size = read_varint(&ops, &mut pos)?;
    let mut out = Vec::with_capacity(size);
    while out.len() < size {
        let op = *ops.get(pos).ok_or("truncated delta")?;
        pos += 1;
        let (a, n) = match op {
            0 => {
                let off = read_varint(&ops, &mut pos)?;
                let n = read_varint(&ops, &mut pos)?;
                (old.get(off..off + n).ok_or("delta copies past the end of its source")?, n)
            }
            1 => {
                let n = read_varint(&ops, &mut pos)?;
                let lit = ops.get(pos..pos + n).ok_or("truncated delta")?;
                pos += n;
                (lit, n)
            }
            _ => return Err(format!("bad delta op {}", op)),
        };
        debug_assert_eq!(a.len(), n);
        out.extend_from_slice(a);
    }
    if out.len() != size {
        return Err("delta produced the wrong size".into());
    }
    Ok(out)
}

fn zip_read(zip: &mut zip::ZipArchive<fs::File>, name: &str) -> Result<Vec<u8>> {
    let mut entry = zip.by_name(name).map_err(|e| format!("{}: {}", name, e))?;
    let mut data = vec![];
    entry.read_to_end(&mut data).map_err(|e| e.to_string())?;
    Ok(data)
}

/// Apply a release zip to the vanilla jar, verifying every entry; writes out_jar.
fn install_release(release: &Path, vanilla: &Path, out_jar: &Path) -> Result<Value> {
    let open = |p: &Path| -> Result<zip::ZipArchive<fs::File>> {
        let f = fs::File::open(p).map_err(|e| format!("{}: {}", p.display(), e))?;
        zip::ZipArchive::new(f).map_err(|e| format!("{}: {}", p.display(), e))
    };
    let mut rz = open(release)?;
    let mut vz = open(vanilla)?;
    let meta: Value = serde_json::from_slice(&zip_read(&mut rz, "release.json")?).map_err(|e| e.to_string())?;
    if meta["format"].as_u64() != Some(RELEASE_FORMAT) || meta["client_sha1"] != CLIENT_SHA1 {
        return Err("this release needs a newer launcher".into());
    }
    let patches = meta["patches"].as_object().cloned().unwrap_or_default();
    let files = meta["files"].as_object().cloned().unwrap_or_default();
    let drop: HashSet<&str> = meta["delete"].as_array().into_iter().flatten().filter_map(Value::as_str).collect();

    let tmp = out_jar.with_extension("part");
    if let Some(parent) = out_jar.parent() {
        fs::create_dir_all(parent).map_err(|e| format!("{}: {}", parent.display(), e))?;
    }
    let mut out = zip::ZipWriter::new(fs::File::create(&tmp).map_err(|e| format!("{}: {}", tmp.display(), e))?);
    let opts = zip::write::SimpleFileOptions::default().compression_method(zip::CompressionMethod::Deflated);
    for i in 0..vz.len() {
        let name = vz.by_index(i).map_err(|e| e.to_string())?.name().to_string();
        // META-INF holds Mojang's signature, which the patched classes would break
        if name.ends_with('/') || name.starts_with("META-INF/") || drop.contains(name.as_str()) {
            continue;
        }
        let mut data = zip_read(&mut vz, &name)?;
        if let Some(p) = patches.get(&name) {
            if hex_sha1(&data) != p["from"].as_str().unwrap_or("") {
                return Err(format!("vanilla {} isn't the one this release was made for", name));
            }
            data = apply_delta(&data, &zip_read(&mut rz, &format!("patches/{}", name))?)?;
            if hex_sha1(&data) != p["to"].as_str().unwrap_or("") {
                return Err(format!("patched {} doesn't match the release", name));
            }
        }
        out.start_file(name, opts).map_err(|e| e.to_string())?;
        out.write_all(&data).map_err(|e| e.to_string())?;
    }
    for (name, sha) in &files {
        let data = zip_read(&mut rz, &format!("files/{}", name))?;
        if hex_sha1(&data) != sha.as_str().unwrap_or("") {
            return Err(format!("release file {} is corrupt", name));
        }
        out.start_file(name.as_str(), opts).map_err(|e| e.to_string())?;
        out.write_all(&data).map_err(|e| e.to_string())?;
    }
    out.finish().map_err(|e| e.to_string())?;
    fs::rename(&tmp, out_jar).map_err(|e| e.to_string())?;
    Ok(meta)
}

/// The release zip to use: PLUTONIUM_RELEASE (a local file) if set, else the latest GitHub
/// release, else the one downloaded last time when offline.
async fn fetch_release(report: Report<'_>, client: &reqwest::Client, dirs: &Dirs) -> Result<PathBuf> {
    if let Ok(local) = std::env::var("PLUTONIUM_RELEASE") {
        return Ok(PathBuf::from(local));
    }
    report("Checking for updates", 0, 0);
    let releases = dirs.versions.join("plutonium");
    let latest: Result<Value> = match fetch_bytes(client, RELEASES_API).await {
        Ok(data) => serde_json::from_slice(&data).map_err(|e| e.to_string()),
        Err(e) => Err(e),
    };
    let latest = match latest {
        Ok(v) => v,
        Err(e) => {
            let cached = releases.join("release.zip");
            if cached.exists() {
                log::warn!("couldn't check for updates, using the installed release: {}", e);
                return Ok(cached);
            }
            return Err(format!("couldn't get a Plutonium release: {}", e));
        }
    };
    let asset = latest["assets"]
        .as_array()
        .and_then(|a| a.iter().find(|x| x["name"].as_str().map_or(false, |n| n.starts_with("plutonium-1.8.9-") && n.ends_with(".zip"))))
        .ok_or("the latest GitHub release has no plutonium-1.8.9-*.zip")?;
    let name = asset["name"].as_str().unwrap_or("release.zip");
    let dest = releases.join("release.zip");
    let stamp = releases.join("release.name");
    if fs::read_to_string(&stamp).ok().as_deref() != Some(name) || !dest.exists() {
        report("Downloading Plutonium", 0, 0);
        let data = fetch_bytes(client, asset["browser_download_url"].as_str().unwrap_or("")).await?;
        write_atomic(&dest, &data)?;
        write_atomic(&stamp, name.as_bytes())?;
    }
    Ok(dest)
}

// --------------------------------------------------------------------------- launch

pub struct Installed {
    java: PathBuf,
    client_jar: PathBuf,
    classpath: Vec<PathBuf>,
    asset_index: String,
    main_class: String,
    env: HashMap<String, String>,
    jvm_args: Vec<String>,
}

/// Everything up to a runnable client; skips whatever is already in place.
pub async fn prepare(report: Report<'_>, dirs: &Dirs) -> Result<Installed> {
    let client = http()?;
    let release = fetch_release(report, &client, dirs).await?;

    // read the runtime section before installing so libraries come from the release
    let meta: Value = {
        let f = fs::File::open(&release).map_err(|e| format!("{}: {}", release.display(), e))?;
        let mut z = zip::ZipArchive::new(f).map_err(|e| format!("{}: {}", release.display(), e))?;
        serde_json::from_slice(&zip_read(&mut z, "release.json")?).map_err(|e| e.to_string())?
    };
    let runtime = meta["runtime"].clone();
    let version = fetch_version_json(report, &client, dirs).await?;
    let mojang = install_minecraft(report, &client, dirs, &version, &runtime).await?;
    // the release's libraries go first: Mojang's JNA 3.4 has no arm64 build and would
    // shadow the newer JNA inside java-discord-rpc
    let mut classpath = install_runtime_libs(report, &client, dirs, &runtime).await?;
    classpath.extend(mojang);
    let asset_index = install_assets(report, &client, dirs, &version).await?;
    let java = install_java(report, &client, dirs, runtime["java"].as_u64().unwrap_or(21)).await?;

    // re-apply only when the release zip changed
    let client_jar = dirs.versions.join("plutonium").join("client.jar");
    let stamp = dirs.versions.join("plutonium").join("client.sha256");
    let release_sha = hex_sha256(&fs::read(&release).map_err(|e| e.to_string())?);
    if !client_jar.exists() || fs::read_to_string(&stamp).ok().as_deref() != Some(release_sha.as_str()) {
        report("Installing Plutonium", 0, 0);
        let (release, vanilla, out) = (release.clone(), vanilla_jar(dirs), client_jar.clone());
        tauri::async_runtime::spawn_blocking(move || install_release(&release, &vanilla, &out))
            .await
            .map_err(|e| e.to_string())??;
        write_atomic(&stamp, release_sha.as_bytes())?;
    }

    let str_list = |v: &Value| -> Vec<String> { v.as_array().into_iter().flatten().filter_map(|s| s.as_str().map(String::from)).collect() };
    let mut jvm_args = str_list(&runtime["jvm_args"]);
    if cfg!(target_os = "macos") {
        jvm_args.extend(str_list(&runtime["jvm_args_macos"]));
    }
    let env = meta["env"]
        .as_object()
        .map(|o| o.iter().filter_map(|(k, v)| v.as_str().map(|v| (k.clone(), v.to_string()))).collect())
        .unwrap_or_default();
    Ok(Installed {
        java,
        client_jar,
        classpath,
        asset_index,
        main_class: meta["main_class"].as_str().unwrap_or("net.minecraft.client.main.Main").to_string(),
        env,
        jvm_args,
    })
}

pub struct LaunchOptions {
    pub username: String,
    pub ram_gb: u32,
    pub window_mode: String,
    pub discord_rpc: bool,
}

pub fn launch(dirs: &Dirs, inst: &Installed, opts: &LaunchOptions) -> Result<Child> {
    fs::create_dir_all(dirs.game.join("logs")).map_err(|e| e.to_string())?;
    let sep = if cfg!(target_os = "windows") { ";" } else { ":" };
    let mut cp = vec![inst.client_jar.display().to_string()];
    cp.extend(inst.classpath.iter().map(|p| p.display().to_string()));
    let natives = dirs.natives.display().to_string();

    let mut cmd = Command::new(&inst.java);
    cmd.current_dir(&dirs.game)
        .envs(&inst.env)
        .args(&inst.jvm_args)
        .arg(format!("-Xms{}G", opts.ram_gb))
        .arg(format!("-Xmx{}G", opts.ram_gb))
        // same GC tuning as launch.sh
        .args([
            "-XX:+UseG1GC", "-XX:MaxGCPauseMillis=20", "-XX:+UnlockExperimentalVMOptions",
            "-XX:G1NewSizePercent=40", "-XX:G1MaxNewSizePercent=50", "-XX:G1HeapRegionSize=16M",
            "-XX:G1ReservePercent=15", "-XX:G1MixedGCCountTarget=4", "-XX:InitiatingHeapOccupancyPercent=20",
            "-XX:SurvivorRatio=32", "-XX:MaxTenuringThreshold=1", "-XX:+ParallelRefProcEnabled",
            "-XX:MaxDirectMemorySize=3G",
        ])
        .arg(format!("-Dorg.lwjgl.librarypath={}", natives))
        .arg(format!("-Djava.library.path={}", natives))
        .arg(format!("-Dplutonium.discordRpc={}", opts.discord_rpc));
    if opts.window_mode == "Borderless" {
        cmd.arg("-Dplutonium.windowMode=Borderless");
    }
    cmd.arg("-cp").arg(cp.join(sep)).arg(&inst.main_class);
    cmd.args(["--version", "plutonium", "--accessToken", "0", "--userProperties", "{}"])
        .arg("--username").arg(&opts.username)
        .arg("--gameDir").arg(&dirs.game)
        .arg("--assetsDir").arg(&dirs.assets)
        .arg("--assetIndex").arg(&inst.asset_index)
        .args(["--width", "1352", "--height", "848"]);
    if opts.window_mode == "Fullscreen" {
        cmd.arg("--fullscreen");
    }

    let log_path = dirs.game.join("logs").join("launcher-game.log");
    let log = fs::File::create(&log_path).map_err(|e| format!("{}: {}", log_path.display(), e))?;
    let log_err = log.try_clone().map_err(|e| e.to_string())?;
    cmd.stdout(Stdio::from(log)).stderr(Stdio::from(log_err));
    cmd.spawn().map_err(|e| format!("couldn't start Java ({}): {}", inst.java.display(), e))
}
