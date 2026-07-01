#!/usr/bin/env bash
# scripts/smoke-backend.sh
#
# Packaged-runtime smoke test (docs/design/01-architecture.md §3/§7, ADR-003): boots the
# ACTUAL jlinked runtime + fat jar exactly as the Tauri shell would spawn them, and
# asserts the full handshake + shutdown protocol works end to end. This is what catches
# a missing jlink module or a broken jar before it ever reaches a release artifact.
#
# Usage: scripts/smoke-backend.sh
#   Expects desktop/src-tauri/resources/jre/ and desktop/src-tauri/resources/backend/backend.jar
#   to already exist (run scripts/package.sh, or scripts/build-jre.sh + a manual jar copy, first).
#
# Exits non-zero on ANY failure.
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "$SCRIPT_DIR/.." && pwd)"

JAVA_BIN="$REPO_ROOT/desktop/src-tauri/resources/jre/bin/java"
BACKEND_JAR="$REPO_ROOT/desktop/src-tauri/resources/backend/backend.jar"
GP_TOKEN_VALUE="smoke"

fail() {
  echo "SMOKE FAIL: $*" >&2
  exit 1
}

[ -x "$JAVA_BIN" ] || fail "packaged java not found/executable at $JAVA_BIN (run scripts/build-jre.sh / scripts/package.sh first)"
[ -f "$BACKEND_JAR" ] || fail "packaged backend.jar not found at $BACKEND_JAR (run scripts/package.sh first)"

DATA_DIR="$(mktemp -d)"
STDOUT_LOG="$(mktemp)"
STDERR_LOG="$(mktemp)"
BACKEND_PID=""

cleanup() {
  if [ -n "$BACKEND_PID" ] && kill -0 "$BACKEND_PID" 2>/dev/null; then
    kill "$BACKEND_PID" 2>/dev/null || true
    wait "$BACKEND_PID" 2>/dev/null || true
  fi
  rm -rf "$DATA_DIR" "$STDOUT_LOG" "$STDERR_LOG"
}
trap cleanup EXIT

echo "Starting packaged backend: $JAVA_BIN -jar $BACKEND_JAR"
GP_TOKEN="$GP_TOKEN_VALUE" GP_DATA_DIR="$DATA_DIR" \
  "$JAVA_BIN" -Duser.timezone=UTC -Dfile.encoding=UTF-8 -jar "$BACKEND_JAR" \
  --server.port=0 --server.address=127.0.0.1 \
  >"$STDOUT_LOG" 2>"$STDERR_LOG" &
BACKEND_PID=$!
echo "Backend PID: $BACKEND_PID"

echo "Waiting up to 30s for the GP_READY line..."
PORT=""
DEADLINE=$((SECONDS + 30))
while [ "$SECONDS" -lt "$DEADLINE" ]; do
  if ! kill -0 "$BACKEND_PID" 2>/dev/null; then
    echo "---- backend stdout ----"; cat "$STDOUT_LOG"
    echo "---- backend stderr ----"; cat "$STDERR_LOG"
    fail "backend process exited before printing GP_READY"
  fi
  if grep -q '^GP_READY ' "$STDOUT_LOG" 2>/dev/null; then
    READY_LINE="$(grep '^GP_READY ' "$STDOUT_LOG" | head -1)"
    PORT="$(printf '%s' "$READY_LINE" | sed -n 's/.*"port":\([0-9]*\).*/\1/p')"
    [ -n "$PORT" ] && break
  fi
  sleep 0.25
done

if [ -z "$PORT" ]; then
  echo "---- backend stdout ----"; cat "$STDOUT_LOG"
  echo "---- backend stderr ----"; cat "$STDERR_LOG"
  fail "timed out waiting 30s for the GP_READY line"
fi
echo "GP_READY parsed: port=$PORT"

BASE_URL="http://127.0.0.1:$PORT"

HEALTH_BODY="$(curl -sS --max-time 5 -H "X-GP-Token: $GP_TOKEN_VALUE" "$BASE_URL/api/health" || true)"
echo "Health response: $HEALTH_BODY"
case "$HEALTH_BODY" in
  *'"status":"UP"'*) ;;
  *)
    echo "---- backend stdout ----"; cat "$STDOUT_LOG"
    echo "---- backend stderr ----"; cat "$STDERR_LOG"
    fail "unexpected /api/health response: '$HEALTH_BODY'"
    ;;
esac

echo "Requesting graceful shutdown..."
curl -sS --max-time 5 -o /dev/null -w '%{http_code}\n' -X POST -H "X-GP-Token: $GP_TOKEN_VALUE" "$BASE_URL/api/system/shutdown" \
  || fail "POST /api/system/shutdown request failed"

echo "Waiting up to 10s for the process to exit..."
EXITED=false
EXIT_DEADLINE=$((SECONDS + 10))
while [ "$SECONDS" -lt "$EXIT_DEADLINE" ]; do
  if ! kill -0 "$BACKEND_PID" 2>/dev/null; then
    EXITED=true
    break
  fi
  sleep 0.25
done

if [ "$EXITED" != true ]; then
  echo "---- backend stdout ----"; cat "$STDOUT_LOG"
  echo "---- backend stderr ----"; cat "$STDERR_LOG"
  fail "backend process (PID $BACKEND_PID) did not exit within 10s of the shutdown request"
fi

echo "SMOKE OK: GP_READY handshake + /api/health + graceful shutdown all succeeded (port=$PORT)"
