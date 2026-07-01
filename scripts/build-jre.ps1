# scripts/build-jre.ps1
#
# Windows equivalent of scripts/build-jre.sh: builds the jlink custom-runtime resource
# directory the Tauri shell bundles next to the fat jar
# (docs/design/01-architecture.md §3, docs/adr/003-tauri-jlink-resources.md).
#
# Mirrors build-jre.sh's logic exactly (same cache layout under .cache/jdks/, same
# jlink flags, same jdeps drift check), minus macOS ad-hoc signing, which does not
# apply on Windows. Cannot be exercised in this environment (no Windows host) —
# written carefully against the bash script; verify on a real Windows runner before
# relying on it in CI/release.
#
# Usage:
#   pwsh scripts/build-jre.ps1 [-Target <os-arch>]
#
#   -Target   One of: windows-x64 | mac-aarch64 | mac-x64. Defaults to windows-x64
#             (this script's only realistic host). Non-Windows targets are supported
#             only in the sense that jmods get downloaded and jlink still runs — no
#             codesign step exists on any platform in this script.
#
# Requires $env:JAVA_HOME to point at a Java 21 JDK (its jlink.exe links the runtime).

[CmdletBinding()]
param(
    [string]$Target = ""
)

$ErrorActionPreference = "Stop"

$ScriptDir = Split-Path -Parent $MyInvocation.MyCommand.Path
$RepoRoot = Split-Path -Parent $ScriptDir

# --- Load scripts/jre.env (plain KEY="value" lines + '#' comments, shared with the
#     bash scripts) -----------------------------------------------------------------
function Read-JreEnv {
    param([string]$Path)

    $result = @{}
    foreach ($line in Get-Content -Path $Path) {
        $trimmed = $line.Trim()
        if ($trimmed -eq "" -or $trimmed.StartsWith("#")) {
            continue
        }
        if ($trimmed -match '^([A-Z_][A-Z0-9_]*)="(.*)"$') {
            $result[$Matches[1]] = $Matches[2]
        }
    }
    return $result
}

$JreEnv = Read-JreEnv -Path (Join-Path $ScriptDir "jre.env")
$TemurinVersion = $JreEnv["TEMURIN_VERSION"]
$JlinkModules = $JreEnv["JLINK_MODULES"]

if (-not $TemurinVersion -or -not $JlinkModules) {
    throw "Failed to parse TEMURIN_VERSION / JLINK_MODULES from scripts/jre.env"
}

# ---------------------------------------------------------------------------
# 1. Resolve the target platform + its Adoptium os/arch pair.
# ---------------------------------------------------------------------------

function Get-CurrentPlatformTarget {
    # This script's only realistic host is Windows x64; Adoptium does not ship a
    # windows-aarch64 "normal" JDK build as of the pinned version, so windows-x64 is
    # the sole Windows default.
    if ($IsWindows -or ($env:OS -eq "Windows_NT")) {
        return "windows-x64"
    }
    throw "build-jre.ps1 is the Windows build script; run build-jre.sh on macOS instead."
}

if ([string]::IsNullOrEmpty($Target)) {
    $Target = Get-CurrentPlatformTarget
}
$CurrentTarget = $null
try { $CurrentTarget = Get-CurrentPlatformTarget } catch { $CurrentTarget = $null }

function Get-TargetOsArch {
    param([string]$TargetName)
    switch ($TargetName) {
        "windows-x64" { return @{ Os = "windows"; Arch = "x64" } }
        "mac-aarch64" { return @{ Os = "mac"; Arch = "aarch64" } }
        "mac-x64" { return @{ Os = "mac"; Arch = "x64" } }
        "linux-x64" { return @{ Os = "linux"; Arch = "x64" } }
        "linux-aarch64" { return @{ Os = "linux"; Arch = "aarch64" } }
        default { throw "Unrecognized -Target '$TargetName' (expected windows-x64 | mac-aarch64 | mac-x64 | linux-x64 | linux-aarch64)" }
    }
}

$TargetOsArch = Get-TargetOsArch -TargetName $Target
$TargetOs = $TargetOsArch.Os
$TargetArch = $TargetOsArch.Arch

Write-Host "== build-jre.ps1: target=$Target (os=$TargetOs arch=$TargetArch), Temurin $TemurinVersion =="

# ---------------------------------------------------------------------------
# 2. Resolve JAVA_HOME (host jlink tool) and the jmods module-path to link from.
# ---------------------------------------------------------------------------

if ([string]::IsNullOrEmpty($env:JAVA_HOME)) {
    throw "JAVA_HOME must be set to a Temurin 21 JDK. Set `$env:JAVA_HOME` and put `$env:JAVA_HOME\bin` on PATH first."
}

$JlinkBin = Join-Path $env:JAVA_HOME "bin\jlink.exe"
if (-not (Test-Path $JlinkBin)) {
    throw "$JlinkBin not found. Is JAVA_HOME a full JDK (not a JRE)?"
}

function Test-IsPinnedLocalJdk {
    $releaseFile = Join-Path $env:JAVA_HOME "release"
    if (-not (Test-Path $releaseFile)) {
        return $false
    }
    $releaseContent = Get-Content -Path $releaseFile -Raw
    $semanticVersion = $null
    $implementor = $null
    if ($releaseContent -match 'SEMANTIC_VERSION="([^"]*)"') { $semanticVersion = $Matches[1] }
    if ($releaseContent -match 'IMPLEMENTOR="([^"]*)"') { $implementor = $Matches[1] }
    $pinnedSemantic = $TemurinVersion -replace '^jdk-', ''
    return ($semanticVersion -eq $pinnedSemantic) -and ($implementor -eq "Eclipse Adoptium")
}

$JmodsDir = $null
if (($Target -eq $CurrentTarget) -and (Test-IsPinnedLocalJdk)) {
    $JmodsDir = Join-Path $env:JAVA_HOME "jmods"
    Write-Host "Using local pinned JDK jmods: $JmodsDir"
}
else {
    if ($Target -eq $CurrentTarget) {
        Write-Host "Local `$env:JAVA_HOME ($($env:JAVA_HOME)) is not exactly $TemurinVersion; downloading pinned jmods instead."
    }
    else {
        Write-Host "Cross-target build ($Target != host $CurrentTarget); downloading pinned jmods."
    }

    $CacheDir = Join-Path $RepoRoot ".cache\jdks\$TemurinVersion-$TargetOs-$TargetArch"
    New-Item -ItemType Directory -Force -Path $CacheDir | Out-Null

    $existing = Get-ChildItem -Path $CacheDir -Recurse -Directory -Filter "jmods" -ErrorAction SilentlyContinue | Select-Object -First 1
    if ($existing) {
        $JmodsDir = $existing.FullName
        Write-Host "Using cached jmods: $JmodsDir"
    }
    else {
        $archiveExt = if ($TargetOs -eq "windows") { "zip" } else { "tar.gz" }
        $archivePath = Join-Path $CacheDir "temurin.$archiveExt"
        $url = "https://api.adoptium.net/v3/binary/version/$TemurinVersion/$TargetOs/$TargetArch/jdk/hotspot/normal/eclipse"

        Write-Host "Downloading $url"
        Invoke-WebRequest -Uri $url -OutFile $archivePath -MaximumRetryCount 3 -RetryIntervalSec 2

        if ($archiveExt -eq "zip") {
            Expand-Archive -Path $archivePath -DestinationPath $CacheDir -Force
        }
        else {
            tar -xzf $archivePath -C $CacheDir
            if ($LASTEXITCODE -ne 0) { throw "tar extraction failed with exit code $LASTEXITCODE" }
        }
        Remove-Item -Path $archivePath -Force

        $found = Get-ChildItem -Path $CacheDir -Recurse -Directory -Filter "jmods" -ErrorAction SilentlyContinue | Select-Object -First 1
        if (-not $found) {
            throw "Could not locate a jmods directory after extracting the downloaded Temurin archive into $CacheDir"
        }
        $JmodsDir = $found.FullName
        Write-Host "Cached at: $JmodsDir"
    }
}

# ---------------------------------------------------------------------------
# 3. jlink: wipe the output dir, then link.
# ---------------------------------------------------------------------------

$JreOutputDir = Join-Path $RepoRoot "desktop\src-tauri\resources\jre"
$ResourcesDir = Split-Path -Parent $JreOutputDir
New-Item -ItemType Directory -Force -Path $ResourcesDir | Out-Null
if (Test-Path $JreOutputDir) {
    Remove-Item -Path $JreOutputDir -Recurse -Force
}

Write-Host "Modules: $JlinkModules"
& $JlinkBin `
    --module-path $JmodsDir `
    --add-modules $JlinkModules `
    --strip-debug `
    --no-header-files `
    --no-man-pages `
    --compress zip-6 `
    --output $JreOutputDir

if ($LASTEXITCODE -ne 0) {
    throw "jlink failed with exit code $LASTEXITCODE"
}

$sizeMb = [math]::Round(((Get-ChildItem -Path $JreOutputDir -Recurse -File | Measure-Object -Property Length -Sum).Sum / 1MB), 1)
Write-Host "jlink output: $JreOutputDir ($sizeMb MB)"

# No ad-hoc signing step: that is macOS-only (ADR-003). Windows binaries ship unsigned
# at M0 per docs/design/01-architecture.md §7 (documented SmartScreen workaround).

# ---------------------------------------------------------------------------
# 4. jdeps drift check against the real backend.jar, if it has been built yet.
#    Inlined port of scripts/lib/jre-module-drift.sh's gp_jre_check_module_drift
#    (same jdeps invocations, same union/diff logic) -- kept self-contained here
#    since PowerShell can't `source` the bash lib file.
# ---------------------------------------------------------------------------

function Test-JreModuleDrift {
    param(
        [string]$JarPath,
        [string]$PinnedCsv
    )

    $env:PATH = "$($env:JAVA_HOME)\bin;$($env:PATH)"

    $workDir = Join-Path ([System.IO.Path]::GetTempPath()) ("gp-jre-drift-" + [System.Guid]::NewGuid().ToString("N"))
    New-Item -ItemType Directory -Force -Path $workDir | Out-Null
    try {
        $explodedDir = Join-Path $workDir "exploded"
        Expand-Archive -Path $JarPath -DestinationPath $explodedDir -Force

        if (-not (Test-Path (Join-Path $explodedDir "BOOT-INF\classes"))) {
            throw "$JarPath does not look like a Spring Boot fat jar (no BOOT-INF\classes)"
        }

        Push-Location $explodedDir
        try {
            $appModulesRaw = & jdeps --ignore-missing-deps --print-module-deps --multi-release 21 --recursive --class-path "BOOT-INF/lib/*" "BOOT-INF/classes"
            if ($LASTEXITCODE -ne 0) { throw "jdeps failed against BOOT-INF/classes (exit $LASTEXITCODE)" }
        }
        finally {
            Pop-Location
        }

        $loaderModulesRaw = & jdeps --ignore-missing-deps --print-module-deps --multi-release 21 --recursive "$JarPath"
        if ($LASTEXITCODE -ne 0) { throw "jdeps failed against $JarPath (exit $LASTEXITCODE)" }

        $required = @($appModulesRaw -split ",") + @($loaderModulesRaw -split ",") |
            Where-Object { $_ -ne "" } | Sort-Object -Unique
        $pinned = @($PinnedCsv -split ",") | Where-Object { $_ -ne "" } | Sort-Object -Unique

        $missing = @($required | Where-Object { $pinned -notcontains $_ })
        $extra = @($pinned | Where-Object { $required -notcontains $_ })

        Write-Host "-- jlink module drift check (source: $JarPath) --"
        Write-Host "Pinned (scripts/jre.env)      : $($pinned -join ',')"
        Write-Host "jdeps-required (app + loader) : $($required -join ',')"
        if ($extra.Count -gt 0) {
            Write-Host "Extra in pinned list (OK - reflective/future deps): $($extra -join ',')"
        }

        if ($missing.Count -gt 0) {
            Write-Host "MISSING FROM PINNED LIST (packaged runtime would crash): $($missing -join ',')"
            Write-Host "-> add the module(s) above to JLINK_MODULES in scripts/jre.env, with a comment"
            Write-Host "   explaining which dependency demands them (see existing per-module notes)."
            return $false
        }

        Write-Host "OK - pinned module list covers every module jdeps detected."
        return $true
    }
    finally {
        Remove-Item -Path $workDir -Recurse -Force -ErrorAction SilentlyContinue
    }
}

$BackendJar = Join-Path $RepoRoot "backend\target\backend.jar"
if (Test-Path $BackendJar) {
    $driftOk = Test-JreModuleDrift -JarPath $BackendJar -PinnedCsv $JlinkModules
    if (-not $driftOk) {
        throw "jdeps drift check failed - the jre just built is missing a module the real backend.jar needs."
    }
}
else {
    Write-Host "NOTICE: $BackendJar not found; skipping jdeps drift check for this run."
    Write-Host "        Build it first (cd backend; .\mvnw.cmd -q package -DskipTests) and re-run for full drift coverage."
}

Write-Host "== build-jre.ps1 done: $JreOutputDir =="
