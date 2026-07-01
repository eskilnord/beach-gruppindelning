# ADR-002: Spring Boot 3.5.x despite OSS support window ending

**Status:** Accepted (2026-07-01)

## Context
Boot 4.1 is current; Boot 3.5 OSS support ended 2026-06-30. But Timefold 1.x's Spring starter supports Boot 3.x only (Boot 4 support arrived in the Timefold 2.x era), and ADR-001 pins Timefold 1.33.0.

## Decision
Spring Boot 3.5.x (latest patch), `timefold-solver-spring-boot-starter:1.33.0`, Java 21 (Temurin), Maven with committed wrapper. Acceptable because the backend binds 127.0.0.1 only, is token-authenticated, and processes only local files. Post-MVP escape hatch: drop the starter, configure `SolverFactory` programmatically (~30 lines) to unlock Boot 4 — or upgrade with ADR-001's path.
