# ADR-004: SQLite via JdbcClient + Flyway; no JPA/Hibernate

**Status:** Accepted (2026-07-01)

## Decision
`org.xerial:sqlite-jdbc` 3.53.x, Flyway owns the schema, Spring `JdbcClient` with explicit SQL and row mappers. Hibernate's SQLite dialect is community-tier and its type-affinity validation fights Flyway-created schemas; explicit SQL is deterministic and reliable for code-generation agents.

Rules: PKs TEXT UUIDv7; timestamps ISO-8601 UTC; booleans INTEGER 0/1; pragmas WAL + `foreign_keys=ON` + `busy_timeout=5000`; no `ALTER COLUMN` — reshaping migrations use create-copy-rename.
