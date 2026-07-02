# Gruppindelning

Lokalt skrivbordsverktyg (Mac + Windows) som hjälper kansliet i en idrottsförening att skapa optimerade, **förklarbara** träningsgrupper: importera anmälningsdata, strukturera fält, konfigurera regler och vikter, kör Timefold-optimering, förstå varje placering, testa flyttar, lås och kör om, exportera.

Första användningsfall: beachvolleyträning (~130 spelare / 12 nivågrupper per kategori och termin), men import och regler är generella.

**Kärnprincip:** ingen svart låda. Verktyget svarar alltid på *"Varför hamnade Kalle i grupp Y och inte i grupp C med sin kompis?"* utifrån synliga regler, vikter och strukturerad data — aldrig AI-gissningar. Kommentarer från anmälan tolkas aldrig automatiskt och lämnar aldrig datorn.

## Status

**MVP funktionellt komplett** (milstolpe M9, `docs/plan.md`) — samtliga kärnflöden
(import → strukturera fält → resurser/tränare → optimera → förklara → what-if → lås →
exportera) är byggda och testade. Se `docs/acceptance-vt27-mvp.md` för en
kriterium-för-kriterium-genomgång mot kravspecens §23 acceptanskriterier (backend 501
tester, frontend 105 tester + 7 gröna Playwright-flöden), `docs/plan.md` (färdplan
M0–M9) och `docs/design/` (designdokument). Första skarpa användning: VT27-planeringen
(kansliets dry run med den riktiga anmälningsfilen är sista steget innan skarp drift,
se acceptansdokumentets 👤-markerade punkter).

**Installation:** `docs/installation-mac.md` / `docs/installation-windows.md` — ingen
Java, Node eller annat förinstallerat krävs, allt bundlas i appen.

**Release-process:** en tagg `vX.Y.Z` pushad till `main` triggar `.github/workflows/release.yml`,
som bygger signerade ad-hoc-installerare för Mac (`.dmg`) och Windows (`.exe`/NSIS),
installerar och röktestar de faktiska artefakterna på riktiga CI-maskiner (inte bara
den obundlade binären), och publicerar ett utkast till GitHub Release med båda
installerarna bifogade.

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
