# Acceptanskriterier VT27 MVP — verifieringsgenomgång (M9)

Genomgång av `../utkast-kravspec.txt` §23.1–§23.10 (kriterium för kriterium) inför tagg
`v0.1.0`. Varje pekare nedan är verifierad att den faktiskt existerar (fil öppnad/grep:ad,
inte gissad från filnamn) under 2026-07-02. Backend: `./mvnw clean verify` — **501 tester,
0 failures, 0 errors**. Frontend: `npx tsc -b --noEmit` grönt, `npx vitest run` — **105
tester, 17 filer, alla gröna**. Playwright: samtliga 7 specs gröna i webbläsarläge (se
Bilaga A). Fullständiga transkript i Bilaga A–D.

## Statuslegend

| Symbol | Betydelse |
|---|---|
| ✅ Verifierad | Automatiskt test/CI-jobb bevisar kriteriet, pekare kontrollerad |
| ⚠️ Delvis | Kärnfunktionen fungerar och är delvis testad, men täckningen har en namngiven lucka |
| ❌ Saknas | Ingen fungerande täckning hittad |
| 👤 Kräver användare | Kan bara verifieras av kansliet med den riktiga konfidentiella filen eller på en riktig, orörd Mac/Windows-maskin — agenten får varken läsa filen (`CLAUDE.md`) eller har fysisk tillgång till en sådan maskin |

---

## §23.1 Import

| # | Kriterium | Status | Evidens | Anteckning |
|---|---|---|---|---|
| 1 | Användaren kan importera exempel-Excel-filen | 👤 | — | "Exempel-Excel-filen" = den riktiga `Torsdagsträning VT26.xlsx` (konfidentiell, ligger utanför repot, får aldrig öppnas av en agent — `CLAUDE.md`). Kan bara verifieras av kansliet lokalt. **Stark proxy-täckning finns redan**: `backend/src/test/java/se/klubb/groupplanner/importer/parse/XlsxParserTest.java` (t.ex. `neverCrashesOnTheMessyFixtureAndReadsAllSheets`, `swedishCharactersSurviveIntact`) parsar en riktig POI-genererad `.xlsx` med celler/trådade kommentarer, sammanslagna celler och blandade typer — samma struktur som originalfilen (se `CLAUDE.md`/`docs/plan.md` "Verified structure"). `ImportControllerIntegrationTest#fullWizardFlowFromUploadToCommitAndTemplateReuse` kör hela guiden (upload→preview→header→mapping→validate→commit) mot en riktig `.xlsx`-byteström via det riktiga REST-API:t. Instruktion: kör importguiden mot den riktiga filen lokalt som del av VT27-dry-run (se `docs/installation-mac.md`/`installation-windows.md` för hur appen startas). |
| 2 | Användaren kan välja sheet | ⚠️ Delvis | `ImportSessionServiceTest#createSessionReturnsSheetSummariesWithRowCounts`, `ImportControllerIntegrationTest` (`GET .../preview?sheet=...`, `PUT .../header {sheet,...}`) | Sheet-listning/val-vägen är kodad och testad, men **varje testfixtur (`MessyWorkbookBuilder`) har bara ett sheet** — inget test bygger/övar en riktig fleraskets-arbetsbok (originalfilen har 2: Herr/Dam). `frontend/e2e/import-flow.spec.ts` testar också bara enkel-sheet-fallet (CSV har alltid ett sheet). Se "Kända luckor". |
| 3 | Användaren kan mappa kolumner | ✅ Verifierad | `ImportControllerIntegrationTest` (`$.columns[1].suggestedTarget`, `PUT .../mapping`); `frontend/src/routes/import/steps/MappingStep.test.tsx` ("lets the user change a column's target..."); `frontend/e2e/import-flow.spec.ts` steg 3 | |
| 4 | Kommentarer importeras som informationsfält | ✅ Verifierad | `ImportControllerIntegrationTest` (`johanProfile.importedComment()` == fixturens text, landar i `participant_profile.imported_comment`, inte en constraint); `MappingStep.test.tsx` ("...marks the comment column as sensitive"); `frontend/e2e/field-builder.spec.ts` rad 120 (griden visar bara en indikator, aldrig texten) | |
| 5 | Inga kommentarer används automatiskt av optimeringen | ✅ Verifierad | `backend/src/test/java/se/klubb/groupplanner/solver/run/OptimizationRunSnapshotLeakTest.java#solverRunSnapshotsNeverContainImportedCommentOrInternalNoteText` — planterar unik kommentartext, kör en riktig solve, läser rå-DB-kolumner `input_snapshot_json`/`constraint_weights_json`/`result_summary_json`, asserterar frånvaro (med positiv kontroll). Strukturellt: `solver.domain`-paketet har **noll** kommentarfält (`grep -r comment backend/src/main/java/.../solver/domain` → 0 träffar). Kompletteras av `ExplanationRecordLeakTest` och `SavedPlanSnapshotLeakTest`. | Denna typ av automatiserat läckagetest är precis den typ av bevis kravspec §26.3–4 efterfrågar. |

## §23.2 Fält och constraints

| # | Kriterium | Status | Evidens | Anteckning |
|---|---|---|---|---|
| 1 | Användaren kan se standardfält | ✅ Verifierad | `FieldDefinitionControllerTest#listsNineteenStandardFieldsForAnExistingPlan` (20 fält: 19 från spec §9.2 + `mustNotPlayWith` tillagt M6a) | |
| 2 | Användaren kan skapa egna fält | ✅ Verifierad | `FieldDefinitionCrudControllerTest#createsThePersonRelationExampleFieldFromSpecSection9Point1` (spec §9.1-exemplet `wantsToPlayWith`); `frontend/e2e/field-builder.spec.ts` skapar "Vill spela med" end-to-end genom riktig UI | |
| 3 | Användaren kan koppla fält till constraints | ✅ Verifierad | `FieldDefinitionCrudControllerTest#rejectsConstraintTypeIncompatibleWithFieldType` (fieldType↔constraintType-kompatibilitetstabell, dokumenterad `backend/docs/m4-notes.md`); `field-builder.spec.ts` (modalen väljer automatiskt `SAME_GROUP` när "påverkar optimering" slås på) | |
| 4 | Användaren kan sätta hard/soft och vikt | ✅ Verifierad | `FieldDefinitionCrudControllerTest` (`rejectsMediumHardOrSoftWithClearMessage`, `rejectsHardConstraintWithAWeight`, `rejectsSoftConstraintWithoutAPositiveWeight`, `rejectsSoftConstraintWeightAboveTenThousand`, `patchingHardOrSoftToHardAutomaticallyDropsAStaleWeight`); `ConstraintWeightFanOutTest#changingGroupSizeTargetWeightAlsoChangesItsEmptyGroupComplement` bevisar att en viktändring faktiskt slår igenom i en riktig Timefold `ScoreAnalysis`; `field-builder.spec.ts` sätter vikt 80 och verifierar SOFT-radioknappen | |

## §23.3 Resurser

| # | Kriterium | Status | Evidens | Anteckning |
|---|---|---|---|---|
| 1 | Användaren kan skapa träningstider | ✅ Verifierad | `TimeSlotControllerTest#createReadUpdateDeleteHappyPathWithAutoLabel` (auto-etikett "Torsdag 18.00–19.30"); `frontend/e2e/resources-coaches-capacity.spec.ts` | |
| 2 | Användaren kan ange antal banor per tid | ✅ Verifierad | `TrainingBlockControllerTest#specExampleFourCourtsCreatesFourBlocksNamedBana1To4` (`PUT .../time-slots/{id}/courts {"count":4}` — det exakta exemplet ur kravspecen); E2E fyller i 4 banor, verifierar 4 block-chips | |
| 3 | Systemet skapar TrainingBlocks | ✅ Verifierad | Samma test + `#threeSlotsWithFourFourThreeCoursesShareClubWideCourtsAndTotalElevenBlocks` (3 tider × banor = 11 block); `backend/docs/m5-notes.md` (TrainingBlockGenerationService) | |
| 4 | Användaren kan inaktivera en TrainingBlock | ✅ Verifierad | `TrainingBlockControllerTest#manualPatchDeactivatesOneBlockWithoutAffectingSiblings` (spec §12.3), `#manualDeactivationSurvivesRegenerationUntilManuallyReactivated`; E2E "Bana 2 aktiv"-switch | |

## §23.4 Tränare

| # | Kriterium | Status | Evidens | Anteckning |
|---|---|---|---|---|
| 1 | Användaren kan skapa tränare | ✅ Verifierad | `CoachControllerTest#createFromNewPersonSetsCanBeCoachOnPerson`; E2E skapar tränare "Lena Holmberg" | |
| 2 | Tränare kan ha tillgänglighet | ✅ Verifierad | `CoachControllerTest#availabilityTriStateRoundTrip` (AVAILABLE/PREFERRED/UNAVAILABLE); `solver/constraints/CoachConstraintsTest#coachUnavailableAtScheduledTimePenalizes`; E2E SegmentedControl "Föredrar" | |
| 3 | Tränare kan ha nivå | ✅ Verifierad | `CoachControllerTest` (`$.coachLevel == 650.0` + intervallvalidering); solver-effekt: `TimeAndCoachSoftConstraintsTest#coachMaxSixHundredGroupMeanSevenHundredPenalizesByOneHundred` (§10.20 coachLevelFit) | |
| 4 | Tränare kan också vara spelare | ✅ Verifierad | `CoachControllerTest#createFromExistingPersonFlipsCanBeCoachOnAndAllowsBothRoles`; `OverlapConstraintsTest#coachPlaysWhileCoachingAtOverlappingTimePenalizes` (§10.17) | |
| 5 | Tränare dubbelbokas inte | ✅ Verifierad | `OverlapConstraintsTest#coachDoubleBookedAtOverlappingTimesPenalizes` (HARD-constraint `coachNoOverlap`, `GroupPlanConstraintProvider.java`, spec §10.15); `solver/regression/SolverRegressionTest#allDatasetsSolveFeasibleAndMatchGoldenScoresExactly` asserterar `hardScore==0` på flertränar-dataset (`coach-overlap-20`) | Bevisat på ConstraintVerifier-nivå (§26.13-kravet: varje hard constraint har ett dedikerat test) + gyllene regressionstest. Inget dedikerat test tvingar fram en tränarkrock i en fullständig löst plan för att specifikt bevisa scenariot end-to-end — lågprioriterad lucka, se "Kända luckor". |

## §23.5 Optimering

| # | Kriterium | Status | Evidens | Anteckning |
|---|---|---|---|---|
| 1 | Timefold kör lokalt | ✅ Verifierad | `backend/src/main/resources/solverConfig.xml` (rent lokal XML-konfiguration, `NO_ASSERT`, `randomSeed 0`); `pom.xml` innehåller endast `timefold-solver-spring-boot-starter`/`timefold-solver-test` (Community), ingen extern endpoint någonstans i solver-koden | |
| 2 | Spelare tilldelas grupper | ✅ Verifierad | `solver.domain.PlayerAssignment` (`@PlanningEntity`, `@PlanningVariable Group group`, `allowsUnassigned=true`) | |
| 3 | Grupper tilldelas tid/bana | ✅ Verifierad | `solver.domain.GroupSchedule` (`@PlanningVariable TrainingBlock trainingBlock`) | |
| 4 | Tränare tilldelas grupper | ✅ Verifierad | `solver.domain.CoachSlot` (`@PlanningVariable(allowsUnassigned=true) CoachFact coach`) | |
| 5 | Hard constraints respekteras | ✅ Verifierad | 90 `@Test`-metoder i 11 filer under `solver/constraints/` (ConstraintVerifier, en fil per constraint-familj — `CapacityConstraintsTest`, `CoachConstraintsTest`, `OverlapConstraintsTest`, `PersonWishConstraintsTest`, `TimeAvailabilityConstraintTest`, `SavedPlanConstraintsTest`, `UnassignedPlayerConstraintTest`, m.fl.); `SolverRegressionTest#allDatasetsSolveFeasibleAndMatchGoldenScoresExactly` asserterar `hardScore==0` mot `test-data/regression/expected-scores.json` på alla tre dataset | |
| 6 | Soft constraints påverkar resultatet | ✅ Verifierad | `solver.WeightOverrideFlipTest#crankingSameGroupSoftWeightFlipsTheFriendUnionVsSizeBalanceTradeOff` — höjer `sameGroupSoft`-vikten 80→150 och visar att solvern byter vilket val den gör (delad vs. förenad grupp), med scorebidrag verifierade per constraint-nyckel | |

## §23.6 Sparande

| # | Kriterium | Status | Evidens | Anteckning |
|---|---|---|---|---|
| 1 | Plan kan sparas | ✅ Verifierad | `SavedPlanControllerIntegrationTest#postCreatesAStatusSavedRowWithAParsedSnapshot`; E2E sparar "Version 1", verifierar "Sparad" | |
| 2 | Plan kan låsas | ✅ Verifierad | `SavedPlanControllerIntegrationTest#legalTransitionMatrixThroughTheRealEndpoint` (saved→locked→published→archived); `savedplan/SavedPlanLifecycleTest` (parametriserad legal/illegal-matris); E2E klickar "Lås", verifierar "Låst" + borttagning avstängd | |
| 3 | Låsta placeringar kan inte ändras av ny solve | ✅ Verifierad | `solver/LockAndResolveIntegrationTest#lockingAllThreeKindsThenSolvingHonorsEveryLockWhileOptimizingTheRest` (riktig async-solve, alla tre låstyper bevarade, resten omoptimerad); `solver/LockRespectTest` (tre `pinned...SurvivesAFullSolve...`-metoder, byggda så ett olåst solve skulle valt annorlunda); `solver/CrossPlanBlockingTest#forcingAnnaIntoTheBlockedEighteenGroupViaAnExplicitLockProducesAVisibleHardViolation` | Motsvarar `docs/plan.md` M6b-radens "lock+re-solve golden test". |
| 4 | Sparad plan kan användas som blockerande constraint | ✅ Verifierad | `solver/CrossPlanBlockingTest#annaCannotBePlacedAsPlayerAtEighteenButCanAtNineteenThirty` — **spec §13.2 Anna-exemplet verbatim** (bekräftat i `backend/docs/m6b-notes.md`); `api/SavedPlanCrossPlanBlockingE2ETest#savingAndLockingHerrThroughTheRealEndpointsBlocksAnnaFromDamAtEighteen` (riktiga endpoints, riktig async Dam-solve); `SavedPlanConstraintsTest` (§10.24a–c person/tränare/bana-krock); `season/ConflictServiceSavedPlanTest` | |

## §23.7 Förklarbarhet

| # | Kriterium | Status | Evidens | Anteckning |
|---|---|---|---|---|
| 1 | Klicka på spelare, se varför placeringen gjordes | ✅ Verifierad | `explain/Section23Point7Test#allFiveSection23Point7BulletsAreSatisfied` bullet 1 (`explainPerson(...).selectedGroup()` ≠ null); `frontend/e2e/whatif-explain.spec.ts` klickar `explainButton`, verifierar dialog + `explain-selected-group` | |
| 2 | Systemet visar uppfyllda faktorer | ✅ Verifierad | `Section23Point7Test` bullet 2 (`positiveFactors()` icke-tom); E2E `explain-positive-factor` | |
| 3 | Systemet visar brutna önskemål | ✅ Verifierad | `Section23Point7Test` bullet 3 (`brokenWishes()` icke-tom, `messageSv()` ej blank); `explain/CanonicalQuestionTest#kalleIsExplainedWhyHeIsNotInGroupCWithHisFriendLisa` (`sameGroupSoft`-önskemål, text "Kompisönskemål"/"brutet"); E2E `group-explain-broken-wish-members` | |
| 4 | Systemet visar varför minst en alternativ grupp inte valdes | ✅ Verifierad | `Section23Point7Test` bullet 4 (varje `alternatives()`-post har `verdict()`/`narrativeSv()`); `explain/WhatIfServiceTest#whyNotAnswersForAnArbitraryGroupNotJustTheAutomaticCandidateSet` (fungerar även för en grupp utan koppling till spelaren); E2E "Varför inte...?"-kombobox + `explain-why-not` | |
| 5 | Systemet visar relevanta constraint weights | ✅ Verifierad | `Section23Point7Test` bullet 5 (`appliedWeights()` icke-tom, `label`/`level`); E2E `plan-analysis-section` | |
| — | **Kanonisk fråga** ("Varför hamnade Kalle i grupp Y och inte i grupp C med sin kompis?") | ✅ Verifierad | `explain/CanonicalQuestionTest` — `kalleIsExplainedWhyHeIsNotInGroupCWithHisFriendLisa` (verdict `WOULD_BREAK_HARD`, narrativ "Grupp C"/"full"/"5/5", `origin` innehåller `FRIEND_WISH`) + `waitlistedFriendEdgeLisaUnassignedProducesOplaceradNarrativeWithLink` (Lisa oplacerad-varianten, länk till hennes egen förklaring) | Exakt den fråga spec §27 pekar ut som viktigast — täckt med båda varianterna (kompis placerad / kompis på kölista). |

## §23.8 What-if

| # | Kriterium | Status | Evidens | Anteckning |
|---|---|---|---|---|
| 1 | Användaren kan testa att flytta en spelare | ✅ Verifierad | `explain/WhatIfServiceTest#moveReportsSizesSpreadScoreDeltaAndHardBreakFlag`, `#moveThatOverflowsMaxSizeReportsHardBreak`; E2E `testMoveButton` | |
| 2 | Systemet visar konsekvens på gruppstorlek, nivå och score | ✅ Verifierad | `WhatIfServiceTest` (`groupSizeChanges()` from/to/max, `levelSpreadChanges()`, `scoreDelta()`, `wouldBreakHard`); E2E `whatif-consequence`, `scoreDeltaLabel`, `groupSizeChangesHeading` | |
| 3 | Användaren kan låsa flytten och köra om optimering | ⚠️ Delvis | Frontend: `frontend/src/routes/plan/results/explain/WhatIfDialog.tsx` (`handleLockAndResolve` → flytta → låsa, knapp `sv.results.whatIf.actions.lockAndResolve`); backend-mekanismen bevisad av `solver/LockAndResolveIntegrationTest#lockingAllThreeKindsThenSolvingHonorsEveryLockWhileOptimizingTheRest` | Ingen E2E/integrationstest kör den exakta knappkedjan "Lås & markera för omoptimering" från UI genom hela flytta→lås→omsolve-sekvensen som knappen faktiskt anropar — `whatif-explain.spec.ts` övar bara plain "flytta ändå" och separat omsolve; `LockAndResolveIntegrationTest` låser via direkta repository-anrop, inte via samma REST-sekvens som dialogen. Mekanismen (lås respekteras vid solve) är hårt bevisad; den exakta UI-knappkedjan är det inte. Se "Kända luckor". |

## §23.9 Export

| # | Kriterium | Status | Evidens | Anteckning |
|---|---|---|---|---|
| 1 | Användaren kan exportera resultat till Excel eller CSV | ✅ Verifierad | `exporter/FlatExportTest` (CSV `;`-separerad UTF-8-BOM, §20.2-kolumner, platt xlsx); `exporter/GroupedXlsxExportTest` (grupperad xlsx-layout, POI-återinläsning); `frontend/e2e/saved-plans-export.spec.ts` laddar ner riktig csv och anonymiserad xlsx | E2E klickar inte den vanliga (icke-anonymiserade) xlsx-radioknappen — bara csv (normal) och xlsx (anonymiserad) körs genom webbläsaren. Backend-xlsx-generering är oberoende bevisad via `GroupedXlsxExportTest`. Liten lucka, se "Kända luckor". |
| 2 | Kommentarer exporteras inte som standard | ✅ Verifierad | `exporter/CommentLeakExportTest#commentsAreAbsentFromEveryDefaultExportVariantAtByteLevel` — skannar dekomprimerade xlsx-zip-poster (inkl. `sharedStrings.xml`) och avkodad csv efter planterad kommentartext, med `includeComments=false` som default; positiv kontroll `includeCommentsTrueActuallyEmitsTheImportedCommentButNeverTheInternalNote`; `anonymizedExportNeverContainsCommentsRegardlessOfAnyParameter`; E2E växlar `includeCommentsLabel`-kryssrutan, verifierar varningen bara visas när ikryssad | Det starkaste enskilda beviset i hela genomgången — byte-nivå-test, inte bara fältnivå. |

## §23.10 Cross-platform

| # | Kriterium | Status | Evidens | Anteckning |
|---|---|---|---|---|
| 1 | Appen kan köras på Mac | ✅ Verifierad | CI-körning `28583133698` (grön): `backend (macos-latest)`, `packaged-smoke (macos-latest)` båda ✓; Release-körning `28558811284`: riktig `.dmg` monterad, `.app` kopierad till en "installations"-katalog, `--smoke` kört, loggen visar `SMOKE OK: installed .app launched...` | |
| 2 | Appen kan köras på Windows | ✅ Verifierad | Samma CI-körning: `backend (windows-latest)`, `packaged-smoke (windows-latest)` ✓; Release-körning: riktig NSIS `.exe` installerad tyst (`/S`), `--smoke` kört, loggen visar `SMOKE_OK` i stdout och exit-kod 0 | |
| 3 | Användaren behöver inte installera Java manuellt | ✅ Verifierad | `scripts/build-jre.sh`/`.ps1` (jlink, moduler pinnade i `scripts/jre.env`, jdeps-drift-grind i CI); `packaged-smoke`-jobbet startar `resources/jre/bin/java -jar backend.jar` direkt (ingen systeminstallerad Java); release-jobbet installerar den riktiga `.dmg`/`.exe` och kör `--smoke` mot det paketerade JRE:t — grönt på båda OS | |
| 4 | Samma testdataset ger reproducerbart resultat | ✅ Verifierad | `solver/regression/SolverRegressionTest#allDatasetsSolveFeasibleAndMatchGoldenScoresExactly` jämför mot `test-data/regression/expected-scores.json`; samma testklass körs på både `macos-latest` och `windows-latest` i `backend`-jobbet — en avvikelse failar CI per konstruktion; `#doubleSolveFromIndependentAssembliesOfTheSameDataIsScoreIdentical` verifierar dessutom determinism inom samma JVM | Dataseten (`test-data/datasets/{small-10,coach-overlap-20,large-120}`) motsvarar spec §22.2:s 10/20/120-spelarkrav (obs: `large-120` innehåller faktiskt 130 rader — namnavvikelse, ingen funktionell brist). |
| 👤 | Färsk, osignerad maskin — Gatekeeper/SmartScreen med endast installationsguiderna | 👤 | `docs/installation-mac.md`, `docs/installation-windows.md` | CI-jobbens install+smoke körs på filer byggda och kopierade inom samma CI-jobb, vilket **aldrig** sätter macOS quarantine-xattr — Gatekeeper ingriper därför aldrig i CI (dokumenterat i `release.yml`s egna kommentarer). M0-kravet "en riktigt orörd, karantänsatt Mac/Windows-maskin öppnar appen med enbart guiderna som stöd" kan bara verifieras av en människa på en riktig maskin. Instruktion: testa `.dmg`/`.exe` från senaste GitHub Release på en dator som aldrig haft appen installerad, följ bara `docs/installation-mac.md`/`installation-windows.md`. |

---

## Sammanfattning — antal per status

| Status | Antal |
|---|---|
| ✅ Verifierad | 40 |
| ⚠️ Delvis | 2 |
| ❌ Saknas | 0 |
| 👤 Kräver användare | 2 |
| **Totalt rader (§23.1–23.10 = 42 kravspec-kriterier + kanonisk fråga-raden + fräsch-maskin-raden)** | **44** |

Ingen ❌ hittades. De tre milstolparna som föregick M9 var granskningsgrindade
(Fable/Opus-review per `docs/plan.md` "Multi-model execution protocol"), vilket matchar
förväntan i uppdraget — luckorna som hittades är samtliga täckningsluckor i redan
fungerande funktionalitet, inte trasiga flöden.

## §22.4 UI-testflöden — täckning

Samtliga tio flöden i kravspec §22.4 körs av de sju Playwright-specen under
`frontend/e2e/` (körda grönt lokalt, se Bilaga A):

| Flöde | Spec |
|---|---|
| Importera fil, Mappa kolumner | `import-flow.spec.ts` |
| Skapa fält | `field-builder.spec.ts` |
| Skapa tider/banor, Lägg till tränare | `resources-coaches-capacity.spec.ts` |
| Kör optimering | `optimize-results.spec.ts`, `whatif-explain.spec.ts` |
| Förklara placering, Testa flytt | `whatif-explain.spec.ts` |
| Lås placering | `saved-plans-export.spec.ts`, `whatif-explain.spec.ts` |
| Kör om | `whatif-explain.spec.ts` (omsolve rensar staleness) |
| Exportera | `saved-plans-export.spec.ts` |

---

## Kända luckor

Dessa är samtliga ⚠️ Delvis-poster ovan plus två mindre observationer — inget är ❌, så
inget av detta blockerar `v0.1.0`, men listas här som rekommenderad efterarbete inför/under
VT27-planeringen:

1. **§23.1.2 — Sheet-val bara testat med ett sheet.** Ingen automatisk testfixtur har mer
   än ett sheet (originalfilen har exakt 2: Herr/Dam). Rekommendation: när kansliet gör
   VT27-dry-runen med den riktiga filen (§23.1.1, 👤) är detta samtidigt det verkliga
   testet av flera-sheet-valet. Om ni vill ha automatiserad täckning innan dess: utöka
   `MessyWorkbookBuilder` med ett andra sheet och lägg till ett
   `ImportSessionServiceTest`-fall — litet jobb, men inte gjort i denna omgång eftersom
   det inte är en trasig funktion, bara en otestad väg.
2. **§23.1.1 — xlsx-import inte körd genom webbläsar-UI:t i Playwright** (bara CSV;
   xlsx är fullt bevisat på backend/REST-nivå med riktiga POI-byte). Lågt praktiskt
   risk eftersom filuppladdningen är byte-agnostisk (multipart oavsett filändelse och
   parsern är samma kod), men den riktiga filen bör ändå köras av kansliet manuellt.
3. **§23.4.5 — Tränarkrock bevisad på ConstraintVerifier- och gyllene-score-nivå, inte via
   ett dedikerat "tvinga fram krock i en fullständig solve"-integrationstest.** Låg
   prioritet — täckningen som finns uppfyller redan spec §26.13:s krav (varje hard
   constraint har ett dedikerat test).
4. **§23.8.3 — "Lås & markera för omoptimering"-knappens exakta UI→API-kedja saknar
   E2E-test.** Mekanismen (låsning respekteras av en efterföljande solve) är hårt bevisad
   via `LockAndResolveIntegrationTest`; det som saknas är ett test som klickar just den
   knappen i `WhatIfDialog` och verifierar att den anropar samma sekvens. Rekommendation:
   lägg till ett `whatif-explain.spec.ts`-steg som klickar "Lås & markera för
   omoptimering" och verifierar staleness-flaggan + att en efterföljande omsolve
   respekterar flytten — cirka 15–20 rader, inte gjort i denna omgång.
5. **§23.9.1 — E2E klickar inte den icke-anonymiserade xlsx-nedladdningen**, bara csv och
   anonymiserad xlsx. Backend-genereringen är oberoende bevisad. Trivial att lägga till
   om ni vill stänga även denna.
6. **Namnavvikelse (ofarlig):** `test-data/datasets/large-120/` innehåller 130 spelarrader,
   inte exakt 120. Katalognamnet är historiskt; ingen funktionell brist eftersom
   kravspec §22.2 bara kräver "~120".

## Rättade luckor (denna omgång)

0. **Systemisk Windows-CI-flake: `SQLITE_BUSY` ("database is locked") i test-setup**
   (körning 28589334486, `backend (windows-latest)`, drabbade
   `SolveControllerIntegrationTest.fullLifecycleStartStatusCompletionWritesResultsBack` och
   `StalenessAndApplyMoveTest.applyMoveToWaitlistSetsGroupIdNull`). Rotorsak: testmetoder
   som startar BAKGRUNDS-solves (wall-clock-profiler via `SolveCoordinator`/REST) kunde
   avsluta metoden medan solven fortfarande körde; dess writeback höll sedan
   SQLite-skrivlåset (längre än `busy_timeout` 5 s på långsamma runners) medan NÄSTA
   testmetods setup-INSERT:ar kördes. Två testers inline-städning väntade dessutom bara på
   `NOT_SOLVING` — som flippar FÖRE writebacken. **Fixad systematiskt**: ny JUnit-extension
   `backend/src/test/java/se/klubb/groupplanner/testsupport/ActiveSolveCleanup.java`
   (efter varje test: avbryt varje icke-terminal `optimization_run` och invänta att
   run-RADEN når terminal status — terminal skrivs strikt EFTER writebacken, så terminal
   rad ⇒ alla skrivlås släppta), inkopplad i alla 7 testklasser som startar asynkrona
   solves; de buggiga/duplicerade inline-städningarna borttagna. Belt-and-braces:
   `busy_timeout` höjd till 30 000 ms **endast i testsviten**
   (`backend/src/test/resources/application.properties`, ny property
   `app.sqlite.busy-timeout-ms`; produktion kvar på 5 000 — se kommentaren i
   `DataSourceConfig`). Verifierat: berörda klasser individuellt gröna + full
   `./mvnw clean verify` grön (501 tester, goldens oförändrade).

1. **CI körde aldrig frontend-testerna** (`tsc`, `vitest`, Playwright) — `.github/workflows/ci.yml`
   hade fyra jobb (`confidentiality`, `backend`, `packaged-smoke`, `shell-check`) men inget
   som motsvarade `docs/plan.md`s Packaging & CI-avsnitt ("CI matrix... Playwright e2e
   (browser mode)...") eller Verification-avsnittet ("E2E: Playwright flows for the §22.4
   list... in browser mode"). Detta var den enda posten i genomgången som var en riktig
   process-lucka snarare än en testtäckningslucka i redan fungerande kod — **fixad**:
   lade till två nya jobb, `frontend` (tsc + vitest, `ubuntu-latest`) och `e2e` (Playwright
   mot en riktig backend-jar + Vite dev-server, `ubuntu-latest`), båda `needs:
   confidentiality`. Kör bara på Linux eftersom Playwright/Chromium-beteendet är
   OS-agnostiskt och den plattforms-specifika risken (JVM/Rust) redan täcks av
   `backend`- och `packaged-smoke`-matriserna på macOS+Windows. Verifierat lokalt: samtliga
   7 Playwright-specs gröna (Bilaga A), `tsc -b --noEmit` grönt, `vitest run` grönt
   (Bilaga B).

---

## Bilaga A — Playwright e2e, fullständig körning (lokalt, browser-läge)

```
$ npx playwright test
...
Running 7 tests using 1 worker

  ✓  1 [chromium] › e2e/field-builder.spec.ts:23:1 › Fältbyggare + Deltagarvy: create field → import → edit level → recompute → link via custom field → anonymize (6.0s)
  ✓  2 [chromium] › e2e/import-flow.spec.ts:22:1 › import wizard: upload → preview → map → validate → decide duplicate → commit → Deltagare (1.7s)
  ✓  3 [chromium] › e2e/optimize-results.spec.ts:26:1 › Optimering (generera+GREEDY+FAST) → Resultatvy (grupper+kölista+lås) → Planeringskarta (10.1s)
  ✓  4 [chromium] › e2e/plan-flow.spec.ts:8:1 › create season → open it → create activity plan → navigate tabs → delete plan + season (2.4s)
  ✓  5 [chromium] › e2e/resources-coaches-capacity.spec.ts:32:1 › Resurser (tider+banor+block) → Tränare (tillgänglighet) → Kapacitet (dashboard reflects state) (4.8s)
  ✓  6 [chromium] › e2e/saved-plans-export.spec.ts:27:1 › Planer (spara+lås) → Export (flat csv+anonymiserat) → Säsongsvy (antal+konflikter) (4.3s)
  ✓  7 [chromium] › e2e/whatif-explain.spec.ts:37:1 › Optimering (GREEDY, 2 grupper) → Analys/Förklara grupp/Förklara/Testa flytt → manuell flytt → staleness → re-solve rensar (7.1s)

  7 passed (42.0s)
```

## Bilaga B — Frontend statisk kontroll + enhetstester

```
$ npx tsc -b --noEmit
(inga fel, exit 0)

$ npx vitest run
 Test Files  17 passed (17)
      Tests  105 passed (105)
```

## Bilaga C — Backend fullständig testsvit (version 0.1.0)

```
$ ./mvnw clean verify
...
[INFO] Results:
[INFO]
[INFO] Tests run: 501, Failures: 0, Errors: 0, Skipped: 0
[INFO]
[INFO] --- jar:3.4.2:jar (default-jar) @ backend ---
[INFO] Building jar: .../backend/target/backend.jar
[INFO] --- spring-boot:3.5.16:repackage (repackage) @ backend ---
[INFO] BUILD SUCCESS
```

(Byggloggen visar `Building backend 0.1.0` — versionsstämpeln verifierad i samma körning
som testsviten.)

## Bilaga D — Desktop/Rust + versionsstämpel

```
$ cd desktop/src-tauri && cargo check --all-targets --locked
   Compiling gruppindelning-desktop v0.1.0 (.../desktop/src-tauri)
    Finished `dev` profile [unoptimized + debuginfo] target(s) in 2.00s
```

Versionsnummer kontrollerade i samtliga filer (2026-07-02):

| Fil | Version |
|---|---|
| `backend/pom.xml` | 0.1.0 (ändrad från `0.0.1-SNAPSHOT` i denna omgång) |
| `package.json` (root) | 0.1.0 (redan korrekt) |
| `frontend/package.json` | 0.1.0 (redan korrekt) |
| `desktop/package.json` | 0.1.0 (redan korrekt) |
| `desktop/src-tauri/tauri.conf.json` | 0.1.0 (redan korrekt) |
| `desktop/src-tauri/Cargo.toml` | 0.1.0 (redan korrekt) |
| `desktop/src-tauri/Cargo.lock` (`gruppindelning-desktop`-paketet) | 0.1.0 (redan konsistent, `cargo check` bekräftar) |
