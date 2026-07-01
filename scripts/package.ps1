# scripts/package.ps1
#
# Windows equivalent of scripts/package.sh: full local production build pipeline
# (docs/design/01-architecture.md §7): backend fat jar -> jlink runtime -> copy jar
# into the Tauri resource dir -> tauri build.
#
# Cannot be exercised in this environment (no Windows host) -- written carefully
# against package.sh; verify on a real Windows runner before relying on it in CI/release.
#
# Usage: pwsh scripts/package.ps1

$ErrorActionPreference = "Stop"

$ScriptDir = Split-Path -Parent $MyInvocation.MyCommand.Path
$RepoRoot = Split-Path -Parent $ScriptDir

Write-Host "== package.ps1: 1/4 backend jar =="
Push-Location (Join-Path $RepoRoot "backend")
try {
    & .\mvnw.cmd -q package -DskipTests
    if ($LASTEXITCODE -ne 0) { throw "mvnw package failed with exit code $LASTEXITCODE" }
}
finally {
    Pop-Location
}
Write-Host "Built $(Join-Path $RepoRoot 'backend\target\backend.jar')"

Write-Host "== package.ps1: 2/4 jlink runtime =="
& pwsh -File (Join-Path $ScriptDir "build-jre.ps1")
if ($LASTEXITCODE -ne 0) { throw "build-jre.ps1 failed with exit code $LASTEXITCODE" }

Write-Host "== package.ps1: 3/4 copy backend.jar into resources =="
$ResourcesBackendDir = Join-Path $RepoRoot "desktop\src-tauri\resources\backend"
New-Item -ItemType Directory -Force -Path $ResourcesBackendDir | Out-Null
Copy-Item -Path (Join-Path $RepoRoot "backend\target\backend.jar") -Destination (Join-Path $ResourcesBackendDir "backend.jar") -Force
Write-Host "Copied to $(Join-Path $ResourcesBackendDir 'backend.jar')"

Write-Host "== package.ps1: 4/4 tauri build =="
$SrcTauriDir = Join-Path $RepoRoot "desktop\src-tauri"
if (Test-Path $SrcTauriDir) {
    Push-Location (Join-Path $RepoRoot "desktop")
    try {
        npm run tauri build
        if ($LASTEXITCODE -ne 0) { throw "npm run tauri build failed with exit code $LASTEXITCODE" }
    }
    finally {
        Pop-Location
    }
}
else {
    Write-Host "NOTICE: desktop\src-tauri does not exist yet (Tauri shell not scaffolded) -- skipping 'npm run tauri build'."
    Write-Host "        Another agent is creating it; re-run scripts/package.ps1 once it exists."
}

Write-Host "== package.ps1 done =="
