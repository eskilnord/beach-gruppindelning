#!/usr/bin/env bash
# scripts/build-jre.sh
#
# Builds the jlink custom-runtime resource directory the Tauri shell bundles next to
# the fat jar (docs/design/01-architecture.md §3, docs/adr/003-tauri-jlink-resources.md).
#
# Usage:
#   scripts/build-jre.sh [--target <os-arch>]
#
#   --target   One of: mac-aarch64 | mac-x64 | windows-x64 | linux-x64 | linux-aarch64.
#              Defaults to the current host platform (e.g. mac-aarch64 on Apple
#              Silicon). Cross-targets download the pinned Temurin jmods for that
#              platform via the Adoptium API instead of using the local JDK.
#
# Requires JAVA_HOME to point at a Java 21 JDK (its jlink binary links the runtime,
# even for cross-targets — jlink is a build-time tool, not something that needs to
# match the target OS/arch, only the module-path content does).
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "$SCRIPT_DIR/.." && pwd)"

# shellcheck source=scripts/jre.env
source "$SCRIPT_DIR/jre.env"
# shellcheck source=scripts/lib/jre-module-drift.sh
source "$SCRIPT_DIR/lib/jre-module-drift.sh"

usage() {
  cat >&2 <<EOF
Usage: $(basename "$0") [--target <os-arch>]
  os-arch: mac-aarch64 | mac-x64 | windows-x64 | linux-x64 | linux-aarch64
  Defaults to the current host platform.
EOF
}

TARGET=""
while [ $# -gt 0 ]; do
  case "$1" in
    --target)
      TARGET="${2:-}"
      shift 2
      ;;
    --target=*)
      TARGET="${1#--target=}"
      shift
      ;;
    -h|--help)
      usage
      exit 0
      ;;
    *)
      echo "Unknown argument: $1" >&2
      usage
      exit 1
      ;;
  esac
done

# ---------------------------------------------------------------------------
# 1. Resolve the target platform + its Adoptium os/arch pair.
# ---------------------------------------------------------------------------

current_platform_target() {
  local os arch
  os="$(uname -s)"
  arch="$(uname -m)"
  case "$os" in
    Darwin)
      case "$arch" in
        arm64) echo "mac-aarch64" ;;
        x86_64) echo "mac-x64" ;;
        *) echo "ERROR: unsupported macOS arch '$arch'" >&2; exit 1 ;;
      esac
      ;;
    Linux)
      # Not an official release target (see docs/design/01-architecture.md §3: macOS
      # arm64/x64 + Windows x64 only), but harmless as a default so this script can
      # still run module-drift checks etc. in a Linux dev container.
      case "$arch" in
        x86_64) echo "linux-x64" ;;
        aarch64) echo "linux-aarch64" ;;
        *) echo "ERROR: unsupported Linux arch '$arch'" >&2; exit 1 ;;
      esac
      ;;
    *)
      echo "ERROR: build-jre.sh is the macOS/bash build script; unsupported host OS '$os'. Use scripts/build-jre.ps1 on Windows." >&2
      exit 1
      ;;
  esac
}

[ -z "$TARGET" ] && TARGET="$(current_platform_target)"
CURRENT_TARGET="$(current_platform_target)"

target_os_arch() {
  case "$1" in
    mac-aarch64) echo "mac aarch64" ;;
    mac-x64) echo "mac x64" ;;
    windows-x64) echo "windows x64" ;;
    linux-x64) echo "linux x64" ;;
    linux-aarch64) echo "linux aarch64" ;;
    *)
      echo "ERROR: unrecognized --target '$1' (expected mac-aarch64 | mac-x64 | windows-x64 | linux-x64 | linux-aarch64)" >&2
      exit 1
      ;;
  esac
}

TARGET_OS_ARCH="$(target_os_arch "$TARGET")"
TARGET_OS="${TARGET_OS_ARCH% *}"
TARGET_ARCH="${TARGET_OS_ARCH#* }"

echo "== build-jre.sh: target=$TARGET (os=$TARGET_OS arch=$TARGET_ARCH), Temurin ${TEMURIN_VERSION} =="

# ---------------------------------------------------------------------------
# 2. Resolve JAVA_HOME (host jlink tool) and the jmods module-path to link from.
# ---------------------------------------------------------------------------

: "${JAVA_HOME:?JAVA_HOME must be set to a Temurin 21 JDK (CLAUDE.md 'Pinned versions': ~/.jdks/jdk-21.0.11+10/Contents/Home). export JAVA_HOME=... and PATH=\"\$JAVA_HOME/bin:\$PATH\" first.}"
JLINK_BIN="$JAVA_HOME/bin/jlink"
if [ ! -x "$JLINK_BIN" ]; then
  echo "ERROR: $JLINK_BIN not found or not executable. Is JAVA_HOME a full JDK (not a JRE)?" >&2
  exit 1
fi

is_pinned_local_jdk() {
  local release_file="$JAVA_HOME/release"
  [ -f "$release_file" ] || return 1
  local semantic_version implementor
  semantic_version="$(grep -E '^SEMANTIC_VERSION=' "$release_file" | cut -d'"' -f2 || true)"
  implementor="$(grep -E '^IMPLEMENTOR=' "$release_file" | cut -d'"' -f2 || true)"
  local pinned_semantic="${TEMURIN_VERSION#jdk-}"
  [ "$semantic_version" = "$pinned_semantic" ] && [ "$implementor" = "Eclipse Adoptium" ]
}

JMODS_DIR=""
if [ "$TARGET" = "$CURRENT_TARGET" ] && is_pinned_local_jdk; then
  JMODS_DIR="$JAVA_HOME/jmods"
  echo "Using local pinned JDK jmods: $JMODS_DIR"
else
  if [ "$TARGET" = "$CURRENT_TARGET" ]; then
    echo "Local \$JAVA_HOME ($JAVA_HOME) is not exactly ${TEMURIN_VERSION}; downloading pinned jmods instead."
  else
    echo "Cross-target build ($TARGET != host $CURRENT_TARGET); downloading pinned jmods."
  fi

  CACHE_DIR="$REPO_ROOT/.cache/jdks/${TEMURIN_VERSION}-${TARGET_OS}-${TARGET_ARCH}"
  mkdir -p "$CACHE_DIR"

  JMODS_DIR="$(find "$CACHE_DIR" -type d -name jmods -print -quit 2>/dev/null || true)"
  if [ -n "$JMODS_DIR" ]; then
    echo "Using cached jmods: $JMODS_DIR"
  else
    archive_ext="tar.gz"
    [ "$TARGET_OS" = "windows" ] && archive_ext="zip"
    archive_path="$CACHE_DIR/temurin.${archive_ext}"
    url="https://api.adoptium.net/v3/binary/version/${TEMURIN_VERSION}/${TARGET_OS}/${TARGET_ARCH}/jdk/hotspot/normal/eclipse"

    echo "Downloading $url"
    curl -fL --retry 3 --retry-delay 2 -o "$archive_path" "$url"

    case "$archive_ext" in
      tar.gz) tar -xzf "$archive_path" -C "$CACHE_DIR" ;;
      zip) unzip -q "$archive_path" -d "$CACHE_DIR" ;;
    esac
    rm -f "$archive_path"

    JMODS_DIR="$(find "$CACHE_DIR" -type d -name jmods -print -quit 2>/dev/null || true)"
    if [ -z "$JMODS_DIR" ]; then
      echo "ERROR: could not locate a jmods/ directory after extracting the downloaded Temurin archive into $CACHE_DIR" >&2
      exit 1
    fi
    echo "Cached at: $JMODS_DIR"
  fi
fi

# ---------------------------------------------------------------------------
# 3. jlink: wipe the output dir, then link.
# ---------------------------------------------------------------------------

JRE_OUTPUT_DIR="$REPO_ROOT/desktop/src-tauri/resources/jre"
mkdir -p "$(dirname "$JRE_OUTPUT_DIR")"
rm -rf "$JRE_OUTPUT_DIR"

echo "Modules: $JLINK_MODULES"
"$JLINK_BIN" \
  --module-path "$JMODS_DIR" \
  --add-modules "$JLINK_MODULES" \
  --strip-debug \
  --no-header-files \
  --no-man-pages \
  --compress zip-6 \
  --output "$JRE_OUTPUT_DIR"

echo "jlink output: $JRE_OUTPUT_DIR ($(du -sh "$JRE_OUTPUT_DIR" | cut -f1))"

# jlink copies legal/** notices as read-only (444); tauri-build's resource copy
# preserves permissions and then fails to overwrite them on a rebuild. Make the
# runtime tree owner-writable so repeated `tauri build`/`tauri dev` runs work.
chmod -R u+w "$JRE_OUTPUT_DIR"

# ---------------------------------------------------------------------------
# 4. macOS ad-hoc signing — BEFORE anything else uses the runtime (ADR-003: sign
#    individual binaries here, never post-dmg --deep).
# ---------------------------------------------------------------------------

if [ "$TARGET_OS" = "mac" ]; then
  if command -v codesign >/dev/null 2>&1; then
    echo "Ad-hoc signing jre executables + dylibs (target=mac)..."
    find "$JRE_OUTPUT_DIR" -type f \( -perm +111 -o -name '*.dylib' -o -name '*.jnilib' \) -exec codesign --force --sign - {} \;
    echo "Signing complete."
  else
    echo "WARNING: target=mac but 'codesign' is not available on this host; skipping ad-hoc signing." >&2
    echo "         Re-run this step on macOS before the jre is used in a bundle." >&2
  fi
fi

# ---------------------------------------------------------------------------
# 5. jdeps drift check against the real backend.jar, if it has been built yet.
#    Runs the SAME check exposed standalone as scripts/check-jre-modules.sh.
# ---------------------------------------------------------------------------

BACKEND_JAR="$REPO_ROOT/backend/target/backend.jar"
if [ -f "$BACKEND_JAR" ]; then
  if ! gp_jre_check_module_drift "$BACKEND_JAR" "$JLINK_MODULES"; then
    echo "ERROR: jdeps drift check failed — the jre just built is missing a module the real backend.jar needs." >&2
    exit 1
  fi
else
  echo "NOTICE: $BACKEND_JAR not found; skipping jdeps drift check for this run."
  echo "        Build it first (cd backend && ./mvnw -q package -DskipTests) and re-run, or use"
  echo "        scripts/check-jre-modules.sh directly, for full drift coverage."
fi

echo "== build-jre.sh done: $JRE_OUTPUT_DIR =="
