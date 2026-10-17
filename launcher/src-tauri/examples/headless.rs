//! Install and launch Plutonium without the GUI, printing progress:
//!   cargo run --example headless -- <data dir> [username]
//! PLUTONIUM_RELEASE=<zip> uses a local release instead of the latest GitHub one.

use app_lib::game;

fn main() {
    let mut args = std::env::args().skip(1);
    let root = args.next().expect("usage: headless <data dir> [username]");
    let username = args.next().unwrap_or_else(|| "Player".into());
    let dirs = game::Dirs::new(root.into());
    let report = |stage: &str, done: u64, total: u64| {
        if total == 0 || done == total || done % 100 == 0 {
            println!("[{}] {}/{}", stage, done, total);
        }
    };
    let installed = tauri::async_runtime::block_on(game::prepare(&report, &dirs)).unwrap_or_else(|e| {
        eprintln!("prepare failed: {}", e);
        std::process::exit(1);
    });
    let opts = game::LaunchOptions { username, ram_gb: 2, window_mode: "Windowed".into(), discord_rpc: false };
    let mut child = game::launch(&dirs, &installed, &opts).unwrap_or_else(|e| {
        eprintln!("launch failed: {}", e);
        std::process::exit(1);
    });
    println!("game running, pid {} (log: {}/logs/launcher-game.log)", child.id(), dirs.game.display());
    let status = child.wait().expect("wait for game");
    println!("game exited: {}", status);
}
