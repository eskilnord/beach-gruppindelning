#!/usr/bin/env bash
# scripts/package.sh
#
# Full local production build pipeline for the current platform
# (docs/design/01-architecture.md §7): backend fat jar -> jlink runtime -> copy jar
# into the Tauri resource dir -> tauri build. Also what CI runs.
#
# Usage: scripts/package.sh
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "$SCRIPT_DIR/.." && pwd)"

echo "== package.sh: 1/4 backend jar =="
( cd "$REPO_ROOT/backend" && ./mvnw -q package -DskipTests )
echo "Built $REPO_ROOT/backend/target/backend.jar"

echo "== package.sh: 2/4 jlink runtime =="
"$SCRIPT_DIR/build-jre.sh"

echo "== package.sh: 3/4 copy backend.jar into resources =="
RESOURCES_BACKEND_DIR="$REPO_ROOT/desktop/src-tauri/resources/backend"
mkdir -p "$RESOURCES_BACKEND_DIR"
cp "$REPO_ROOT/backend/target/backend.jar" "$RESOURCES_BACKEND_DIR/backend.jar"
echo "Copied to $RESOURCES_BACKEND_DIR/backend.jar"

echo "== package.sh: 4/4 tauri build =="
if [ -d "$REPO_ROOT/desktop/src-tauri" ]; then
  # Workspace deps must exist at the ROOT: tauri.conf.json's beforeBuildCommand builds the
  # real frontend (npm run build --workspace frontend) before bundling — the v0.1.0
  # installers shipped the M0 placeholder because nothing ever built/bundled the SPA.
  if [ ! -d "$REPO_ROOT/node_modules" ]; then
    ( cd "$REPO_ROOT" && npm ci )
  fi
  ( cd "$REPO_ROOT/desktop" && npm run tauri build )
else
  echo "NOTICE: desktop/src-tauri does not exist yet (Tauri shell not scaffolded) -- skipping 'npm run tauri build'."
  echo "        Another agent is creating it; re-run scripts/package.sh once it exists."
fi

echo "== package.sh done =="
