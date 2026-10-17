pub mod game;

use serde::{Deserialize, Serialize};
use std::fs;
use std::path::PathBuf;
use std::process::Child;
use std::sync::Mutex;
use tauri::{AppHandle, Emitter, Manager, State};

struct GameProcess(Mutex<Option<Child>>);

#[derive(Serialize, Deserialize, Clone)]
#[serde(default)]
struct LauncherSettings {
    username: String,
    ram: u32,
    window_mode: String,
    discord_rpc: bool,
    close_on_launch: bool,
}

impl Default for LauncherSettings {
    fn default() -> Self {
        Self {
            username: "Player".into(),
            ram: 4,
            window_mode: "Windowed".into(),
            discord_rpc: true,
            close_on_launch: false,
        }
    }
}

/// Everything the launcher writes: settings, accounts, and the game install (see game::Dirs).
/// PLUTONIUM_HOME overrides the platform app-data dir (handy for testing a clean install).
fn data_dir(app: &AppHandle) -> Result<PathBuf, String> {
    let dir = match std::env::var("PLUTONIUM_HOME") {
        Ok(home) => PathBuf::from(home),
        Err(_) => app.path().app_data_dir().map_err(|e| e.to_string())?,
    };
    fs::create_dir_all(&dir).map_err(|e| format!("{}: {}", dir.display(), e))?;
    Ok(dir)
}

fn settings_path(app: &AppHandle) -> Result<PathBuf, String> {
    Ok(data_dir(app)?.join("launcher_settings.json"))
}

fn accounts_path(app: &AppHandle) -> Result<PathBuf, String> {
    Ok(data_dir(app)?.join("accounts.txt"))
}

fn read_settings(app: &AppHandle) -> LauncherSettings {
    settings_path(app)
        .ok()
        .and_then(|p| fs::read_to_string(p).ok())
        .and_then(|data| serde_json::from_str(&data).ok())
        .unwrap_or_default()
}

#[tauri::command]
fn load_accounts(app: AppHandle) -> Vec<String> {
    match accounts_path(&app).and_then(|p| fs::read_to_string(p).map_err(|e| e.to_string())) {
        Ok(data) => data.lines().map(|l| l.trim().to_string()).filter(|l| !l.is_empty()).collect(),
        Err(_) => Vec::new(),
    }
}

#[tauri::command]
fn save_accounts(app: AppHandle, accounts: Vec<String>) -> Result<(), String> {
    let content: String = accounts.iter().map(|a| format!("{}\n", a)).collect();
    fs::write(accounts_path(&app)?, content).map_err(|e| e.to_string())
}

#[tauri::command]
fn load_settings(app: AppHandle) -> LauncherSettings {
    read_settings(&app)
}

#[tauri::command]
fn save_settings(app: AppHandle, settings: LauncherSettings) -> Result<(), String> {
    let json = serde_json::to_string_pretty(&settings).map_err(|e| e.to_string())?;
    fs::write(settings_path(&app)?, json).map_err(|e| e.to_string())
}

#[tauri::command]
fn close_launcher(app: AppHandle) {
    app.exit(0);
}

fn game_running(state: &State<'_, GameProcess>) -> Result<Option<u32>, String> {
    let mut proc = state.0.lock().map_err(|e| e.to_string())?;
    if let Some(child) = proc.as_mut() {
        match child.try_wait() {
            Ok(None) => return Ok(Some(child.id())),
            Ok(Some(_)) => *proc = None,
            Err(e) => return Err(e.to_string()),
        }
    }
    Ok(None)
}

/// Download/update whatever is missing (progress arrives as `launcher-progress` events),
/// then start the game with the saved settings. Returns the game's pid.
#[tauri::command]
async fn launch_game(app: AppHandle, state: State<'_, GameProcess>) -> Result<u32, String> {
    if game_running(&state)?.is_some() {
        return Err("Game is already running".into());
    }
    let settings = read_settings(&app);
    let dirs = game::Dirs::new(data_dir(&app)?);
    let emitter = app.clone();
    let report = move |stage: &str, done: u64, total: u64| {
        let _ = emitter.emit("launcher-progress", game::Progress { stage, done, total });
    };
    let installed = game::prepare(&report, &dirs).await?;
    let child = game::launch(
        &dirs,
        &installed,
        &game::LaunchOptions {
            username: settings.username,
            ram_gb: settings.ram,
            window_mode: settings.window_mode,
            discord_rpc: settings.discord_rpc,
        },
    )?;
    let pid = child.id();
    *state.0.lock().map_err(|e| e.to_string())? = Some(child);
    Ok(pid)
}

#[tauri::command]
fn stop_game(state: State<'_, GameProcess>) -> Result<(), String> {
    let mut proc = state.0.lock().map_err(|e| e.to_string())?;
    if let Some(child) = proc.as_mut() {
        child.kill().map_err(|e| e.to_string())?;
        child.wait().ok();
    }
    *proc = None;
    Ok(())
}

#[tauri::command]
fn is_game_running(state: State<'_, GameProcess>) -> Result<Option<u32>, String> {
    game_running(&state)
}

/// Where saves, screenshots, resource packs and logs live.
#[tauri::command]
fn get_game_dir(app: AppHandle) -> Result<String, String> {
    Ok(game::Dirs::new(data_dir(&app)?).game.display().to_string())
}

#[cfg_attr(mobile, tauri::mobile_entry_point)]
pub fn run() {
    tauri::Builder::default()
        .manage(GameProcess(Mutex::new(None)))
        .invoke_handler(tauri::generate_handler![
            launch_game,
            stop_game,
            is_game_running,
            load_settings,
            save_settings,
            load_accounts,
            save_accounts,
            get_game_dir,
            close_launcher,
        ])
        .setup(|app| {
            app.handle().plugin(
                tauri_plugin_log::Builder::default()
                    .level(log::LevelFilter::Info)
                    .build(),
            )?;
            Ok(())
        })
        .run(tauri::generate_context!())
        .expect("error while running tauri application");
}
