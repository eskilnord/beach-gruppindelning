# Implementation Plan: Gruppindelning — Group Assignment Desktop Tool

## Context

The user's nonprofit beach volleyball association assigns ~260 players (Herr + Dam) into 12 level-ordered training groups per category each term — today done by hand in Excel by the council (kansliet). The goal is a **local desktop tool** (Mac + Windows) that imports registration data, lets the council fill structured fields, runs **Timefold Solver** optimization with configurable hard/soft constraints and weights, and — critically — **explains every placement** ("why did Kalle end up in group Y and not group C with his friend?"). No black box, no AI interpretation of comments, all data stays local.

Authoritative spec: `/Users/eskilnord/beach-gruppindelning/utkast-kravspec.txt` (Swedish, ~2400 lines — MVP scope §3, data model §7, 24 standard constraints §10, explainability §17, acceptance criteria §23, build order §24, agent instructions §26).

Example of today's finished work: `Torsdagsträning VT26.xlsx` — **CONFIDENTIAL** (real names, personnummer, sensitive comments). Never committed, never opened by coding agents, never hardcoded against (spec §26.1). Verified structure:
- 2 sheets (Herr/Dam) = two activity plans in season VT26; ~130 players each in 12 groups of 9–11
- Column A stacks group metadata vertically per group block (color name / group number / time slot / coach / player count), blank rows between groups
- 4 time slots (16.30/18.00/19.30/21.00), 3–4 courts per slot
- Player columns: Förnamn, Efternamn, MedlemsId, Medlemsnummer, Rank (0–1000), RankInfo, GruppFöregåendeTermin, Tid (semi-structured: "ej 21", "18.00, 19.30"), Tränare (coach wish), InternKommentar, Kommentar (sensitive free text), Epost, Mobil, Personnummer, AnmäldAktivitet
- Below groups: "Kölista" (waiting list) + dropout/refund rows — **overcapacity is the normal case, not an edge case**
- Contains cell comments + threaded comments (importer must tolerate them)

The Tid/Tränare columns prove the council already works the way the spec intends: they read free-text comments and manually distill structured constraint fields. The tool systematizes exactly that.

## User decisions (asked & answered 2026-07-01)

1. **First real use: VT27 planning (~Dec 2026)** → build the full MVP methodically, including desktop packaging, before first live use.
2. **Private GitHub repo** → GitHub Actions CI for Mac+Windows builds, packaging, cross-platform solver regression. Confidential files physically outside the repo; only anonymized test data committed.
3. **Waitlist in MVP: yes, lightweight** → solver may leave players unplaced when capacity is exceeded; explicit "Oplacerad/Kölista" bucket in results, capacity analysis, and export, with explanations.

## How this plan was produced

Three parallel design agents (architecture, solver, product) + two adversarial critics (feasibility red-team, spec-coverage), all with web-verified versions/APIs; the solver design was re-run with critic findings baked in and independently API-verified against Timefold 1.33 docs. All critic blockers are resolved in this document. Full design docs live in the workflow outputs (see "First execution steps" — they get copied into the repo as `docs/design/`).

## Decided stack (versions web-verified 2026-07-01)

| Layer | Choice | Pin | Key reason |
|---|---|---|---|
| Solver | **Timefold Solver Community 1.33.0** | never float | **Load-bearing decision, verified twice**: Timefold 2.x (Apr 2026) moved `SolutionManager.analyze()`/ScoreAnalysis + recommendation API to the paid "Plus" edition. 1.33.0 is the last Apache-2.0 line with explainability (core spec requirement §17/§23.7). Docs: `https://docs.timefold.ai/timefold-solver/1.33.x/` — **never `/latest/`** (documents 2.x). Post-MVP path: Timefold offers paid editions **free for nonprofits** — verify terms and consider 2.x then. |
| Backend | Spring Boot 3.5.x + `timefold-solver-spring-boot-starter:1.33.0` | pinned | Timefold 1.x has no Boot 4 support. Boot 3.5 OSS window just ended — acceptable for a localhost-only, token-guarded desktop backend; ADR documents the trade-off. Most agent-friendly Java framework. |
| Java | Temurin 21 LTS | 21.0.x, same patch all platforms | Intersection certified by Timefold 1.x + Boot 3.5. `brew install --cask temurin@21` on dev machine. |
| DB | SQLite `org.xerial:sqlite-jdbc` 3.53.x + **Flyway** migrations + **Spring `JdbcClient`** — **no JPA/Hibernate** | | Hibernate's SQLite dialect is community-tier and fights migrations (critic-confirmed). Explicit SQL is deterministic and agent-reliable. Pragmas: WAL, `foreign_keys=ON`, `busy_timeout=5000`. SQLite has no `ALTER COLUMN` → create-copy-rename idiom, rule written into repo CLAUDE.md. |
| Import/export | Apache POI 5.5.x (.xlsx), Commons CSV (.csv) | | POI tolerates cell/threaded comments. |
| Frontend | React 19 + TypeScript 5 + **Vite 7** (not 8), TanStack Query v5, Zustand 5, **Mantine 8** + **AG Grid Community 35** (not week-old majors), React Router, openapi-typescript typegen, Vitest + Playwright | | Deliberately one major behind newest: agent reliability on well-trained APIs beats novelty. One UI kit only (critic: round-1 docs disagreed — Mantine adopted, Ant Design rejected). |
| Desktop shell | **Tauri v2** (CLI 2.11.x) | | Spec's preferred choice. Java payload via `bundle.resources` (NOT `externalBin` — sidecars must be single files; jre/ is a directory). Rust surface fenced to ~200 lines in `backend.rs`. Electron = documented fallback with cheap escape hatch (frontend is pure browser code). |
| JRE bundling | **jlink custom runtime** as Tauri resource (NOT jpackage) | | jpackage adds a launcher we don't need (Rust shell is the launcher) and a conflicting installer stage. Modules pinned in script **plus `java.logging`** (critic catch: embedded Tomcat needs JULI) — and CI diffs `jdeps` output vs pinned list so drift fails the build. ~45–60 MB. |
| Node (dev) | Node 24 LTS | | Node 23 (installed) is a dead non-LTS line — switch via brew. |
| Windows installer | NSIS `.exe` primary (per-user, no admin), `.msi` optional | | |

## Repo layout & confidentiality firewall

**Git repo root = `/Users/eskilnord/beach-gruppindelning/app/`** — confidential xlsx stays physically OUTSIDE the repo tree (critic-mandated; round-1 product doc had it wrong).

```
beach-gruppindelning/
├── Torsdagsträning VT26.xlsx        # confidential — OUTSIDE repo
├── utkast-kravspec.txt
└── app/                             # ← git repo root, private GitHub
    ├── CLAUDE.md                    # agent rules (see below)
    ├── .github/workflows/{ci.yml,release.yml}
    ├── docs/ {architecture.md, adr/, design/, api-handshake.md, installation-mac.md, installation-windows.md}
    ├── backend/                     # Maven; se.klubb.groupplanner.{config,api,domain,persistence,importer,exporter,fields,capacity,solver,explain,plans}
    ├── frontend/                    # React SPA; src/{api,lib/platform.ts,features/*,components}
    ├── desktop/src-tauri/           # tauri.conf.json, src/{main.rs,backend.rs}, resources/ (gitignored)
    ├── test-data/ {datasets/ (anonymized CSVs), regression/expected-scores.json, generator/}
    └── scripts/ {build-jre.sh|.ps1, package.sh|.ps1, check-no-confidential.sh}
```

Defense in depth: deny-by-default `.gitignore` (`*.xlsx`, `*.xls`, `~$*`, `*.csv` + allowlist `!test-data/datasets/**/*.csv`); `check-no-confidential.sh` as pre-commit hook AND required CI step (blocks spreadsheets outside allowlist, personnummer regex `\b\d{6}(\d{2})?[-+]?\d{4}\b`, real-file markers); xlsx test fixtures generated at test runtime from committed anonymized CSVs (spec §26.15).

**Repo `CLAUDE.md` must contain**: never read `../Torsdagsträning VT26.xlsx`; never commit spreadsheets; Timefold **1.33** docs URL only; pinned versions table; no floating point in constraint code; comments (importedComment/internalNote) never reach solver input, snapshots, or default export; SQLite migrations use create-copy-rename; exact environmentMode enum names; dev commands.

## Frontend–backend contract (handshake protocol)

- Backend binds `127.0.0.1` only, `--server.port=0` (random free port — port conflicts impossible by construction); per-session random token; servlet filter 401s any request without `X-GP-Token` header (health poll includes it; `/v3/api-docs` exempted in dev profile only)
- Shell (`backend.rs`): spawn `{resources}/jre/bin/java -Duser.timezone=UTC -Dfile.encoding=UTF-8 -jar backend.jar`, env `GP_TOKEN`/`GP_DATA_DIR`/`GP_PARENT_PID`; Windows `CREATE_NO_WINDOW`; **on Unix restore `0o755` on `jre/bin/*` + `lib/jspawnhelper` before spawn** (Tauri resource-copy can strip exec bits — known failure class)
- Backend prints one stdout line `GP_READY {"port":…,"pid":…,"schemaVersion":…}` → shell polls `/api/health` ≤10 s → stores `{base_url, token}`, exposed via `get_backend_info` command
- Shutdown: `POST /api/system/shutdown` (in the API surface — critic catch), wait ≤3 s, hard-kill fallback; backend watchdog self-terminates if parent PID dies
- Failure UX: error screen with last 50 log lines + log path + "Försök igen"; TanStack Query health poll flips a reconnect overlay on mid-session crash; `tauri-plugin-single-instance`
- DB file: macOS `~/Library/Application Support/Gruppindelning/`, Windows `%APPDATA%\Gruppindelning\` (spec §21.1), overridable `--app.data-dir=` for dev/tests

**Dev workflow (agent-critical)**: frontend runs fully in a plain browser — `npm run dev` = Boot on fixed port 4517 (dev token) + Vite 5173 with proxy. File import = multipart upload; export = byte download; Tauri APIs isolated in `src/lib/platform.ts` with browser fallbacks. Coding agents and Playwright never need the desktop shell for feature work. `tauri dev` only for shell work.

## Determinism (spec §16.5, §22.3) — corrected by red-team

- **CI golden-score gate MUST use deterministic termination** (step-count / unimproved-step-count — never time-based): Timefold's reproducible mode does not reproduce across differing hardware under wall-clock termination. Wall-clock presets (10/60/120 s) are for interactive user solves only; UI copy says reproducibility holds for identical compute only.
- environmentMode exact names in 1.33: `REPRODUCIBLE` is deprecated (since ~1.19) — production solves use `NO_ASSERT` (fast, reproducible); tests use `PHASE_ASSERT`/`STEP_ASSERT`. Fixed `randomSeed`, deterministic input sort by stable UUIDv7 IDs.
- Integer-only score math: `HardSoftLongScore`-family with fixed-point scaling for levels (details in solver section); no floats in constraint code, enforced by test.
- Avoid Simulated Annealing (time-gradient-dependent); Late Acceptance or 1.33 default local search.
- Golden scores for 3 committed anonymized datasets (10/20/120 players — spec §22.2) asserted equal on macOS + Windows CI runners.

## Packaging & CI — corrected by red-team

- **macOS signing order (critic catch)**: ad-hoc sign the jlink runtime binaries individually (`find resources/jre -type f -perm +111` + all `.dylib`/`.jnilib`, `codesign --force --sign -` each) immediately after `build-jre.sh`, **BEFORE** `tauri build`; Tauri `signingIdentity: "-"` seals the outer bundle; **no post-dmg `--deep` step** (deprecated, doesn't reach Resources/, and the dmg is already built).
- **CI must exercise the actual artifact** (critic catch): release + nightly jobs install for real (macOS: `hdiutil attach` + copy .app; Windows: NSIS silent `/S`) and launch the installed app with a `--smoke` flag that runs spawn/handshake/health and exits with status.
- Unsigned-app reality: macOS 15+ needs Settings → Privacy & Security → "Open Anyway" (illustrated `docs/installation-mac.md`); Windows SmartScreen "More info → Run anyway" (`docs/installation-windows.md`). **M0 exit criterion: a clean quarantined Mac opens the .dmg following only the docs** — if the "damaged app" dialog appears instead, escalate immediately to Apple Developer Program (nonprofit fee waiver) + notarization rather than shipping xattr instructions. Windows upgrade path: Azure Trusted Signing / SignPath OSS.
- WebView2: `webviewInstallMode: downloadBootstrapper` covers rare machines without it.
- CI matrix (macos-latest + windows-latest): build, unit/integration tests, ConstraintVerifier suite, golden-score regression, Playwright e2e (browser mode), packaged-runtime smoke (`resources/jre/bin/java -jar backend.jar` → health 200), confidentiality scan.
- Cross-target jlink for mac x64 from arm64 runner (point `--module-path` at target-platform jmods).

## Import pipeline corrections (red-team)

- Keep typed values: for NUMERIC/DATE cells retain raw double / `java.time` value alongside the `DataFormatter` string; map numeric targets from typed values; locale-aware parsing (`1 234,5`, NBSP thousands) only for text-typed cells. Fixture rows must cover mixed-type columns and text-formatted numbers.
- CSV fallback charset: **windows-1252** (not ISO-8859-1) after UTF-8 BOM sniff; fixtures include cp1252-only chars (’ … –).
- Validation must include **"ogiltiga tider"** (spec §8.6 — round-1 design missed it): any column mapped to a time-typed target gets parse validation; unparseable time-looking values in reference columns → warning "ogiltig tid — kontrollera manuellt".
- Coach import: add a coach mapping target or "skapa tränare från person" bulk action post-import (spec §13.1 allows importing coaches).
- Import wizard: server-side `ImportSession`, header-row heuristic, sheet picker, skip-row logic (metadata/blank/kölista rows), person matching on email/phone/exact/similar name (§8.7), reusable import templates keyed by header hash, warnings per §8.6.

## Privacy corrections (red-team)

- Comments are deletable/anonymizable (spec §21.2 — round-1 design missed it): "Radera/anonymisera kommentarer" actions per participant and per plan (lands in M4).
- **Snapshot schemas explicitly EXCLUDE `importedComment`/`internalNote`** (solver never needs them → snapshots don't either). Automated leak test: after full import→solve→save cycle, assert no comment text appears in any `*_json` column or solver input.
- Export: comments off by default (opt-in checkbox); anonymized-dataset export strips names/email/phone/comments/personnummer, keeps ranking/level/constraints/sizes/times.

## SQLite schema (round-1 design, adopted with corrections)

All PKs TEXT UUIDv7 (stable IDs, §4.5.9), timestamps ISO-8601 UTC, booleans INTEGER 0/1, JSON in `*_json` TEXT columns. Tables: `person`, `season_plan`, `activity_plan`, `participant_profile` (incl. `waitlisted`, `imported_comment`, `manual_review_flag`, UNIQUE(person, plan)), `coach_profile`, `coach_time_slot` (AVAILABLE|UNAVAILABLE|PREFERRED), `venue`, `court`, `time_slot`, `training_block` (UNIQUE(time_slot, court)), `training_group`, `player_assignment` (source: imported|manual|solver|locked), `coach_assignment`, `field_definition` (standard+custom, `storage_kind` COLUMN|CUSTOM, `affects_optimization`, `constraint_type`, `hard_or_soft` incl. MEDIUM in model, `weight`), `custom_field_value`, `constraint_definition` (seeded §10.1–10.24 via migration), `constraint_weight_config` (per plan), `optimization_run`, `explanation_record`, `saved_plan` (+ status flow), `saved_plan_resource_usage` (materialized on **save**, not only lock — critic fix; drives cross-plan blocking), `import_template`, `import_run`.

Migrations: Flyway `V1__core.sql`, `V2__seed_constraints_and_standard_fields.sql`, `V3__runs_explanations_plans.sql`, `V4__import.sql`.

## Cross-plan conflicts (red-team addition)

Season-level `ConflictService` (plain service, outside solver): computes person/coach/court×time overlaps across ALL activity plans in a season regardless of status; surfaces in Säsongsvyn ("Konflikter" per spec §19.2), Resultatvy warnings, Planeringskarta. Overlap matching on concrete date+time when TimeSlot has a date, else dayOfWeek+time. §14.4 checkboxes decide whether locked-plan usage acts as hard block (solver facts), soft warning, or "visa konflikter men tillåt ändå".

## Explainability engine (core requirement)

- Built from `SolutionManager.analyze()` ScoreAnalysis/ConstraintAnalysis/MatchAnalysis with `justifyWith` justifications on every constraint + computed comparisons (level fit, fullness, time feasibility per group). No LLM anywhere.
- **Candidate-set rule (critic-mandated)**: alternative-group comparisons = UNION of (a) every group containing a person referenced by the player's personRelation wishes — so "why not group C with his friend" is ALWAYS answerable, (b) coach-wish groups, (c) previous-term group, (d) top-N by score diff.
- On-demand **"Varför inte grupp X?"** selector in the explain drawer → what-if diff for any group the user picks.
- Three levels: person (§17.1–17.3), group, plan (spec explainability chapter).
- Stale marking: after any manual move/lock, explanations show "Inaktuell — baserad på körning N"; what-if baseline re-anchors to current DB state.
- What-if: clone solution → apply move → analyze → diff vs baseline; report new sizes, level-spread delta, score delta, newly broken/satisfied constraints in Swedish.

## Solver design (adversarially verified against Timefold 1.33.0 source — verdict: APPROVE WITH FIXES, all fixes folded in below)

**Planning model — three entities, not two** (solves the lock-granularity problem):
- `PlayerAssignment` — planning var `group`, **`allowsUnassigned = true`** (verified 1.33 API; `nullable` is deprecated) → waitlist is a first-class solver outcome
- `GroupSchedule` — planning var `trainingBlock` (one per group)
- `CoachSlot` — planning var `coach`, `allowsUnassigned = true` (one per required coach; `requiredCoachCount=0` → no slot)
- `Group` itself = immutable problem fact. Since `@PlanningPin` is entity-level in 1.x ("applies to all planning variables of that entity" — verified), this split gives **independent locking of player/tid+bana/tränare (spec §15.2/§15.3) with zero custom move-filter code**
- `@PlanningId` = boxed `Long`; CoachSlot synthetic id = `groupId*100 + slotIndex`, assembler validates `requiredCoachCount ≤ 99`

**Score: `HardMediumSoftLongScore`** — hard = pure feasibility; **medium reserved exclusively for the unassignment penalty** (scaled ×100 by the Prioritet field); soft = all quality/wishes. Lexicographic consequence: minimizing waitlist dominates all soft quality but never competes with hard; a player who can't attend any slot is waitlisted, not hard-violated. **Guardrails (verifier-mandated)**: `unassignedPlayer` constraint is not disableable, level locked to MEDIUM, weight floor ≥ 1 (422 otherwise); MVP UI restricts field-constraint reclassification to HARD/SOFT only; validation test enforces this.

**Integer discipline**: all level math fixed-point ×100 (`levelScaled = round(level*100)`); LevelBalance = sum of absolute deviations from `floorDiv`-mean ("spridning 82 → 141" stays explainable); GroupOrderByLevel compares group means by cross-multiplication (no division); ArchUnit test bans float/double/BigDecimal in `solver.domain` + `solver.constraints`; overflow headroom verified (~1e10 worst case vs long max 9.2e18). Hand-rolled SAD chosen over the built-in `loadBalance` collector (exists in Community but returns BigDecimal — verified).

**All 24 constraints (§10.1–10.24)** designed with Constraint Streams sketches, `justifyWith` justification payloads (verified syntax) and ConstraintVerifier tests; 10.2 + 10.23 satisfied by construction. Verifier fixes applied: empty-group complement constraints get explicit keys (`groupSizeTargetEmpty`, `groupMinSizeEmpty`) that fan out from the SAME DB weight row as their parent (+ fan-out test); `forEach` null-filtering semantics verified for wish constraints.

**Dynamic weights**: `ConstraintWeightOverrides` field on the solution (verified: `of(Map<String,Score_>)`, zero weight removes constraint from processing, level reclassification = `ofHard(w)` vs `ofSoft(w)`) — NOT the legacy `@ConstraintConfiguration`. Per-plan weights from DB flow in at assembly time.

**Determinism**: user solves = `NO_ASSERT` (verified: `REPRODUCIBLE` deprecated since 1.20) + `randomSeed 0` + deterministically sorted input + wall-clock presets (10/60/120 s) injected via `new SolverConfigOverride<>().withTerminationConfig(...)` (instance method — verifier corrected); CI golden tests = `PHASE_ASSERT` + `stepCountLimit`/`unimprovedStepCountLimit` (never time-based). Local search = Late Acceptance (verified 1.33 default; no time-gradient). Async: `SolverManager` with `withBestSolutionEventConsumer`/`withFinalBestSolutionEventConsumer` (verifier: non-event variants are deprecated), progress endpoint, cancel persists best-so-far.

**Waitlist explanations (verifier-corrected)**: per full candidate group report the concrete hard blocker (full / kan inte tiden / differentGroupHard / pinned); the priority narrative comes **from data** (compare player's Prioritet vs min(member priority) of full groups, optionally a swap probe) — never from the single-move probe (mathematically can't say "placeable but worse" under lexicographic scoring); a feasible-non-full probe result renders "förbättring möjlig — kör om optimeringen" + solver-quality warning. Test: no waitlisted player has a feasible non-full candidate after convergence on golden datasets.

**Explainability engine**: one `SolutionManager.analyze(solution, FETCH_ALL)` per run persisted at plan level; per-player records computed lazily on request, cached by `(runId, planRevision)`; each player's 12 group probes computed in one pass (verifier corrected cost arithmetic), candidate set = union rule (friend-wish/coach-wish/previous/top-3) as an ordering/labelling rule over that result set, each alternative tagged with `origin: FRIEND_WISH|COACH_WISH|PREVIOUS_GROUP|TOP_SCORE` and verdict + newly-broken/newly-fixed lists in Swedish. **Waitlisted-friend edge**: "Lisa är oplacerad (kölista)" narrative + link to Lisa's own explanation. `<1 s` cold per-player latency gate on the 120-player dataset, measured in CI. Staleness: responses carry `basedOnRevision/currentRevision/stale`.

**What-if**: rebuild solution from current state via assembler (avoids non-public SolutionCloner internals) → apply move → `analyze` → `ScoreAnalysis.diff` (verified, direction `this − other`) → sizes/spread/score deltas + newly broken/fixed + `wouldBreakHard` + suggested actions (KEEP / MOVE_ANYWAY / LOCK_AND_RESOLVE). `recommendAssignment` verified Community in 1.33 — optional stretch for §18.2 suggestions.

**Plain deterministic services outside the solver**: `GroupGenerator` (count = f(participants, targetSize, active blocks); CRUD-time validation keeps groupCount ≤ activeBlockCount), `LevelService` (§11.2 chain manualLevelScore > normalized ranking > previous-group mapping, + confidence), `GreedyBaselineService` (verifier-fixed tie-breaks: blocks sorted by (day, startMinute, courtId, blockId); coach ties by (distance, coachProfileId); included in golden-score CI), season-wide `ConflictService`. "Optimera endast X" modes (§15.5) require a fully-solved plan — validator 422s with a clear message before first solve.

**Enterprise boundary confirmed clean** (verified 1.33 enterprise doc: nearby selection, multithreaded solving, partitioned search etc. — none used). **Nonprofit license verified**: Timefold offers free licenses to nonprofits (licenses.timefold.ai) — recorded as the post-MVP 2.x-Plus upgrade path; dependabot must ignore `ai.timefold.solver:*` version bumps meanwhile.

**REST surface (solver part)**: `POST /plans/{id}/solve` (profile + §14.4 blocking checkboxes) / `GET solve/status` (score, unassignedCount, progress) / `POST solve/cancel`; `GET runs/{runId}/explanations/{plan|groups/{gid}|players/{pid}}`; `POST whatif/move`; `POST whatif/why-not`; `GET /seasons/{id}/conflicts`.

## Milestones (restructured per red-team: walking skeleton first, packaging risk front-loaded)

| # | Milestone | Scope (condensed) | Exit criteria |
|---|---|---|---|
| **M0** | **Walking skeleton + packaging proof** | Repo init in `app/`, CLAUDE.md, confidentiality guard scripts + hooks, hello-world Boot backend (health + token) → fat jar → jlink (module list + jdeps drift check) → Tauri shell spawn/handshake/shutdown → CI matrix building + **installing + launching the real .dmg/.exe artifact**, ad-hoc signing in correct order | Fresh quarantined Mac + Windows machine: install via docs, app opens, health OK, no Java/Node installed. Gatekeeper "damaged" dialog ⇒ escalate to notarization path |
| M1 | Backend core + DB | Flyway V1–V2, domain records, JdbcClient repos, CRUD for Season/ActivityPlan/Person/ParticipantProfile, seeded constraints + standard fields, AppDataDirResolver | CRUD via curl; DB in platform dir; migration + repo integration tests green |
| M2 | Frontend shell | Vite/React/TS + Mantine + Router + Query, `sv.ts` Swedish copy, Startvy, Säsongsvy, plan layout w/ tabs, season/plan CRUD, openapi typegen | Create season→plan→navigate all tabs in browser; Playwright smoke |
| M3 | Import wizard e2e | POI/CSV parsing (typed values + locale), ImportSession endpoints, header heuristic, mapping UI, skip-rows, §8.6 validation incl. ogiltiga tider, person matching, templates, coach import path, synthetic messy fixture generator | Synthetic file shaped like the real one imports correctly (å/ä/ö, comments tolerated, kölista rows skipped); **manual check by user with real file locally**; parser/matching tests + Playwright flow |
| M4 | Fields + participants | Fältbyggare (custom field CRUD → constraint wiring → weights), per-plan weight table, Deltagarvy AG Grid + side-by-side comment/structured-fields drawer, estimatedLevel service + confidence, comment delete/anonymize actions | Create "Vill spela med"/personRelation/SameGroupSoft/80; level fallback chain works; comment-leak test green |
| M5 | Resources + coaches + capacity | Time slots, courts-per-slot → TrainingBlock generation (idempotent regen), block deactivation, Tränarvyn (tri-state availability, level, max groups, also-plays), Kapacitetsvyn (§12.4 metrics + waitlist/coach-shortage warnings) | "Torsdag 18.00–19.30: 4 banor" → 4 blocks; capacity math matches §12.4 example |
| M6a | Solver core: model + hard + waitlist + solve loop | 3-entity planning model, TimeKey/overlap logic, SolverInputAssembler (deterministic sort, seed 0), GroupGenerator, solverConfig + starter wiring, solve lifecycle endpoints (start/status/cancel), hard constraints + `unassignedPlayer` (medium) with `justifyWith` from day one, ConstraintVerifier test per hard constraint (§26.13), no-float ArchUnit + score-headroom tests, **golden-score cross-OS CI gate live from this milestone** (step-count termination). Prereq: Flyway V3 (optimization_run) | 10/20/120-player datasets solve feasible (hard=0), no double-bookings, waitlist contract tests pass; identical scores mac/win CI; same-machine double-run equality |
| M6b | Solver complete: soft + weights + locks + results UI | All soft constraints (SAD level balance, cross-multiplied group ordering, coach fit/wishes, late-time), ConstraintWeightOverrides flow + **guardrails** (unassignedPlayer undisableable) + complement-key fan-out, LevelService, greedy baseline (in golden CI), locks end-to-end for all 3 kinds + §15.5 modes, cross-plan SavedPlanResourceBlock + ConflictService, Resultatvy + Planeringskarta + Oplacerad bucket | Weight change flips a documented trade-off deterministically; lock+re-solve golden test; the spec §13.2 Anna cross-plan test verbatim; cancel persists best-so-far |
| M7 | Explainability + what-if | analyze(FETCH_ALL) persistence, ConstraintMetadata Swedish registry, ExplanationService (3 levels, 12-probe single pass, candidate-union rule, waitlist explanations, lazy cache + staleness), what-if move + "Varför inte grupp X?" endpoints, explain drawer + consequence dialog UI | **Canonical-question test green: "Varför hamnade Kalle i grupp Y och inte i grupp C med sin kompis?"** incl. waitlisted-friend variant; §23.7+§23.8 scripted checks; what-if reproduces §18.1 output fields; <1 s cold explain on 120-player dataset; staleness flag flips after manual move |
| M8 | Saved plans + export + conflicts | Save/lock plans (snapshots EXCLUDING comments), status flow, resource-usage materialization, cross-plan solver blocking, ConflictService + Säsongsvy conflicts, xlsx/csv export (council layout + flat), comments-off default, anonymized export | Lock Herr → Dam solve can't double-book shared coach/person (§23.6); export opens in Excel mirroring council layout; **manual check by user vs real file**; leak tests green |
| M9 | Release hardening | App icon/naming, installation docs with screenshots, release.yml (draft GitHub Releases), full §23 acceptance checklist pass, cross-platform regression suite complete, real-data dry run of entire flow by user | Every §23 criterion demonstrably green; VT27 dry-run of full workflow (import→structure→solve→explain→adjust→export) |

Milestone order rationale: M0 front-loads the riskiest cross-platform work (red-team requirement); M6a/M6b/M7 map 1:1 to the verified solver design's own three-session split (with LevelService + greedy baseline shed to M6b per its verifier).

## Multi-model execution protocol (per user request)

Per milestone:
1. **Brief** (Fable, main session): milestone contract — endpoints, files, acceptance checks, test list — derived from this plan + design docs in `docs/design/`.
2. **Implement** (Sonnet 5 agents via Workflow, `model: 'sonnet'`): 1–3 agents; parallel only on disjoint modules; worktree isolation when files could collide. Agents follow repo CLAUDE.md rules.
3. **Gate** (Fable/Opus 4.8): build + full test suite green → adversarial code review (correctness, spec conformance §-references, confidentiality, determinism rules) → verify the demo criterion by actually running it (browser Playwright or curl). Findings go back to Sonnet agents for fixes; re-review until clean.
4. **Merge + tag** milestone on main; CI matrix must be green.
5. **User checkpoints**: M0 (install on your machines), M3 (import your real file locally), M7 (judge explanation quality on real data), M8 (export vs your current layout), M9 (full dry run).

## Verification

- Every hard constraint: dedicated ConstraintVerifier test (spec §26.13). Solver fixtures: 10/20/120-player anonymized datasets (§22.2).
- Cross-platform: golden-score regression (deterministic termination, fixed seed) on macOS+Windows CI — build-breaking.
- Confidentiality: pre-commit + CI scanner; comment-leak automated test (no comment text in solver input, snapshots, default export).
- E2E: Playwright flows for the §22.4 list (import→map→fields→resources→coaches→solve→explain→move→lock→re-solve→export) in browser mode; packaged-artifact install+launch smoke on both OSes in CI.
- Final: §23 acceptance checklist walked criterion-by-criterion at M9 + user dry run with real VT26 data locally.

## First execution steps (after plan approval)

1. Install toolchain: `brew install --cask temurin@21`, `rustup` (stable), Node 24 LTS (replace dead-line Node 23).
2. `git init` in `app/`, write CLAUDE.md + .gitignore + confidentiality scripts FIRST, create private GitHub repo (`gh repo create`).
3. Copy full design docs from workflow outputs into `docs/design/` — round-1 (arch + product + critics): `/private/tmp/claude-501/-Users-eskilnord-beach-gruppindelning/abbaefe1-d6c8-46b4-b6ed-c32332b7f557/tasks/wovapczb6.output`; solver design + verification (incl. per-constraint table, class sketches, endpoint JSON shapes): `/private/tmp/claude-501/-Users-eskilnord-beach-gruppindelning/abbaefe1-d6c8-46b4-b6ed-c32332b7f557/tasks/wkeuvl9sc.output` (JSON; extract `result.*.detail`). Apply the solver verifier's 3 major + 8 minor fixes (listed in `result.verification.problems`) when distilling — the plan above already incorporates them. Distill ADRs: Timefold 1.33 pin + nonprofit-license upgrade path, Boot 3.5 pin, jlink-not-jpackage, JdbcClient-not-JPA, Mantine, 3-entity planning model, determinism rules.
4. Build the anonymized test-data generator (deterministic, seeds committed) — needed by every later milestone.
5. Start M0 with the Sonnet-implement / Fable-review protocol.
