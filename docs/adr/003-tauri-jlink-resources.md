# ADR-003: Tauri v2 shell; jlink runtime shipped via bundle.resources

**Status:** Accepted (2026-07-01)

## Decision
- **Tauri v2** (spec-preferred): frontend is pure browser code, no Node runtime needed in production; installers (.dmg/NSIS .exe) out of the box; Rust surface fenced to ~200 lines (`backend.rs`). Electron = documented fallback (cheap escape hatch since the frontend never touches Tauri APIs outside `platform.ts`).
- **jlink custom runtime** (Temurin 21, pinned module list **including `java.logging`** for embedded Tomcat) shipped as a Tauri **resource directory** next to the fat jar. Not `externalBin` (sidecars must be single files). Not jpackage (adds a launcher we don't need and a conflicting installer stage). CI diffs `jdeps` output vs the pinned module list so drift fails the build.
- macOS ad-hoc signing order: sign jre binaries individually (all executables + dylibs) **before** `tauri build`; `signingIdentity: "-"` seals the bundle; never post-dmg `--deep`.
- Shell restores `0o755` on `jre/bin/*` + `lib/jspawnhelper` before spawn (Tauri resource-copy can strip exec bits).
- If a clean quarantined Mac shows the "damaged app" dialog at M0: escalate to Apple Developer Program (nonprofit fee waiver) + notarization.
