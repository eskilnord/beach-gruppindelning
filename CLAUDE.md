# Gruppindelning — agent rules

Local desktop tool (Mac + Windows) for a Swedish beach volleyball nonprofit's council to create optimized, explainable training-group assignments. Authoritative spec: `../utkast-kravspec.txt` (Swedish). Approved plan + design docs: `docs/design/`.

## Confidentiality — absolute rules

- **NEVER read `../Torsdagsträning VT26.xlsx`** or any file outside this repo containing member data. It holds real personnummer, contact info, and sensitive comments.
- **NEVER commit spreadsheets or CSVs** except generated anonymized fixtures under `test-data/datasets/`. The `.gitignore` is deny-by-default; `scripts/check-no-confidential.sh` runs as pre-commit hook and CI gate.
- **Comments (`importedComment`, `internalNote`) never reach**: solver input, `*_json` snapshots, or default export. There is an automated leak test — keep it green.
- Never hardcode column names from the real file; import stays generic (column-mapping wizard).

## Pinned versions — do not float

| What | Pin | Why |
|---|---|---|
| Timefold Solver | **Community 1.33.0** | 2.x moved ScoreAnalysis/explainability + recommendation API to paid Plus. Docs: `https://docs.timefold.ai/timefold-solver/1.33.x/` — **NEVER `/latest/`** (documents 2.x, will mislead you). |
| Spring Boot | 3.5.x | Timefold 1.x has no Boot 4 support (ADR-002). |
| Java | Temurin 21 LTS (dev: `~/.jdks/jdk-21.0.11+10`) | Same patch bundled on all platforms. |
| SQLite | `org.xerial:sqlite-jdbc` 3.53.x + Flyway + Spring `JdbcClient` | **No JPA/Hibernate** (ADR-004). |
| Frontend | React 19, TypeScript 5, **Vite 7**, Mantine 8, AG Grid Community 35, TanStack Query 5, Zustand 5 | Deliberately one major behind newest (ADR-005). |
| Desktop | Tauri v2 (CLI 2.11.x); jlink JRE via `bundle.resources`, **not jpackage**, not `externalBin` | ADR-003. |
| Node | 24 LTS (`/opt/homebrew/opt/node@24/bin`) | |

Dependabot/renovate must ignore `ai.timefold.solver:*` version bumps.

## Determinism rules (build-breaking CI gate)

- **No `float`/`double`/`BigDecimal` in `solver.domain` or `solver.constraints`** — ArchUnit test enforces. All level math is fixed-point ×100 integers (`levelScaled = Math.round(level * 100)`); spread = sum of absolute deviations from `floorDiv` mean; group-mean comparisons by cross-multiplication.
- Score type: `HardMediumSoftLongScore`. Medium is **reserved** for the `unassignedPlayer` (waitlist) penalty — guardrails prevent disabling/reclassifying it.
- `environmentMode`: user solves `NO_ASSERT` (note: `REPRODUCIBLE` is deprecated since 1.20), tests `PHASE_ASSERT`/`STEP_ASSERT`. `randomSeed 0`. Input deterministically sorted by stable IDs.
- CI golden-score tests use **step-count termination** (`stepCountLimit`/`unimprovedStepCountLimit`) — never wall-clock. Wall-clock presets are for interactive solves only.
- `SolverJobBuilder`: use `withBestSolutionEventConsumer`/`withFinalBestSolutionEventConsumer` (the non-`Event` variants are deprecated in 1.33). `SolverConfigOverride` methods are instance methods: `new SolverConfigOverride<>().withTerminationConfig(...)`.

## SQLite rules

- Schema owned exclusively by Flyway migrations. SQLite has no `ALTER COLUMN` — reshaping uses the create-copy-rename idiom.
- PKs are TEXT UUIDv7. Timestamps ISO-8601 UTC. Booleans INTEGER 0/1. Pragmas: WAL, `foreign_keys=ON`, `busy_timeout=5000`.

## Dev commands

```bash
npm run dev            # backend (fixed port 4517, dev token "dev") + Vite 5173 — plain browser, 90% of development
npm run dev:desktop    # Tauri shell (skips backend spawn in dev, uses 4517)
npm run test:backend   # cd backend && ./mvnw verify
npm run test:web       # vitest
npm run test:e2e       # Playwright vs vite preview + backend jar
bash scripts/package.sh  # local production build
```

Frontend must remain fully functional in a plain browser: file import = multipart upload, export = byte download, Tauri APIs isolated in `frontend/src/lib/platform.ts` with browser fallbacks.

## Language

UI text is **Swedish** (in `frontend/src/i18n/sv.ts`); code identifiers, comments, commit messages are English.
