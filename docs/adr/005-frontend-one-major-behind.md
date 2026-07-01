# ADR-005: Frontend stack deliberately one major behind newest

**Status:** Accepted (2026-07-01)

## Decision
React 19 + TypeScript 5 + **Vite 7** (not Rolldown-based 8), **Mantine 8** (not 9), **AG Grid Community 35** (not week-old 36), TanStack Query 5, Zustand 5, React Router, openapi-typescript typegen, Vitest + Playwright. One UI kit only (Mantine — Ant Design rejected in adversarial review to remove doc conflicts). Rationale: implementation is done by AI coding agents; reliability on well-trained APIs outweighs novelty. UI text Swedish (`i18n/sv.ts`), code English.
