//! Spawn / handshake / shutdown protocol for the bundled Java backend.
//!
//! Implements docs/design/01-architecture.md §4 exactly:
//! 1. Generate a random session token, resolve the app data dir (+ logs/ subdir).
//! 2. Resolve `resources/jre/bin/java[.exe]` and `resources/backend/backend.jar` via Tauri's
//!    resource resolver (with a dev-mode fallback to `<cwd>/resources`).
//! 3. Restore executable bits on the bundled JRE on Unix (Tauri's resource copy can strip them).
//! 4. Spawn `java -Duser.timezone=UTC -Dfile.encoding=UTF-8 -jar backend.jar
//!    --server.port=0 --server.address=127.0.0.1`, with `GP_TOKEN` / `GP_DATA_DIR` /
//!    `GP_PARENT_PID` env vars, stdout/stderr piped (Windows: `CREATE_NO_WINDOW`).
//! 5. Read stdout lines for up to 30s for a `GP_READY {"port":...}` line, then poll
//!    `GET /api/health` (header `X-GP-Token`) every 500ms for up to 10s until `{"status":"UP"}`.
//! 6. On success, store `{base_url, token}` in managed state (exposed via `get_backend_info`).
//! 7. On failure, the caller emits `backend-failed` with the last 50 captured log lines and the
//!    `backend.log` path.
//! 8. On shutdown, `POST /api/system/shutdown`, wait up to 3s, then kill the child if still alive.

use serde::Serialize;
use std::collections::VecDeque;
use std::io::{BufRead, BufReader, Read, Write};
use std::net::{SocketAddr, TcpStream};
use std::path::{Path, PathBuf};
use std::process::{Child, Command, Stdio};
use std::sync::{mpsc, Arc, Condvar, Mutex};
use std::thread;
use std::time::{Duration, Instant};
use tauri::{AppHandle, Manager};

/// Deadline for the `GP_READY` stdout handshake line (design doc §4, step 4).
const HANDSHAKE_TIMEOUT: Duration = Duration::from_secs(30);
/// Overall deadline for the post-handshake `/api/health` poll (design doc §4, step 4).
const HEALTH_POLL_TIMEOUT: Duration = Duration::from_secs(10);
/// Interval between `/api/health` poll attempts.
const HEALTH_POLL_INTERVAL: Duration = Duration::from_millis(500);
/// Grace period for the child to exit after `POST /api/system/shutdown` before a hard kill
/// (design doc §4, step 5).
const SHUTDOWN_GRACE: Duration = Duration::from_secs(3);
/// How long `get_backend_info` will block waiting for the in-flight spawn/handshake attempt.
const GET_INFO_WAIT: Duration = Duration::from_secs(40);
/// Ring-buffer size for captured stdout/stderr lines surfaced on failure.
const LOG_BUFFER_CAP: usize = 50;

/// Handshake result handed to the frontend: the loopback base URL and the per-session token.
#[derive(Clone, Debug, Serialize)]
pub struct BackendInfo {
    pub base_url: String,
    pub token: String,
}

/// Reported to the frontend (via the `backend-failed` event, and as the `get_backend_info` /
/// `retry_backend` command error) when spawn or handshake does not succeed.
#[derive(Clone, Debug, Serialize)]
pub struct BackendFailure {
    pub reason: String,
    pub log_lines: Vec<String>,
    pub log_path: String,
}

struct Inner {
    child: Option<Child>,
    info: Option<BackendInfo>,
    failure: Option<BackendFailure>,
    log_path: Option<PathBuf>,
    /// True once the current spawn attempt has produced a definitive outcome (success or
    /// failure); reset to false by `begin_attempt` at the start of the next attempt.
    settled: bool,
}

/// Shared, thread-safe handle to the backend child process and its handshake outcome. Managed as
/// `Arc<BackendState>` Tauri state so both the setup thread and the `get_backend_info` /
/// `retry_backend` commands can observe/drive it.
pub struct BackendState {
    inner: Mutex<Inner>,
    condvar: Condvar,
}

impl Default for BackendState {
    fn default() -> Self {
        Self::new()
    }
}

impl BackendState {
    pub fn new() -> Self {
        BackendState {
            inner: Mutex::new(Inner {
                child: None,
                info: None,
                failure: None,
                log_path: None,
                settled: false,
            }),
            condvar: Condvar::new(),
        }
    }

    fn begin_attempt(&self) {
        let mut inner = self.inner.lock().expect("backend state mutex poisoned");
        inner.settled = false;
        inner.info = None;
        inner.failure = None;
    }

    fn set_log_path(&self, path: PathBuf) {
        let mut inner = self.inner.lock().expect("backend state mutex poisoned");
        inner.log_path = Some(path);
    }

    fn finish_success(&self, child: Child, info: BackendInfo) -> Result<BackendInfo, BackendFailure> {
        let mut inner = self.inner.lock().expect("backend state mutex poisoned");
        inner.child = Some(child);
        inner.info = Some(info.clone());
        inner.failure = None;
        inner.settled = true;
        self.condvar.notify_all();
        Ok(info)
    }

    fn finish_failure(&self, failure: BackendFailure) -> Result<BackendInfo, BackendFailure> {
        let mut inner = self.inner.lock().expect("backend state mutex poisoned");
        inner.child = None;
        inner.info = None;
        inner.failure = Some(failure.clone());
        inner.settled = true;
        self.condvar.notify_all();
        Err(failure)
    }

    /// Blocks (up to `timeout`) until the current attempt has settled, then returns the info or
    /// a short error reason. Used by the `get_backend_info` command.
    pub fn wait_for_info(&self, timeout: Duration) -> Result<BackendInfo, String> {
        let inner = self.inner.lock().expect("backend state mutex poisoned");
        let (inner, result) = self
            .condvar
            .wait_timeout_while(inner, timeout, |i| !i.settled)
            .expect("backend state mutex poisoned");
        if result.timed_out() {
            return Err("timed out waiting for the backend to become ready".to_string());
        }
        match (&inner.info, &inner.failure) {
            (Some(info), _) => Ok(info.clone()),
            (None, Some(failure)) => Err(failure.reason.clone()),
            (None, None) => Err("backend startup did not complete".to_string()),
        }
    }

    /// Kills any currently-tracked child process (used before a retry re-spawns).
    pub fn kill_child_if_any(&self) {
        let mut inner = self.inner.lock().expect("backend state mutex poisoned");
        if let Some(mut child) = inner.child.take() {
            let _ = child.kill();
            let _ = child.wait();
        }
    }

    /// Graceful shutdown: `POST /api/system/shutdown`, wait up to `SHUTDOWN_GRACE`, then kill.
    pub fn shutdown(&self) {
        let info = {
            let inner = self.inner.lock().expect("backend state mutex poisoned");
            inner.info.clone()
        };
        if let Some(info) = &info {
            if let Some(port) = base_url_port(&info.base_url) {
                let _ = http_request(
                    port,
                    "POST",
                    "/api/system/shutdown",
                    &info.token,
                    Duration::from_secs(2),
                );
            }
        }

        let deadline = Instant::now() + SHUTDOWN_GRACE;
        loop {
            let exited = {
                let mut inner = self.inner.lock().expect("backend state mutex poisoned");
                match &mut inner.child {
                    Some(child) => matches!(child.try_wait(), Ok(Some(_))),
                    None => true,
                }
            };
            if exited {
                let mut inner = self.inner.lock().expect("backend state mutex poisoned");
                inner.child = None;
                return;
            }
            if Instant::now() >= deadline {
                break;
            }
            thread::sleep(Duration::from_millis(100));
        }

        self.kill_child_if_any();
    }
}

fn base_url_port(base_url: &str) -> Option<u16> {
    base_url.rsplit(':').next()?.parse().ok()
}

/// Runs the full spawn + handshake sequence and records the outcome in `state`. Blocking; safe to
/// call from a background thread (normal startup / retry) or directly (`--smoke`).
pub fn spawn_and_handshake(app: &AppHandle, state: &BackendState) -> Result<BackendInfo, BackendFailure> {
    state.begin_attempt();

    let token = generate_token();

    let data_dir = match resolve_data_dir(app) {
        Ok(dir) => dir,
        Err(reason) => {
            return state.finish_failure(BackendFailure {
                reason,
                log_lines: Vec::new(),
                log_path: String::new(),
            })
        }
    };
    let log_path = data_dir.join("logs").join("backend.log");
    state.set_log_path(log_path.clone());
    let log_path_str = log_path.display().to_string();

    let resources_root = match locate_resources_root(app) {
        Ok(root) => root,
        Err(reason) => {
            return state.finish_failure(BackendFailure {
                reason,
                log_lines: Vec::new(),
                log_path: log_path_str,
            })
        }
    };

    #[cfg(unix)]
    restore_exec_bits(&resources_root);

    let java_path = java_binary_path(&resources_root);
    let jar_path = resources_root.join("backend").join("backend.jar");

    let mut command = Command::new(&java_path);
    command
        .arg("-Duser.timezone=UTC")
        .arg("-Dfile.encoding=UTF-8")
        .arg("-jar")
        .arg(&jar_path)
        .arg("--server.port=0")
        .arg("--server.address=127.0.0.1")
        .env("GP_TOKEN", &token)
        .env("GP_DATA_DIR", &data_dir)
        .env("GP_PARENT_PID", std::process::id().to_string())
        .stdout(Stdio::piped())
        .stderr(Stdio::piped());
    #[cfg(windows)]
    {
        use std::os::windows::process::CommandExt;
        const CREATE_NO_WINDOW: u32 = 0x0800_0000;
        command.creation_flags(CREATE_NO_WINDOW);
    }

    let mut child = match command.spawn() {
        Ok(child) => child,
        Err(err) => {
            return state.finish_failure(BackendFailure {
                reason: format!(
                    "failed to launch bundled java at {}: {err}",
                    java_path.display()
                ),
                log_lines: Vec::new(),
                log_path: log_path_str,
            })
        }
    };

    let log_buffer: Arc<Mutex<VecDeque<String>>> =
        Arc::new(Mutex::new(VecDeque::with_capacity(LOG_BUFFER_CAP)));

    let port = match capture_output_and_await_ready(&mut child, &log_buffer, HANDSHAKE_TIMEOUT) {
        Ok(port) => port,
        Err(reason) => {
            kill_child(&mut child);
            return state.finish_failure(BackendFailure {
                reason,
                log_lines: drain_logs(&log_buffer),
                log_path: log_path_str,
            });
        }
    };

    if let Err(reason) = poll_health(port, &token, HEALTH_POLL_TIMEOUT, HEALTH_POLL_INTERVAL) {
        kill_child(&mut child);
        return state.finish_failure(BackendFailure {
            reason,
            log_lines: drain_logs(&log_buffer),
            log_path: log_path_str,
        });
    }

    let info = BackendInfo {
        base_url: format!("http://127.0.0.1:{port}"),
        token,
    };
    state.finish_success(child, info)
}

fn resolve_data_dir(app: &AppHandle) -> Result<PathBuf, String> {
    let data_dir = app
        .path()
        .app_data_dir()
        .map_err(|err| format!("could not resolve app data dir: {err}"))?;
    std::fs::create_dir_all(data_dir.join("logs"))
        .map_err(|err| format!("could not create data dir {}: {err}", data_dir.display()))?;
    Ok(data_dir)
}

/// Locates the directory that contains `jre/` and `backend/`, trying (in order): the Tauri
/// resource dir's `resources/` subfolder (production bundle layout, per `bundle.resources:
/// ["resources/"]`), the resource dir itself, and finally `<cwd>/resources` as a dev-mode
/// fallback for `cargo run -- --smoke` run from `desktop/src-tauri`.
fn locate_resources_root(app: &AppHandle) -> Result<PathBuf, String> {
    let mut candidates: Vec<PathBuf> = Vec::new();
    if let Ok(resource_dir) = app.path().resource_dir() {
        candidates.push(resource_dir.join("resources"));
        candidates.push(resource_dir);
    }
    if let Ok(cwd) = std::env::current_dir() {
        candidates.push(cwd.join("resources"));
    }

    for candidate in &candidates {
        if candidate.join("backend").join("backend.jar").exists() {
            return Ok(candidate.clone());
        }
    }

    let looked = candidates
        .iter()
        .map(|p| p.display().to_string())
        .collect::<Vec<_>>()
        .join(", ");
    Err(format!(
        "could not locate bundled resources (looked in: [{looked}]); expected \
         resources/jre/bin/java{} and resources/backend/backend.jar",
        if cfg!(windows) { ".exe" } else { "" }
    ))
}

fn java_binary_path(resources_root: &Path) -> PathBuf {
    let bin = resources_root.join("jre").join("bin");
    if cfg!(windows) {
        bin.join("java.exe")
    } else {
        bin.join("java")
    }
}

/// Restores `0o755` on everything in `jre/bin/` and on `jre/lib/jspawnhelper`, since Tauri's
/// resource copy can strip executable bits (ADR-003).
#[cfg(unix)]
fn restore_exec_bits(resources_root: &Path) {
    use std::os::unix::fs::PermissionsExt;

    let bin_dir = resources_root.join("jre").join("bin");
    if let Ok(entries) = std::fs::read_dir(&bin_dir) {
        for entry in entries.flatten() {
            let path = entry.path();
            if path.is_file() {
                let _ = std::fs::set_permissions(&path, std::fs::Permissions::from_mode(0o755));
            }
        }
    }

    let jspawnhelper = resources_root.join("jre").join("lib").join("jspawnhelper");
    if jspawnhelper.exists() {
        let _ = std::fs::set_permissions(&jspawnhelper, std::fs::Permissions::from_mode(0o755));
    }
}

fn kill_child(child: &mut Child) {
    let _ = child.kill();
    let _ = child.wait();
}

fn push_log(buffer: &Arc<Mutex<VecDeque<String>>>, line: String) {
    if let Ok(mut guard) = buffer.lock() {
        if guard.len() >= LOG_BUFFER_CAP {
            guard.pop_front();
        }
        guard.push_back(line);
    }
}

fn drain_logs(buffer: &Arc<Mutex<VecDeque<String>>>) -> Vec<String> {
    buffer
        .lock()
        .map(|guard| guard.iter().cloned().collect())
        .unwrap_or_default()
}

/// Spawns reader threads for the child's stdout/stderr (feeding the shared log ring buffer) and
/// blocks until a `GP_READY {"port":...}` line arrives on stdout, the deadline elapses, or the
/// process exits without ever printing one.
fn capture_output_and_await_ready(
    child: &mut Child,
    log_buffer: &Arc<Mutex<VecDeque<String>>>,
    timeout: Duration,
) -> Result<u16, String> {
    let stdout = child.stdout.take().expect("child stdout was not piped");
    let stderr = child.stderr.take().expect("child stderr was not piped");

    let (ready_tx, ready_rx) = mpsc::channel::<String>();
    {
        let log_buffer = log_buffer.clone();
        thread::spawn(move || {
            let reader = BufReader::new(stdout);
            for line in reader.lines().map_while(Result::ok) {
                push_log(&log_buffer, format!("[stdout] {line}"));
                if ready_tx.send(line).is_err() {
                    break;
                }
            }
        });
    }
    {
        let log_buffer = log_buffer.clone();
        thread::spawn(move || {
            let reader = BufReader::new(stderr);
            for line in reader.lines().map_while(Result::ok) {
                push_log(&log_buffer, format!("[stderr] {line}"));
            }
        });
    }

    let deadline = Instant::now() + timeout;
    loop {
        let remaining = deadline.saturating_duration_since(Instant::now());
        if remaining.is_zero() {
            return Err(format!("backend did not print GP_READY within {timeout:?}"));
        }
        match ready_rx.recv_timeout(remaining) {
            Ok(line) => {
                if let Some(json_part) = line.strip_prefix("GP_READY ") {
                    if let Ok(value) = serde_json::from_str::<serde_json::Value>(json_part) {
                        if let Some(port) = value.get("port").and_then(|v| v.as_u64()) {
                            return Ok(port as u16);
                        }
                    }
                }
            }
            Err(mpsc::RecvTimeoutError::Timeout) => {
                return Err(format!("backend did not print GP_READY within {timeout:?}"));
            }
            Err(mpsc::RecvTimeoutError::Disconnected) => {
                return Err("backend process exited before printing GP_READY".to_string());
            }
        }
    }
}

/// Polls `GET /api/health` (with the `X-GP-Token` header) every `interval` until it reports
/// `{"status":"UP"}` or `overall_timeout` elapses.
fn poll_health(
    port: u16,
    token: &str,
    overall_timeout: Duration,
    interval: Duration,
) -> Result<(), String> {
    let deadline = Instant::now() + overall_timeout;
    loop {
        let attempt_result = http_request(port, "GET", "/api/health", token, Duration::from_secs(2));
        let last_error = match &attempt_result {
            Ok((200, body)) => {
                let up = serde_json::from_str::<serde_json::Value>(body)
                    .ok()
                    .and_then(|v| v.get("status").and_then(|s| s.as_str()).map(|s| s == "UP"))
                    .unwrap_or(false);
                if up {
                    return Ok(());
                }
                format!("health responded 200 with unexpected body: {body}")
            }
            Ok((status, body)) => format!("health responded {status}: {body}"),
            Err(err) => format!("health request failed: {err}"),
        };
        if Instant::now() >= deadline {
            return Err(format!(
                "backend did not report healthy within {overall_timeout:?} (last: {last_error})"
            ));
        }
        thread::sleep(interval);
    }
}

/// Minimal hand-rolled HTTP/1.1 client over a plain `TcpStream` (loopback only, no TLS needed) —
/// kept dependency-free per docs/design/01-architecture.md §4.
fn http_request(
    port: u16,
    method: &str,
    path: &str,
    token: &str,
    timeout: Duration,
) -> std::io::Result<(u16, String)> {
    let addr: SocketAddr = format!("127.0.0.1:{port}")
        .parse()
        .map_err(|err| std::io::Error::new(std::io::ErrorKind::InvalidInput, format!("{err}")))?;
    let mut stream = TcpStream::connect_timeout(&addr, timeout)?;
    stream.set_read_timeout(Some(timeout))?;
    stream.set_write_timeout(Some(timeout))?;

    let request = format!(
        "{method} {path} HTTP/1.1\r\nHost: 127.0.0.1:{port}\r\nX-GP-Token: {token}\r\nConnection: close\r\nContent-Length: 0\r\n\r\n"
    );
    stream.write_all(request.as_bytes())?;
    stream.flush()?;

    let mut raw = Vec::new();
    stream.read_to_end(&mut raw)?;

    let header_end = find_subslice(&raw, b"\r\n\r\n").unwrap_or(raw.len());
    let head = String::from_utf8_lossy(&raw[..header_end]).to_string();
    let body_start = (header_end + 4).min(raw.len());
    let raw_body = &raw[body_start..];

    // Spring's embedded Tomcat responds with `Transfer-Encoding: chunked` for these small
    // hand-built JSON bodies (it doesn't pre-compute Content-Length), so the chunk framing must
    // be decoded before the body is valid JSON.
    let is_chunked = head.to_ascii_lowercase().contains("transfer-encoding: chunked");
    let body_bytes = if is_chunked {
        decode_chunked(raw_body)
    } else {
        raw_body.to_vec()
    };
    let body = String::from_utf8_lossy(&body_bytes).to_string();

    let status = head
        .lines()
        .next()
        .and_then(|line| line.split_whitespace().nth(1))
        .and_then(|code| code.parse::<u16>().ok())
        .unwrap_or(0);

    Ok((status, body))
}

fn find_subslice(haystack: &[u8], needle: &[u8]) -> Option<usize> {
    haystack
        .windows(needle.len())
        .position(|window| window == needle)
}

/// Decodes an HTTP/1.1 chunked-transfer-encoding body (RFC 9112 §7.1). Chunk extensions are
/// ignored; trailer fields (if any) are discarded once the terminating zero-size chunk is seen.
fn decode_chunked(mut data: &[u8]) -> Vec<u8> {
    let mut out = Vec::new();
    while let Some(line_end) = find_subslice(data, b"\r\n") {
        let size_line = String::from_utf8_lossy(&data[..line_end]);
        let size_str = size_line.split(';').next().unwrap_or("0").trim();
        let Ok(size) = usize::from_str_radix(size_str, 16) else {
            break;
        };
        data = &data[line_end + 2..];
        if size == 0 {
            break;
        }
        if data.len() < size {
            out.extend_from_slice(data);
            break;
        }
        out.extend_from_slice(&data[..size]);
        data = &data[size..];
        if data.starts_with(b"\r\n") {
            data = &data[2..];
        }
    }
    out
}

/// Generates the per-session token: 16 random bytes hex-encoded to a 32-character string
/// (design doc §4, step 1).
fn generate_token() -> String {
    let mut bytes = [0u8; 16];
    getrandom::fill(&mut bytes).expect("failed to obtain OS randomness for the session token");
    bytes.iter().map(|b| format!("{b:02x}")).collect()
}

// ---------------------------------------------------------------------------------------------
// Tauri commands
// ---------------------------------------------------------------------------------------------

/// Returns the current `{base_url, token}` once the (in-flight or already-completed) spawn +
/// handshake attempt has settled. Rejects with a short reason string on failure/timeout.
#[tauri::command]
pub fn get_backend_info(state: tauri::State<'_, Arc<BackendState>>) -> Result<BackendInfo, String> {
    state.wait_for_info(GET_INFO_WAIT)
}

/// Kills any existing backend child, re-runs the full spawn/handshake sequence, and emits
/// `backend-ready` / `backend-failed` with the outcome (mirrors the initial startup flow).
#[tauri::command]
pub fn retry_backend(
    app: AppHandle,
    state: tauri::State<'_, Arc<BackendState>>,
) -> Result<BackendInfo, String> {
    use tauri::Emitter;

    let state = state.inner().clone();
    state.kill_child_if_any();

    match spawn_and_handshake(&app, &state) {
        Ok(info) => {
            let _ = app.emit("backend-ready", &info);
            Ok(info)
        }
        Err(failure) => {
            let _ = app.emit("backend-failed", &failure);
            Err(failure.reason)
        }
    }
}
