// Prevents an additional console window on Windows in release builds.
#![cfg_attr(not(debug_assertions), windows_subsystem = "windows")]

mod backend;

use std::sync::Arc;
use tauri::{Emitter, Manager, RunEvent};

fn main() {
    let smoke = std::env::args().any(|arg| arg == "--smoke");

    let state = Arc::new(backend::BackendState::new());

    let mut builder = tauri::Builder::default()
        .plugin(tauri_plugin_dialog::init())
        .plugin(tauri_plugin_fs::init())
        .plugin(tauri_plugin_single_instance::init(|app, _args, _cwd| {
            // Second launch: focus the existing window instead of spawning another backend
            // (docs/design/01-architecture.md §4, failure modes / "two app instances").
            if let Some(window) = app.get_webview_window("main") {
                let _ = window.unminimize();
                let _ = window.set_focus();
            }
        }))
        .manage(state.clone())
        .invoke_handler(tauri::generate_handler![
            backend::get_backend_info,
            backend::retry_backend
        ]);

    if !smoke {
        builder = builder.setup(|app| {
            let handle = app.handle().clone();
            let state = app.state::<Arc<backend::BackendState>>().inner().clone();
            std::thread::spawn(move || match backend::spawn_and_handshake(&handle, &state) {
                Ok(info) => {
                    let _ = handle.emit("backend-ready", &info);
                }
                Err(failure) => {
                    let _ = handle.emit("backend-failed", &failure);
                }
            });
            Ok(())
        });
    }

    let app = builder
        .build(tauri::generate_context!())
        .expect("error while building the Tauri application");

    if smoke {
        // `--smoke`: run the full spawn+handshake+health sequence without ever creating a
        // window (windows are only created inside `App::run`, which we deliberately never
        // call here), then shut the backend down and exit 0/1 for CI to assert on.
        match backend::spawn_and_handshake(app.handle(), &state) {
            Ok(info) => {
                println!(
                    "SMOKE_OK {}",
                    serde_json::to_string(&info).unwrap_or_default()
                );
                state.shutdown();
                std::process::exit(0);
            }
            Err(failure) => {
                println!("SMOKE_FAIL {}", failure.reason);
                // Diagnostics for CI: the captured child output is the only way to see
                // WHY the backend died on a runner we can't inspect interactively.
                eprintln!("---- captured backend output ({} lines) ----", failure.log_lines.len());
                for line in &failure.log_lines {
                    eprintln!("{line}");
                }
                eprintln!("---- backend log path: {} ----", failure.log_path);
                if let Ok(log) = std::fs::read_to_string(&failure.log_path) {
                    let tail: Vec<&str> = log.lines().rev().take(50).collect();
                    for line in tail.iter().rev() {
                        eprintln!("{line}");
                    }
                }
                state.kill_child_if_any();
                std::process::exit(1);
            }
        }
    }

    app.run(move |app_handle, event| {
        if let RunEvent::ExitRequested { api, .. } = event {
            // Graceful shutdown on normal app close (docs/design/01-architecture.md §4, step 5):
            // prevent the default exit, shut the backend down synchronously (bounded by the 3s
            // grace period inside `BackendState::shutdown`), then exit for real.
            api.prevent_exit();
            state.shutdown();
            app_handle.exit(0);
        }
    });
}
