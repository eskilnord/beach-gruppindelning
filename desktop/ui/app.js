// Minimal vanilla-JS M0 status page: proves the spawn/handshake/shutdown shell works end to end.
// Replaced by the real frontend at M2 (docs/design/01-architecture.md §9).

const statusLine = document.getElementById("status-line");
const failureDetails = document.getElementById("failure-details");
const logPathEl = document.getElementById("log-path");
const logLinesEl = document.getElementById("log-lines");
const retryButton = document.getElementById("retry-button");

function setPending(message) {
  statusLine.textContent = message;
  statusLine.className = "status-line pending";
  failureDetails.hidden = true;
}

function setOk(message) {
  statusLine.textContent = message;
  statusLine.className = "status-line ok";
  failureDetails.hidden = true;
}

function setError(message, logLines, logPath) {
  statusLine.textContent = message;
  statusLine.className = "status-line error";
  failureDetails.hidden = false;
  logPathEl.textContent = logPath ? `Logg: ${logPath}` : "";
  logLinesEl.textContent =
    Array.isArray(logLines) && logLines.length > 0
      ? logLines.join("\n")
      : "(inga loggrader tillgängliga)";
}

async function checkHealth(info) {
  const response = await fetch(`${info.base_url}/api/health`, {
    headers: { "X-GP-Token": info.token },
  });
  if (!response.ok) {
    throw new Error(`hälsokontroll misslyckades: HTTP ${response.status}`);
  }
  const body = await response.json();
  if (body.status !== "UP") {
    throw new Error(`hälsokontroll rapporterade oväntat status: ${JSON.stringify(body)}`);
  }
}

async function loadBackendInfo() {
  const { invoke } = window.__TAURI__.core;
  setPending("Startar motorn…");
  try {
    const info = await invoke("get_backend_info");
    await checkHealth(info);
    const port = new URL(info.base_url).port;
    setOk(`Motorn är igång ✓ (port ${port})`);
  } catch (err) {
    setError(`Motorn kunde inte startas: ${err}`, [], "");
  }
}

async function retryBackend() {
  retryButton.disabled = true;
  setPending("Försöker igen…");
  try {
    const { invoke } = window.__TAURI__.core;
    const info = await invoke("retry_backend");
    await checkHealth(info);
    const port = new URL(info.base_url).port;
    setOk(`Motorn är igång ✓ (port ${port})`);
  } catch (err) {
    setError(`Motorn kunde inte startas: ${err}`, [], "");
  } finally {
    retryButton.disabled = false;
  }
}

retryButton.addEventListener("click", retryBackend);

window.addEventListener("DOMContentLoaded", () => {
  if (!window.__TAURI__) {
    setError("Denna sida måste köras i Gruppindelning-appen (Tauri saknas).", [], "");
    return;
  }

  const { listen } = window.__TAURI__.event;
  listen("backend-failed", (event) => {
    const { reason, log_lines: logLines, log_path: logPath } = event.payload;
    setError(`Motorn kunde inte startas: ${reason}`, logLines, logPath);
  });
  listen("backend-ready", (event) => {
    const port = new URL(event.payload.base_url).port;
    setOk(`Motorn är igång ✓ (port ${port})`);
  });

  loadBackendInfo();
});
