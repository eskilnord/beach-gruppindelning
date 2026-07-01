#!/usr/bin/env bash
# scripts/check-jre-modules.sh
#
# Standalone jdeps drift gate: fails if the real backend.jar needs a JDK module that
# isn't in the pinned jlink module list (scripts/jre.env). Meant to run as its own CI
# step (drift breaks the build, docs/design/01-architecture.md §3 / §7) and is also
# invoked internally by scripts/build-jre.sh before it links a runtime.
#
# Usage: scripts/check-jre-modules.sh [path/to/backend.jar]
#   Defaults to backend/target/backend.jar relative to the repo root.
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "$SCRIPT_DIR/.." && pwd)"

# shellcheck source=scripts/jre.env
source "$SCRIPT_DIR/jre.env"
# shellcheck source=scripts/lib/jre-module-drift.sh
source "$SCRIPT_DIR/lib/jre-module-drift.sh"

BACKEND_JAR="${1:-$REPO_ROOT/backend/target/backend.jar}"

if [ -z "${JAVA_HOME:-}" ]; then
  echo "ERROR: JAVA_HOME must be set to a Java 21 JDK (jdeps needs to be on PATH)." >&2
  exit 2
fi
export PATH="$JAVA_HOME/bin:$PATH"

gp_jre_check_module_drift "$BACKEND_JAR" "$JLINK_MODULES"
