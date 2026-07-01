# scripts/smoke-backend.ps1
#
# Windows equivalent of scripts/smoke-backend.sh: packaged-runtime smoke test
# (docs/design/01-architecture.md §3/§7, ADR-003). Boots the actual jlinked runtime +
# fat jar exactly as the Tauri shell would spawn them, and asserts the full handshake +
# shutdown protocol works end to end.
#
# Cannot be exercised in this environment (no Windows host) -- written carefully
# against smoke-backend.sh; verify on a real Windows runner before relying on it in CI.
#
# Usage: pwsh scripts/smoke-backend.ps1
# Exits non-zero (throws) on ANY failure.

$ErrorActionPreference = "Stop"

$ScriptDir = Split-Path -Parent $MyInvocation.MyCommand.Path
$RepoRoot = Split-Path -Parent $ScriptDir

$JavaBin = Join-Path $RepoRoot "desktop\src-tauri\resources\jre\bin\java.exe"
$BackendJar = Join-Path $RepoRoot "desktop\src-tauri\resources\backend\backend.jar"
$GpTokenValue = "smoke"

function Fail {
    param([string]$Message)
    Write-Error "SMOKE FAIL: $Message"
    exit 1
}

if (-not (Test-Path $JavaBin)) {
    Fail "packaged java not found at $JavaBin (run scripts/build-jre.ps1 / scripts/package.ps1 first)"
}
if (-not (Test-Path $BackendJar)) {
    Fail "packaged backend.jar not found at $BackendJar (run scripts/package.ps1 first)"
}

$DataDir = Join-Path ([System.IO.Path]::GetTempPath()) ("gp-smoke-data-" + [System.Guid]::NewGuid().ToString("N"))
New-Item -ItemType Directory -Force -Path $DataDir | Out-Null
$StdoutLog = Join-Path ([System.IO.Path]::GetTempPath()) ("gp-smoke-stdout-" + [System.Guid]::NewGuid().ToString("N") + ".log")
$StderrLog = Join-Path ([System.IO.Path]::GetTempPath()) ("gp-smoke-stderr-" + [System.Guid]::NewGuid().ToString("N") + ".log")

$proc = $null

function Cleanup {
    if ($proc -and -not $proc.HasExited) {
        try { $proc.Kill() } catch {}
    }
    Remove-Item -Path $DataDir -Recurse -Force -ErrorAction SilentlyContinue
    Remove-Item -Path $StdoutLog -Force -ErrorAction SilentlyContinue
    Remove-Item -Path $StderrLog -Force -ErrorAction SilentlyContinue
}

try {
    Write-Host "Starting packaged backend: $JavaBin -jar $BackendJar"

    $psi = New-Object System.Diagnostics.ProcessStartInfo
    $psi.FileName = $JavaBin
    $psi.Arguments = "-Duser.timezone=UTC -Dfile.encoding=UTF-8 -jar `"$BackendJar`" --server.port=0 --server.address=127.0.0.1"
    $psi.UseShellExecute = $false
    $psi.RedirectStandardOutput = $true
    $psi.RedirectStandardError = $true
    $psi.CreateNoWindow = $true
    $psi.EnvironmentVariables["GP_TOKEN"] = $GpTokenValue
    $psi.EnvironmentVariables["GP_DATA_DIR"] = $DataDir

    $proc = New-Object System.Diagnostics.Process
    $proc.StartInfo = $psi

    $stdoutBuilder = New-Object System.Text.StringBuilder
    $stderrBuilder = New-Object System.Text.StringBuilder
    $stdoutLock = New-Object object

    Register-ObjectEvent -InputObject $proc -EventName OutputDataReceived -Action {
        if ($EventArgs.Data) {
            [System.Threading.Monitor]::Enter($Event.MessageData.Lock)
            try { $Event.MessageData.Builder.AppendLine($EventArgs.Data) | Out-Null }
            finally { [System.Threading.Monitor]::Exit($Event.MessageData.Lock) }
        }
    } -MessageData (@{ Builder = $stdoutBuilder; Lock = $stdoutLock }) | Out-Null

    Register-ObjectEvent -InputObject $proc -EventName ErrorDataReceived -Action {
        if ($EventArgs.Data) {
            [System.Threading.Monitor]::Enter($Event.MessageData.Lock)
            try { $Event.MessageData.Builder.AppendLine($EventArgs.Data) | Out-Null }
            finally { [System.Threading.Monitor]::Exit($Event.MessageData.Lock) }
        }
    } -MessageData (@{ Builder = $stderrBuilder; Lock = $stdoutLock }) | Out-Null

    $proc.Start() | Out-Null
    $proc.BeginOutputReadLine()
    $proc.BeginErrorReadLine()

    Write-Host "Backend PID: $($proc.Id)"
    Write-Host "Waiting up to 30s for the GP_READY line..."

    $port = $null
    $deadline = (Get-Date).AddSeconds(30)
    while ((Get-Date) -lt $deadline) {
        if ($proc.HasExited) {
            Write-Host "---- backend stdout ----`n$($stdoutBuilder.ToString())"
            Write-Host "---- backend stderr ----`n$($stderrBuilder.ToString())"
            Fail "backend process exited before printing GP_READY"
        }
        $readyLine = ($stdoutBuilder.ToString() -split "`n") | Where-Object { $_ -match '^GP_READY ' } | Select-Object -First 1
        if ($readyLine -and ($readyLine -match '"port":(\d+)')) {
            $port = $Matches[1]
            break
        }
        Start-Sleep -Milliseconds 250
    }

    if (-not $port) {
        Write-Host "---- backend stdout ----`n$($stdoutBuilder.ToString())"
        Write-Host "---- backend stderr ----`n$($stderrBuilder.ToString())"
        Fail "timed out waiting 30s for the GP_READY line"
    }
    Write-Host "GP_READY parsed: port=$port"

    $baseUrl = "http://127.0.0.1:$port"
    $headers = @{ "X-GP-Token" = $GpTokenValue }

    $healthResponse = $null
    try {
        $healthResponse = Invoke-RestMethod -Uri "$baseUrl/api/health" -Headers $headers -TimeoutSec 5
    }
    catch {
        Write-Host "---- backend stdout ----`n$($stdoutBuilder.ToString())"
        Write-Host "---- backend stderr ----`n$($stderrBuilder.ToString())"
        Fail "GET /api/health failed: $_"
    }
    Write-Host "Health response: $($healthResponse | ConvertTo-Json -Compress)"
    if ($healthResponse.status -ne "UP") {
        Fail "unexpected /api/health response: $($healthResponse | ConvertTo-Json -Compress)"
    }

    Write-Host "Requesting graceful shutdown..."
    try {
        Invoke-RestMethod -Uri "$baseUrl/api/system/shutdown" -Method Post -Headers $headers -TimeoutSec 5 | Out-Null
    }
    catch {
        Fail "POST /api/system/shutdown request failed: $_"
    }

    Write-Host "Waiting up to 10s for the process to exit..."
    $exited = $proc.WaitForExit(10000)
    if (-not $exited) {
        Write-Host "---- backend stdout ----`n$($stdoutBuilder.ToString())"
        Write-Host "---- backend stderr ----`n$($stderrBuilder.ToString())"
        Fail "backend process (PID $($proc.Id)) did not exit within 10s of the shutdown request"
    }

    Write-Host "SMOKE OK: GP_READY handshake + /api/health + graceful shutdown all succeeded (port=$port)"
}
finally {
    Cleanup
}
