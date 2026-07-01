#!/usr/bin/env bash
# scripts/lib/jre-module-drift.sh
#
# Shared implementation of the jlink module drift check (docs/design/01-architecture.md
# §3, docs/adr/003-tauri-jlink-resources.md). Sourced by both scripts/build-jre.sh
# (runs it as a build-time gate) and scripts/check-jre-modules.sh (standalone CI step),
# so there is exactly one place that implements the jdeps invocation.
#
# Not meant to be executed directly — `source` it and call gp_jre_check_module_drift.
#
# shellcheck shell=bash

# gp_jre_check_module_drift <backend-jar-path> <pinned-modules-csv>
#
# Explodes the given Spring Boot fat jar, runs jdeps against BOOT-INF/classes (the
# app's own code + its exact runtime classpath) and against the outer jar (to capture
# the org.springframework.boot.loader.* launcher classes packed at the jar root), unions
# the two module sets, diffs against the pinned list, and prints a clear report.
#
# Returns 0 if every module jdeps found is covered by the pinned list (extra pinned
# modules are fine — reflective/future deps). Returns 1 if jdeps found a module that is
# NOT in the pinned list (this must fail the build: a missing jlink module produces a
# packaged runtime that crashes only on end-user machines). Returns 2 on a usage/
# precondition error (e.g. the jar does not exist).
gp_jre_check_module_drift() {
  local jar_path="$1"
  local pinned_csv="$2"

  if [ -z "$jar_path" ] || [ -z "$pinned_csv" ]; then
    echo "gp_jre_check_module_drift: usage: gp_jre_check_module_drift <backend-jar-path> <pinned-modules-csv>" >&2
    return 2
  fi

  if [ ! -f "$jar_path" ]; then
    echo "ERROR: backend jar not found at $jar_path" >&2
    echo "       Build it first: (cd backend && ./mvnw -q package -DskipTests)" >&2
    return 2
  fi

  if ! command -v jdeps >/dev/null 2>&1; then
    echo "ERROR: jdeps not found on PATH (expected \$JAVA_HOME/bin on PATH)" >&2
    return 2
  fi

  local workdir
  workdir="$(mktemp -d)"
  # RETURN (not EXIT) so we never clobber a trap the caller may have set for its own
  # exit handling; fires when this function returns, however it returns.
  # shellcheck disable=SC2064
  trap "rm -rf '$workdir'" RETURN

  if ! unzip -q "$jar_path" -d "$workdir/exploded"; then
    echo "ERROR: failed to explode $jar_path" >&2
    return 2
  fi

  if [ ! -d "$workdir/exploded/BOOT-INF/classes" ]; then
    echo "ERROR: $jar_path does not look like a Spring Boot fat jar (no BOOT-INF/classes)" >&2
    return 2
  fi

  # The app's own code + its exact runtime classpath, resolved recursively (jdeps
  # follows dependencies of dependencies, catching transitive JDK module usage that a
  # shallow scan of just our own ~10 classes would miss).
  local app_modules
  app_modules="$(
    cd "$workdir/exploded" && \
    jdeps --ignore-missing-deps --print-module-deps --multi-release 21 --recursive \
      --class-path 'BOOT-INF/lib/*' BOOT-INF/classes
  )"

  # The modules the Spring Boot loader itself needs (org.springframework.boot.loader.*,
  # packed at the jar root — the actual Main-Class per META-INF/MANIFEST.MF, resolved
  # before BOOT-INF/classes ever loads). Scanning the outer jar directly (not exploded)
  # is deliberate: jdeps does not descend into nested jars under BOOT-INF/lib, so this
  # reads only the launcher's own dependencies.
  local loader_modules
  loader_modules="$(
    jdeps --ignore-missing-deps --print-module-deps --multi-release 21 --recursive "$jar_path"
  )"

  local required_sorted pinned_sorted
  required_sorted="$(printf '%s\n%s\n' "$app_modules" "$loader_modules" | tr ',' '\n' | sed '/^$/d' | sort -u)"
  pinned_sorted="$(printf '%s\n' "$pinned_csv" | tr ',' '\n' | sed '/^$/d' | sort -u)"

  local missing extra
  missing="$(comm -23 <(printf '%s\n' "$required_sorted") <(printf '%s\n' "$pinned_sorted") || true)"
  extra="$(comm -13 <(printf '%s\n' "$required_sorted") <(printf '%s\n' "$pinned_sorted") || true)"

  echo "── jlink module drift check (source: $jar_path) ──────────────────────"
  echo "Pinned (scripts/jre.env)      : $(printf '%s' "$pinned_sorted" | paste -sd, -)"
  echo "jdeps-required (app + loader) : $(printf '%s' "$required_sorted" | paste -sd, -)"
  if [ -n "$extra" ]; then
    echo "Extra in pinned list (OK — reflective/future deps): $(printf '%s' "$extra" | paste -sd, -)"
  fi

  if [ -n "$missing" ]; then
    echo "MISSING FROM PINNED LIST (packaged runtime would crash): $(printf '%s' "$missing" | paste -sd, -)"
    echo "→ add the module(s) above to JLINK_MODULES in scripts/jre.env, with a comment"
    echo "  explaining which dependency demands them (see existing per-module notes)."
    return 1
  fi

  echo "OK — pinned module list covers every module jdeps detected."
  return 0
}
