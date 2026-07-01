# ADR-001: Pin Timefold Solver Community 1.33.0

**Status:** Accepted (2026-07-01)

## Context
Timefold Solver 2.x (April 2026) moved `SolutionManager.analyze()`/ScoreAnalysis and the recommendation API out of the Apache-2.0 Community edition into the paid "Plus" edition. Explainability is a core spec requirement (§17, §23.7). 1.33.0 is the last fully open-source line with these APIs (quarterly 1.x bugfixes through 2026).

## Decision
Pin `ai.timefold.solver:*` at **1.33.0**. Never float. Use only the 1.33.x docs (`https://docs.timefold.ai/timefold-solver/1.33.x/`) — `/latest/` documents 2.x and misleads. Dependabot/renovate ignores `ai.timefold.solver:*`.

## Upgrade path
Timefold offers **free licenses to nonprofits** (verified: https://licenses.timefold.ai/). Post-MVP: apply for the nonprofit license → migrate to 2.x Plus via the official v1→v2 guide. Keep explain/what-if code behind thin internal interfaces to ease that migration.
