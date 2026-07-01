# Gruppindelning

Lokalt skrivbordsverktyg (Mac + Windows) som hjälper kansliet i en idrottsförening att skapa optimerade, **förklarbara** träningsgrupper: importera anmälningsdata, strukturera fält, konfigurera regler och vikter, kör Timefold-optimering, förstå varje placering, testa flyttar, lås och kör om, exportera.

Första användningsfall: beachvolleyträning (~130 spelare / 12 nivågrupper per kategori och termin), men import och regler är generella.

**Kärnprincip:** ingen svart låda. Verktyget svarar alltid på *"Varför hamnade Kalle i grupp Y och inte i grupp C med sin kompis?"* utifrån synliga regler, vikter och strukturerad data — aldrig AI-gissningar. Kommentarer från anmälan tolkas aldrig automatiskt och lämnar aldrig datorn.

## Status

Under utveckling — se `docs/plan.md` (färdplan M0–M9) och `docs/design/` (designdokument). Första skarpa användning: VT27-planeringen.

## Arkitektur (kort)

Tauri v2-skal → spawnar lokal Java-backend (Spring Boot 3.5 + **Timefold Solver Community 1.33.0** + SQLite) på slumpad localhost-port → React/Vite-frontend (svenska). Java-runtime bundlas (jlink) — slutanvändare installerar bara appen. Detaljer: `docs/adr/`.

## Utveckling

Krav: Temurin 21, Node 24, Rust stable (för skalet), Maven (wrapper committad).

```bash
npm run dev        # backend :4517 + Vite :5173 — utveckla i vanlig webbläsare
npm run test:backend && npm run test:web
```

**Viktigt:** `CLAUDE.md` innehåller bindande regler för AI-kodningsagenter (konfidentialitet, versionslås, determinism). Läs den innan du ändrar något.

## Konfidentialitet

Riktiga anmälningsfiler (med personnummer och känsliga kommentarer) ligger **utanför** repot och får aldrig committas. `scripts/check-no-confidential.sh` körs som pre-commit-hook och CI-grind. Endast genererad, anonymiserad testdata under `test-data/datasets/` är tillåten. Aktivera hooken efter kloning: `git config core.hooksPath .githooks`
