import { pluralize } from "../lib/pluralizeSv";

/**
 * All user-visible strings live here, in Swedish (CLAUDE.md "Language"). Code identifiers and
 * comments stay English. Import `sv` wherever UI copy is needed instead of inlining literals.
 */
export const sv = {
  app: {
    title: "Gruppindelning",
  },
  nav: {
    home: "Hem",
    season: "Säsong",
    plan: "Plan",
  },
  common: {
    loading: "Laddar…",
    error: "Något gick fel",
    save: "Spara",
    cancel: "Avbryt",
    delete: "Ta bort",
    edit: "Redigera",
    create: "Skapa",
    close: "Stäng",
    back: "Tillbaka",
    name: "Namn",
    status: "Status",
    category: "Kategori",
    nameRequired: "Namn krävs",
    unknownError: "Ett okänt fel inträffade",
  },
  start: {
    heading: "Gruppindelning",
    subheading: "Skapa optimerade träningsgruppsindelningar för klubbens säsonger.",
    createSeasonButton: "Skapa ny säsong",
    openSeasonHeading: "Öppna befintlig säsong",
    noSeasons: "Inga säsonger ännu. Skapa en för att komma igång.",
    recentPlansHeading: "Senaste planer",
    noPlans: "Inga aktivitetsplaner ännu.",
    importButton: "Importera ny fil",
    openButton: "Öppna",
    loadFailed: "Kunde inte hämta säsonger",
    demoDataButton: "Prova med demodata",
    demoDataEmptyStateBody:
      "Vill du se hur appen fungerar direkt? Skapa en demosäsong med hundra påhittade spelare, tränare och önskemål – utan att importera något.",
    demoDataSuccess: "Demodata skapad! Här är en färdig säsong att utforska.",
    demoDataFailed: "Kunde inte skapa demodata",
  },
  createSeasonModal: {
    title: "Skapa ny säsong",
    nameLabel: "Namn",
    namePlaceholder: "t.ex. VT27",
    startDateLabel: "Startdatum",
    endDateLabel: "Slutdatum",
    submit: "Skapa",
    createFailed: "Kunde inte skapa säsongen",
  },
  editSeasonModal: {
    title: "Redigera säsong",
    statusLabel: "Status",
    submit: "Spara",
    updateFailed: "Kunde inte spara säsongen",
  },
  deleteSeasonModal: {
    title: "Ta bort säsong",
    message: (name: string) =>
      `Är du säker på att du vill ta bort säsongen "${name}"? Detta går inte att ångra.`,
    confirm: "Ta bort säsong",
    deleteFailed: "Kunde inte ta bort säsongen",
  },
  season: {
    notFound: "Säsongen kunde inte hittas.",
    plansHeading: "Aktivitetsplaner",
    createPlanButton: "Skapa aktivitetsplan",
    editSeasonButton: "Redigera säsong",
    deleteSeasonButton: "Ta bort säsong",
    noPlans: "Inga aktivitetsplaner ännu. Skapa en för att komma igång.",
    columns: {
      name: "Namn",
      category: "Kategori",
      status: "Status",
      participants: "Deltagare",
      groups: "Grupper",
      coaches: "Tränare",
    },
    participantsPlaceholder: "—",
    loadFailed: "Kunde inte hämta säsongen",
    conflicts: {
      heading: "Konflikter",
      empty: "Inga konflikter.",
      loadFailed: "Kunde inte hämta konflikter",
    },
  },
  createPlanModal: {
    title: "Skapa aktivitetsplan",
    nameLabel: "Namn",
    namePlaceholder: "t.ex. Herr",
    categoryLabel: "Kategori",
    categoryPlaceholder: "t.ex. Herr, Dam, Ungdom",
    submit: "Skapa",
    createFailed: "Kunde inte skapa aktivitetsplanen",
  },
  plan: {
    notFound: "Planen kunde inte hittas.",
    backToSeason: "Tillbaka till säsong",
    editButton: "Redigera plan",
    deleteButton: "Ta bort plan",
    loadFailed: "Kunde inte hämta planen",
    tabs: {
      participants: "Deltagare",
      fields: "Fält",
      resources: "Resurser",
      coaches: "Tränare",
      capacity: "Kapacitet",
      optimize: "Optimering",
      results: "Resultat",
      savedPlans: "Planer",
      export: "Export",
    },
    comingSoon: "Den här vyn kommer i en senare milstolpe.",
  },
  editPlanModal: {
    title: "Redigera aktivitetsplan",
    statusLabel: "Status",
    submit: "Spara",
    updateFailed: "Kunde inte spara aktivitetsplanen",
  },
  planDefaults: {
    heading: "Standardvärden för grupper",
    subheading: "Används av \"Generera grupper\" på Optimering-fliken. Lämna fälten tomma för att använda de inbyggda standardvärdena.",
    targetLabel: "Standard målstorlek",
    targetDescription: "Antal deltagare grupperna siktar mot vid gruppgenerering.",
    minLabel: "Standard minsta storlek",
    minDescription: "Minsta gruppstorlek innan gruppen räknas som för liten.",
    maxLabel: "Standard maxstorlek",
    maxDescription: "Största tillåtna gruppstorlek.",
    levelMinLabel: "Standard min-nivå",
    levelMinDescription:
      "Nivågolv för den lägsta gruppen - gruppen läggs aldrig lägre än detta, även om deltagarnas nivåer skulle motivera det.",
    levelMinPlaceholder: "beräknas från deltagarnas nivåer",
    effectiveSizeError: (min: number, target: number, max: number) =>
      `Storlekarna motsäger varandra: med standardvärden för tomma fält blir minsta ${min}, mål ${target} och max ${max} – minsta ≤ mål ≤ max måste gälla`,
    levelMinRangeError: "Min-nivå måste vara mellan 0 och 1000",
  },
  deletePlanModal: {
    title: "Ta bort aktivitetsplan",
    message: (name: string) =>
      `Är du säker på att du vill ta bort aktivitetsplanen "${name}"? Detta går inte att ångra.`,
    confirm: "Ta bort plan",
    deleteFailed: "Kunde inte ta bort aktivitetsplanen",
  },
  backendStatus: {
    up: "Motorn är igång ✓",
    down: "Kan inte nå motorn",
    reconnecting: "Försöker återansluta till motorn…",
    retryButton: "Försök igen",
    restartFailed: "Det gick inte att starta om motorn. Försök igen.",
  },
  importEntry: {
    title: "Importera ny fil",
    seasonLabel: "Säsong",
    seasonPlaceholder: "Välj säsong",
    planLabel: "Aktivitetsplan",
    planPlaceholder: "Välj aktivitetsplan",
    createPlanButton: "Skapa ny aktivitetsplan",
    continueButton: "Fortsätt",
    noSeasons: "Inga säsonger ännu. Skapa en säsong först.",
    noPlans: "Inga aktivitetsplaner i den här säsongen ännu.",
  },
  participants: {
    heading: "Deltagare",
    importButton: "Importera",
    loadFailed: "Kunde inte hämta deltagare",
    empty: "Inga deltagare ännu. Importera en fil för att komma igång.",
    quickFilterPlaceholder: "Sök deltagare…",
    recomputeLevelsButton: "Räkna om nivåer",
    recomputeLevelsSuccess: (count: number) => `${count} deltagare uppdaterade.`,
    recomputeLevelsFailed: "Kunde inte räkna om nivåer",
    anonymizeButton: "Anonymisera kommentarer",
    anonymizeModal: {
      title: "Anonymisera kommentarer",
      message:
        'Detta raderar permanent kommentaren "Kommentar från anmälan" och "Intern kommentar" för samtliga deltagare i den här planen. Det går inte att ångra.',
      confirm: "Anonymisera alla kommentarer",
      success: (count: number) => `${count} deltagares kommentarer anonymiserades.`,
      failed: "Kunde inte anonymisera kommentarerna",
    },
    columns: {
      name: "Namn",
      ranking: "Ranking",
      previousGroup: "Tidigare grupp",
      level: "Nivå",
      manualLevelScore: "Manuell nivåscore",
      waitlisted: "Kölista",
      needsReview: "Behöver granskning",
      comment: "Kommentar",
    },
    waitlistedBadge: "Kölista",
    needsReviewTooltip: "Behöver manuell granskning",
    commentTooltip: "Har kommentar från anmälan",
    levelConfidence: {
      high: "Hög",
      medium: "Medel",
      low: "Låg",
      none: "Saknas",
    },
    drawer: {
      closeButton: "Stäng",
      commentsHeading: "Kommentarer",
      sensitiveBadge: "Känslig",
      importedCommentLabel: "Kommentar från anmälan",
      internalNoteLabel: "Intern kommentar",
      internalNotePlaceholder: "Kansliets egen anteckning…",
      noComment: "Ingen kommentar registrerad.",
      deleteCommentsButton: "Radera kommentarer",
      deleteCommentsModal: {
        title: "Radera kommentarer",
        message:
          "Detta raderar kommentaren från anmälan och den interna anteckningen för den här deltagaren. Det går inte att ångra.",
        confirm: "Radera kommentarer",
        failed: "Kunde inte radera kommentarerna",
      },
      structuredHeading: "Strukturerade fält",
      manualLevelScoreLabel: "Manuell nivåscore",
      previousGroupNameLabel: "Tidigare grupp",
      previousGroupLevelLabel: "Tidigare gruppnivå",
      waitlistedLabel: "Kölista",
      manualReviewFlagLabel: "Behöver granskning",
      customFieldsHeading: "Anpassade fält",
      timeRelationHint: "Välj tider ur planens schema",
      timeRelationPlaceholder: "Välj tider…",
      timeRelationInvalidValuesNote: "Ogiltiga värden från import ignoreras",
      // v0.3.0 WI-A: per-key explanations for the three seeded timeRelation standard fields, shown
      // as the MultiSelect's description so the field is self-explanatory without opening Fältbyggare
      // (verified against SolverInputAssembler.java's actual consumption of each field, not guessed).
      canTimesDescription:
        "Tider spelaren kan delta på – alla andra tider räknas som \"kan inte\". Lämna tomt om spelaren kan alla tider.",
      cannotTimesDescription: "Tider spelaren inte kan delta på – hård regel, spelaren placeras aldrig på dessa tider.",
      preferTimesDescription: "Tider spelaren helst vill ha – mjuk regel som ger minuspoäng om den bryts.",
      personRelationPlaceholder: "Välj deltagare…",
      tagPlaceholder: "Skriv och tryck Enter…",
      relationUnavailable: "Hanteras från och med en senare milstolpe.",
      saveButton: "Spara",
      saveSuccess: "Sparat.",
      saveFailed: "Kunde inte spara deltagaren",
      fieldValuesSaveFailed: "Kunde inte spara fältvärdena",
    },
  },
  importWizard: {
    title: "Importera deltagare",
    cancelButton: "Avbryt import",
    cancelConfirmTitle: "Avbryt import?",
    cancelConfirmMessage: "Den påbörjade importen kastas. Inget har sparats än.",
    steps: {
      file: "Välj fil",
      sheet: "Blad & granskning",
      map: "Mappa kolumner",
      validate: "Validera",
      commit: "Importera",
    },
    sessionExpired: {
      title: "Sessionen har gått ut",
      message: "Importsessionen har gått ut eller hittas inte längre. Börja om från början.",
      restartButton: "Börja om",
    },
    file: {
      heading: "Välj fil att importera",
      dropHint: "Släpp en .xlsx- eller .csv-fil här, eller välj en fil.",
      pickButton: "Välj fil",
      invalidType: "Endast .xlsx- och .csv-filer stöds.",
      uploadFailed: "Kunde inte läsa in filen",
      uploading: "Läser in fil…",
    },
    sheet: {
      heading: "Välj blad och granska rader",
      headerRowLabel: "Header-rad",
      headerRowHint: "Raden som innehåller kolumnrubriker (0 = första raden).",
      restoreFailedTitle: "Bladlistan kunde inte återställas",
      restoreFailedMessage:
        "Det gick inte att återställa bladlistan efter sidladdningen. Ladda upp filen igen.",
      templateSuggested: (name: string) =>
        `Kolumnmappningen är förifylld från den sparade mallen "${name}".`,
      loadFailed: "Kunde inte hämta förhandsgranskningen",
      nextButton: "Nästa: Mappa kolumner",
    },
    mapping: {
      heading: "Mappa kolumner",
      columnHeader: "Kolumn",
      sampleHeader: "Exempelvärden",
      targetHeader: "Mappas till",
      sensitiveBadge: "Känslig — endast referens",
      ignoreOption: "Ignorera",
      createFieldOption: "Skapa nytt fält…",
      standardGroup: "Standardfält",
      customGroup: "Anpassade fält",
      saveFailed: "Kunde inte spara kolumnmappningen",
      nextButton: "Nästa: Validera",
      customFieldUnavailable:
        'Att skapa nya fält stöds inte ännu i den här versionen (kommer i en senare milstolpe). Kolumnen mappas till "Ignorera" tills vidare.',
      targets: {
        firstName: "Förnamn",
        lastName: "Efternamn",
        displayName: "Namn (fullständigt)",
        email: "E-post",
        phone: "Telefon",
        externalId: "Medlems-id",
        rankingPoints: "Ranking",
        previousGroupName: "Tidigare grupp",
        previousGroupLevel: "Tidigare gruppnivå",
        manualLevelScore: "Manuell nivåscore",
        comment: "Kommentar från anmälan",
        internalNote: "Intern kommentar",
        coachName: "Önskad tränare (fritext)",
        isCoach: "Är tränare",
      },
    },
    newFieldModal: {
      title: "Skapa nytt fält",
      nameLabel: "Namn",
      typeLabel: "Typ",
      submit: "Skapa fält",
      types: {
        text: "Text",
        number: "Nummer",
        boolean: "Ja/nej",
        singleSelect: "Envalslista",
      },
    },
    validate: {
      heading: "Validera importerade rader",
      summary: (ok: number, warn: number, skip: number) =>
        `${ok} OK, ${warn} med varning, ${skip} hoppas över`,
      rowColumn: "Rad",
      statusColumn: "Status",
      reasonsColumn: "Anledning",
      decisionColumn: "Beslut",
      status: {
        OK: "OK",
        WARN: "Varning",
        SKIP: "Hoppas över",
      },
      decision: {
        createNew: "Skapa ny",
        skip: "Hoppa över",
        matchExisting: (name: string, basis: string) => `Koppla till befintlig: ${name} (${basis})`,
      },
      matchBasis: {
        EXTERNAL_ID_EXACT: "matchad på medlems-id",
        EMAIL_EXACT: "matchad på e-post",
        PHONE_EXACT: "matchad på telefon",
        NAME_EXACT: "matchad på namn",
        NAME_SIMILAR: "matchad på liknande namn",
      },
      unknownPerson: "okänd person",
      saveDecisionFailed: "Kunde inte spara beslutet",
      nextButton: "Nästa: Importera",
    },
    commit: {
      heading: "Importera",
      summary: (imported: number, skipped: number) => `${imported} rader importeras, ${skipped} hoppas över`,
      templateNameLabel: "Spara mappning som mall",
      templateNamePlaceholder: "t.ex. Anmälningsformulär VT",
      submit: "Importera",
      committing: "Importerar…",
      commitFailed: "Kunde inte genomföra importen",
      resultHeading: "Import klar",
      resultSummary: (imported: number, skipped: number) =>
        `${imported} deltagare importerades, ${skipped} rader hoppades över.`,
      warningsHeading: "Varningar",
      goToParticipants: "Gå till Deltagare",
    },
  },
  dataGrid: {
    noRows: "Inga rader att visa.",
  },
  fieldTypes: {
    text: "Text",
    number: "Nummer",
    boolean: "Ja/nej",
    singleSelect: "Envalslista",
    multiSelect: "Flervalslista",
    personRelation: "Personrelation",
    coachRelation: "Tränarrelation",
    timeRelation: "Tidsrelation",
    groupRelation: "Grupprelation",
    tag: "Tagg",
  },
  constraintFamilies: {
    NONE: "Ingen",
    LEVEL_BALANCE_INPUT: "Nivåbalans (indata)",
    TIME_AVAILABILITY: "Tidstillgänglighet",
    TIME_PREFERENCE: "Tidspreferens",
    SAME_GROUP: "Samma grupp",
    DIFFERENT_GROUP: "Olika grupper",
    COACH_PREFERENCE: "Tränarpreferens",
    COACH_FORBIDDEN: "Förbjuden tränare",
    PRIORITY: "Prioritet",
  },
  hardOrSoft: {
    HARD: "Hård",
    SOFT: "Mjuk",
    INFO: "Information",
    MEDIUM: "Medium",
  },
  /**
   * v0.3.0 WI-3 (user feedback: "Förbättra användarvänligheten genom att förklara vad olika
   * inställningar innebär.") - short Swedish explanations shown by the `HelpTip` component. Every
   * text here was checked against the actual backend behavior before being written (constraint
   * provider, SolverInputAssembler, SolveController - see call sites for specifics); none of it is
   * a guess about what a setting "probably" does.
   */
  help: {
    ariaLabel: (topic: string) => `Förklaring: ${topic}`,
    fields: {
      whatIsAField:
        "Ett fält är en egenskap hos varje deltagare eller tränare, t.ex. nivå eller ett kompisönskemål. Standardfälten finns alltid färdiga; du kan även skapa egna. Ett fält kan antingen bara vara information, eller kopplas till en optimeringsregel via kolumnerna nedan.",
      affectsOptimization:
        "Om detta är på används fältets värde av optimeringen. Är det av är fältet bara information för din egen skull, utan effekt på hur grupperna sätts ihop.",
      constraintType:
        "Vilken typ av optimeringsregel fältets värde kopplas till, t.ex. att hålla ihop personer som vill spela tillsammans eller undvika en viss tränare. Vilka regler som går att välja beror på fältets typ (text, person, tid osv.).",
      // Review fix (v0.3.0 WI-3): must not promise "aldrig bryts" - the app itself surfaces
      // remaining hard violations ("N hårda brott kvarstår" on the last-run card), and the
      // waitlist escape only exists for player-placement constraints, not system-level ones.
      hardOrSoft:
        "Hård betyder att regeln har högsta prioritet och behandlas som ett krav. Går regeln inte att uppfylla försöker optimeringen hellre lämna en spelare på kölistan än att bryta den; i svåra fall kan hårda brott ändå kvarstå (visas som \"hårda brott kvarstår\"). Mjuk betyder att regeln är ett önskemål som vägs mot planens övriga mjuka regler utifrån vikterna, och kan brytas om helheten blir bättre. Använd Hård bara för sånt som verkligen aldrig får hända.",
      weight:
        "Hur tungt en mjuk regel väger mot planens övriga mjuka regler när optimeringen måste välja mellan dem. Bara det relativa förhållandet mellan vikter spelar roll, inte talet i sig – höj vikten för det som är viktigast för just den här planen.",
      // Review fix (v0.3.0 WI-3): the two tables' Vikt COLUMNS also cover HARD rows (where the
      // weight cell shows "—"), so the column-header tip needs the extra hard-rule clause.
      // NewFieldModal's weight input only renders for SOFT, so it keeps the plain `weight` text.
      weightInTable:
        "Hur tungt en mjuk regel väger mot planens övriga mjuka regler när optimeringen måste välja mellan dem. Bara det relativa förhållandet mellan vikter spelar roll, inte talet i sig – höj vikten för det som är viktigast för just den här planen. För hårda regler har vikten sällan någon praktisk betydelse.",
      // NewFieldModal's own weight input is exact-matched by e2e via getByLabel(weightLabel) with
      // no role filter (Playwright's default substring, case-insensitive match) - a HelpTip whose
      // trigger's own accessible name contains that same label text ("Vikt") would make that
      // locator ambiguous within the modal. This dedicated aria-label carries the same meaning
      // without repeating the word "vikt" verbatim, so the two stay distinguishable. The FieldRow
      // table's/ConstraintWeightsTable's own "Vikt" column HelpTips don't share this modal's scope
      // and can safely reuse the plain ariaLabel(...) form instead.
      weightAriaLabelInModal: "Förklaring: hur mjuka regler vägs mot varandra",
      explanation:
        "En egen förklaringstext till fältet, till hjälp för den som fyller i eller granskar det. Ren dokumentation utan effekt på optimeringen.",
    },
    constraintWeights: {
      section:
        "De inbyggda standardreglerna som optimeringen alltid känner till för den här planen, t.ex. jämn nivåspridning inom en grupp eller att en tränare inte dubbelbokas. Ändra Hård/Mjuk och vikt per regel, eller stäng av en regel helt för just den här planen.",
      enabled:
        "Stäng av en regel helt för den här planen utan att ändra dess vikt eller hård/mjuk-inställning – bra för att se hur mycket regeln faktiskt påverkar resultatet. Rader reserverade för kölistan (Medium) kan inte stängas av.",
    },
    optimize: {
      optimizeOnlyPlayers:
        "Ikryssad: optimeringen får flytta om vilka spelare som hamnar i vilken grupp. Urkryssad: alla spelares nuvarande gruppmedlemskap fryses fast, oavsett om de är individuellt låsta eller inte.",
      optimizeOnlySchedule:
        "Ikryssad: optimeringen får ändra vilken tid och bana varje grupp får. Urkryssad: varje grupps nuvarande tid/bana fryses fast.",
      optimizeOnlyCoaches:
        "Ikryssad: optimeringen får ändra vilken tränare som tilldelas varje grupp. Urkryssad: alla tränartilldelningar fryses fast.",
      blockCoaches:
        "Hindrar tränare som redan är upptagna i en annan låst plan i samma säsong, under en överlappande tid, från att också tilldelas här.",
      blockPlayers:
        "Hindrar spelare/personer som redan är upptagna i en annan låst plan i samma säsong, under en överlappande tid, från att också placeras här.",
      blockCourts:
        "Hindrar banor/tider som redan används av en annan låst plan i samma säsong, under en överlappande tid, från att också användas här.",
      conflictsAsWarnings:
        "Nedgraderar ovanstående blockeringar till varningar för just den här körningen – optimeringen får ändå använda en upptagen person, tränare eller bana om det behövs, men visar det som en varning istället för att blockera helt. Sparas inte som en inställning på planen.",
      solveProfiles:
        "Optimeringen provar och förbättrar lösningen gradvis under den tid den får. Ju längre den kör desto bättre resultat hittar den oftast, men förbättringstakten avtar med tiden – de sista sekunderna ger sällan lika stor skillnad som de första.",
      suggestedTime:
        "Ett förslag på hur många sekunder optimeringen behöver, baserat på planens storlek (spelare, grupper, tränare, önskemål) och hur snabb den här datorn är. Använd förslaget direkt, eller välj en egen tid under Avancerat.",
      groupDefaults:
        "Standardvärdena för målstorlek, min/max och min-nivå styr vad knappen \"Generera grupper\" ovan föreslår innan optimeringen körs. Ändra dem för hela planen via länken \"Ändra…\".",
    },
    resources: {
      courts:
        "Antal banor styr hur många grupper som får plats på den här tiden – varje bana är en möjlig gruppplats. Sänker du antalet stängs de sista banorna av (utan att raderas); höjer du det öppnas de igen.",
      // The courts NumberInput is exact-matched by e2e via getByLabel(courtsLabel) with no role
      // filter, scoped to this same time-slot row - a HelpTip whose own accessible name repeats
      // "Antal banor" verbatim would make that locator ambiguous. Same reasoning as
      // weightAriaLabelInModal above.
      courtsAriaLabel: "Förklaring: banor för den här tiden",
      courtActive:
        "Slå av en enskild bana för att undanta just den från schemaläggning vid den här tiden, t.ex. om den är bokad av någon annan. Optimeringen använder aldrig en avstängd bana, men den finns kvar och kan slås på igen senare.",
      slotRecurrence: "Tiden återkommer varje vecka på den valda veckodagen – inte ett engångstillfälle.",
      slotLabel:
        "Ett eget namn för tiden, t.ex. \"Torsdag kväll\". Lämnas fältet tomt genereras ett namn automatiskt utifrån dag och klockslag.",
    },
    coaches: {
      coachLevel:
        "Tränarens egen nivå, till för din egen överblick i tabellen. Det är fältet Kan träna nivå (från–till) som faktiskt styr optimeringens tränarmatchning – inte det här värdet.",
      canCoachRange:
        "Nivåspannet tränaren helst tränar. Hamnar en grupps nivåsnitt utanför spannet räknas det som en mjuk avvikelse i optimeringen (ju längre utanför desto sämre poäng) – tränaren kan ändå tilldelas gruppen, matchningen blir bara sämre.",
      // Review fix (v0.3.0 WI-3): the solver coalesces the two caps into ONE plan-wide cap
      // (coalesceMaxGroups - week wins when both are set); there is no per-day counting, so both
      // texts must say the cap applies to the plan as a whole.
      maxGroupsPerDay:
        "Hårt tak för hur många grupper tränaren kan ha (taket gäller planen som helhet) – optimeringen överskrider aldrig detta. Anger du även Max grupper/vecka är det veckotaket som gäller istället för dagstaket.",
      maxGroupsPerWeek:
        "Hårt tak för hur många grupper tränaren kan ha totalt under veckan (taket gäller planen som helhet) – optimeringen överskrider aldrig detta. Anger du både denna och Max grupper/dag används veckotaket.",
      alsoParticipant:
        "Markerar att personen även är anmäld som spelare i planen, för din egen överblick. Fältet påverkar i dagsläget inte optimeringen eller schemaläggningen.",
      // WI-B: "Okänd" is no longer scored identically to "Tillgänglig" - it now carries a small
      // soft penalty (coachUnknownTimeSlot) so the optimizer favors coaches who actually filled in
      // their availability. Keep this text truthful to that change - see backend
      // GroupPlanConstraintProvider#coachUnknownTimeSlot / V10 migration.
      availability:
        "Fyra lägen per träningstid: Okänd (inget angivet) räknas som \"föredrar inte\" – liten minuspoäng om tränaren sätts på en sådan tid. Tillgänglig är neutralt. Otillgänglig blockerar helt (hård regel). Föredrar ger en liten bonus.",
    },
    export: {
      includeComments:
        "Kommentarerna kan innehålla känsliga uppgifter om enskilda personer. Kryssa bara i om mottagaren verkligen behöver dem, och sprid aldrig filen vidare efteråt.",
      anonymized:
        "Tar bort namn och kontaktuppgifter men behåller struktur och siffror, så du kan dela planen för felsökning eller utveckling utan att exponera personuppgifter.",
    },
    plan: {
      status:
        "Fri text för din egen uppföljning av planens skede (t.ex. \"utkast\" eller \"klar\"). Fältet styr inget i appen – det är bara en etikett du själv sätter.",
      // Review fix (v0.3.0 WI-3): category is NOT purely cosmetic - GroupGenerator uses it as the
      // name prefix for generated groups ("Herr 1", "Herr 2" ...) - so the text must say so. It
      // still has no effect on how the solver scores a solution.
      category:
        "En egen etikett för planen, t.ex. \"Herr\", \"Dam\" eller \"Ungdom\". Används också som namnprefix när du genererar grupper (\"Herr 1\", \"Herr 2\" …). Påverkar inte hur optimeringen poängsätter lösningen.",
    },
    results: {
      softScore:
        "Lägre mjukt avdrag = färre brutna önskemål och avvikelser.",
    },
  },
  fieldBuilder: {
    heading: "Fältbyggare",
    tabs: {
      fields: "Alla fält",
      configuration: "Konfiguration",
    },
    newFieldButton: "Nytt fält",
    loadFailed: "Kunde inte hämta fält",
    table: {
      label: "Label",
      type: "Typ",
      affectsOptimization: "Påverkar optimering",
      constraint: "Constraint",
      hardOrSoft: "Hård/Mjuk",
      weight: "Vikt",
      explanation: "Förklaringstext",
    },
    standardBadge: "Standard",
    customBadge: "Anpassat",
    mediumReservedTooltip: "Reserverad som MEDIUM för kölistan (systemregel)",
    deleteButton: "Ta bort",
    deleteModal: {
      title: "Ta bort fält",
      message: (label: string) =>
        `Är du säker på att du vill ta bort fältet "${label}"? Detta går inte att ångra.`,
      confirm: "Ta bort fält",
      failed: "Kunde inte ta bort fältet",
    },
    updateFailed: "Kunde inte spara fältet",
    newFieldModal: {
      title: "Nytt fält",
      labelLabel: "Etikett",
      labelPlaceholder: "t.ex. Vill spela med",
      keyPreview: (key: string) => `Fältnyckel: ${key}`,
      typeLabel: "Typ",
      optionsLabel: "Alternativ (kommaseparerade)",
      optionsPlaceholder: "t.ex. Ja, Nej, Vet ej",
      optionsRequired: "Minst ett alternativ krävs för den här fälttypen",
      affectsOptimizationLabel: "Påverkar optimering",
      noCompatibleConstraint: "Den här fälttypen kan inte kopplas till en optimeringsregel.",
      constraintLabel: "Constraint",
      hardOrSoftLabel: "Hård/Mjuk",
      weightLabel: "Vikt",
      weightRequired: "Vikt krävs och måste vara minst 1 för ett mjukt (soft) fält",
      explanationLabel: "Förklaringstext",
      submit: "Skapa fält",
      createFailed: "Kunde inte skapa fältet",
    },
  },
  constraintWeights: {
    heading: "Konfiguration",
    subheading: "De 24 standardconstraints som styr optimeringen för den här planen.",
    loadFailed: "Kunde inte hämta constraint-vikter",
    table: {
      label: "Constraint",
      category: "Kategori",
      hardOrSoft: "Hård/Mjuk",
      weight: "Vikt",
      enabled: "Aktiverad",
    },
    resetButton: "Återställ till standard",
    overriddenBadge: "Anpassad",
    updateFailed: "Kunde inte spara constraint-vikten",
  },
  days: {
    MONDAY: "Måndag",
    TUESDAY: "Tisdag",
    WEDNESDAY: "Onsdag",
    THURSDAY: "Torsdag",
    FRIDAY: "Fredag",
    SATURDAY: "Lördag",
    SUNDAY: "Söndag",
  },
  resources: {
    heading: "Resurser",
    loadFailed: "Kunde inte hämta träningstider",
    empty: "Inga träningstider ännu. Lägg till en tid för att komma igång.",
    newSlotButton: "Ny tid",
    editButton: "Redigera",
    deleteButton: "Ta bort",
    courtsLabel: "Antal banor",
    courtsUpdateFailed: "Kunde inte spara antal banor",
    blocksCount: (n: number) => `${n} block`,
    blocksHeading: "Banor",
    inactiveBadge: "Inaktiverad",
    blockActiveUpdateFailed: "Kunde inte ändra banans status",
    exceptionHint:
      'Att inaktivera en bana är ett manuellt undantag (spec §12.3) – t.ex. att "Bana 4 är inte tillgänglig 21.00–22.30". Undantaget gäller bara den här banan vid den här tiden.',
    deleteModal: {
      title: "Ta bort träningstid",
      message: (label: string) =>
        `Är du säker på att du vill ta bort träningstiden "${label}"? Detta går inte att ångra.`,
      confirm: "Ta bort tid",
      failed: "Kunde inte ta bort träningstiden",
    },
    slotModal: {
      createTitle: "Ny träningstid",
      editTitle: "Redigera träningstid",
      dayLabel: "Dag",
      dayPlaceholder: "Välj dag",
      dayRequired: "Dag krävs",
      startTimeLabel: "Starttid",
      endTimeLabel: "Sluttid",
      labelLabel: "Label",
      labelPlaceholder: "Genereras automatiskt om tomt",
      submit: "Spara",
      createFailed: "Kunde inte skapa träningstiden",
      updateFailed: "Kunde inte spara träningstiden",
    },
  },
  coaches: {
    heading: "Tränare",
    loadFailed: "Kunde inte hämta tränare",
    empty: "Inga tränare ännu. Lägg till en tränare för att komma igång.",
    newCoachButton: "Ny tränare",
    alsoParticipantBadge: "Tränar själv",
    columns: {
      name: "Namn",
      coachLevel: "Tränarnivå",
      canCoachRange: "Kan träna nivå",
      maxGroupsPerDay: "Max grupper/dag",
      maxGroupsPerWeek: "Max/vecka",
      alsoParticipant: "Tränar själv",
      availability: "Tillgänglighet",
    },
    availabilitySummary: (available: number, preferred: number, unavailable: number) =>
      `${available} tillgänglig · ${preferred} föredrar · ${unavailable} otillgänglig`,
    deleteModal: {
      title: "Ta bort tränare",
      message: (name: string) => `Är du säker på att du vill ta bort tränaren "${name}"? Detta går inte att ångra.`,
      confirm: "Ta bort tränare",
      failed: "Kunde inte ta bort tränaren",
    },
    newCoachModal: {
      title: "Ny tränare",
      sourceExisting: "Länka befintlig person",
      sourceNew: "Skapa ny person",
      personLabel: "Person",
      personPlaceholder: "Sök person…",
      personRequired: "Välj en person",
      firstNameLabel: "Förnamn",
      lastNameLabel: "Efternamn",
      emailLabel: "E-post",
      firstNameRequired: "Förnamn krävs",
      lastNameRequired: "Efternamn krävs",
      coachLevelLabel: "Tränarnivå",
      canCoachMinLabel: "Kan träna nivå från",
      canCoachMaxLabel: "Kan träna nivå till",
      maxGroupsPerDayLabel: "Max antal grupper/dag",
      maxGroupsPerWeekLabel: "Max antal grupper/vecka",
      alsoParticipantLabel: "Kan själv också vara spelare",
      notesLabel: "Intern kommentar",
      submit: "Skapa tränare",
      createFailed: "Kunde inte skapa tränaren",
    },
    drawer: {
      closeButton: "Stäng",
      profileHeading: "Profil",
      coachLevelLabel: "Tränarnivå",
      canCoachMinLabel: "Kan träna nivå från",
      canCoachMaxLabel: "Kan träna nivå till",
      maxGroupsPerDayLabel: "Max antal grupper/dag",
      maxGroupsPerWeekLabel: "Max antal grupper/vecka",
      alsoParticipantLabel: "Kan själv också vara spelare",
      notesLabel: "Intern kommentar",
      availabilityHeading: "Tillgänglighet",
      availabilityHint: "Ange tränarens tillgänglighet för varje träningstid i planen (spec §13.1).",
      noTimeSlots: "Inga träningstider ännu. Lägg till träningstider under Resurser först.",
      customFieldsHeading: "Anpassade fält",
      saveButton: "Spara",
      saveSuccess: "Sparat.",
      saveFailed: "Kunde inte spara tränaren",
      availabilitySaveFailed: "Kunde inte spara tillgängligheten",
      fieldValuesSaveFailed: "Kunde inte spara fältvärdena",
      deleteButton: "Ta bort tränare",
    },
    availabilityKind: {
      UNKNOWN: "Okänd",
      AVAILABLE: "Tillgänglig",
      UNAVAILABLE: "Otillgänglig",
      PREFERRED: "Föredrar",
    },
    coachRelationPlaceholder: "Välj tränare…",
  },
  capacity: {
    heading: "Kapacitet",
    loadFailed: "Kunde inte hämta kapacitetsanalysen",
    empty: "Lägg till träningstider under Resurser först.",
    headline: {
      participants: "Antal deltagare",
      activeBlocks: "Aktiva block",
      targetCapacity: "Målkapacitet",
      maxCapacity: "Maxkapacitet",
      waitlistedCaption: (n: number) => `varav ${n} på kölista`,
    },
    groupSizes: (target: number | null, max: number | null) =>
      target != null && max != null
        ? `Target: ${target} spelare/grupp · Max: ${max} spelare/grupp`
        : "Standardstorlekar (target/max) saknas för planen",
    risk: {
      heading: "Risk för kölista",
      none: "Ingen risk för kölista",
      overTarget: "Möjlig kölista",
      overMax: "Kölista krävs",
      unknown: "Kan inte beräknas",
    },
    coachSection: {
      heading: "Tränarkapacitet",
      coachCount: "Antal tränare",
      groupsRequiringCoach: "Grupper som kräver tränare",
    },
    coachShortage: {
      risk: "Risk för tränarbrist",
      ok: "Tillräckligt med tränare",
    },
    noCoaches: {
      title: "Inga tränare registrerade",
      body:
        "Det går utmärkt att optimera ändå – grupperna fördelas då på tider och banor utan tränartilldelning. Vill du att optimeringen även ska placera tränare lägger du till dem under fliken Tränare.",
      goToCoachesButton: "Gå till Tränare",
    },
    perSlotHeading: "Fördelning per träningstid",
    perSlotTable: {
      slot: "Träningstid",
      blocks: "Aktiva block",
      coaches: "Tillgängliga tränare",
      preferred: "Varav föredrar",
      status: "Status",
      deficient: "Otillräckligt",
      ok: "OK",
    },
  },
  optimize: {
    heading: "Optimering",
    loadFailed: "Kunde inte hämta optimeringsstatus",
    groups: {
      heading: "Grupper",
      count: (n: number) => (n === 0 ? "Inga grupper genererade ännu." : n === 1 ? "1 grupp genererad." : `${n} grupper genererade.`),
      generateButton: "Generera grupper",
      generateFailed: "Kunde inte generera grupper",
      generateSuccess: (n: number) => (n === 1 ? "1 grupp genererad." : `${n} grupper genererade.`),
      defaultsSummary: (target: number, min: number, max: number, levelMin: number | null) =>
        `Standard: mål ${target} · min ${min} · max ${max} · min-nivå ${levelMin ?? "—"}`,
      changeDefaultsLink: "Ändra…",
      // WI-C ("re-run doesn't feel like it re-runs" user feedback v0.4 #4): the staleness banner
      // shown above the start controls when GET .../groups/sync-status reports stale:true.
      staleBanner: {
        title: "Grupperna är inte uppdaterade",
        regenerateButton: "Generera om grupper",
      },
    },
    suggest: {
      heading: "Föreslagen optimeringstid",
      loading: "Analyserar planens storlek och din dators hastighet…",
      suggestedSeconds: (s: number) => `Föreslagen optimeringstid: ${s} s`,
      problemSummary: (p: {
        participants: number;
        groups: number;
        activeBlocks: number;
        coaches: number;
        wishes: number;
        customFieldConstraints: number;
      }) =>
        `${p.participants} spelare · ${p.groups} grupper · ${p.activeBlocks} block · ${p.coaches} tränare · ${p.wishes} önskemål · ${p.customFieldConstraints} optimeringsfält`,
      refreshButton: "Uppdatera förslag",
      solveActive: "En optimering pågår redan – ett nytt tidsförslag beräknas när den är klar.",
      loadFailed: "Kunde inte beräkna ett tidsförslag",
      retryButton: "Försök igen",
      optimizeButton: (s: number) => `Optimera (${s} s)`,
    },
    advanced: {
      heading: "Avancerat",
      customSecondsLabel: "Egen tid (sekunder)",
      // WI-C ("re-run doesn't feel like it re-runs" user feedback v0.4 #4, root cause B).
      coldStartLabel: "Börja om från grunden",
      coldStartDescription:
        "Ignorera nuvarande placeringar och bygg grupperna från noll (låsta placeringar behålls). Bra när du ändrat inställningar och vill se en helt ny lösning.",
    },
    profileHeading: "Optimeringsprofil",
    profiles: {
      FAST: {
        label: "Snabb (10 s)",
        description: "Kort körning för att snabbt testa en förändring. Ger ofta ett godkänt, men inte optimalt, resultat.",
      },
      NORMAL: {
        label: "Normal (60 s)",
        description: "Standardvalet - en bra balans mellan resultatkvalitet och väntetid.",
      },
      THOROUGH: {
        label: "Grundlig (120 s)",
        description: "Längre körning för bästa möjliga resultat inför en slutgiltig plan.",
      },
      GREEDY: {
        label: "Greedy-baslinje",
        description:
          "Ingen riktig optimering - enklast möjliga tilldelning (sortering), som referens för hur mycket optimeringsmotorn faktiskt förbättrar resultatet. Körs direkt, på under en sekund.",
      },
      CUSTOM: {
        label: "Egen tid",
        description: "Ange själv exakt hur många sekunder optimeringen får köra (10–900).",
      },
    },
    weightsSummary: {
      heading: "Constraint-vikter",
      subheading: "Sammanfattning av de vikter som styr optimeringen för den här planen.",
      goToFieldsButton: "Redigera under Fält",
      loadFailed: "Kunde inte hämta constraint-vikter",
      table: {
        label: "Constraint",
        hardOrSoft: "Hård/Mjuk",
        weight: "Vikt",
        enabled: "Aktiverad",
      },
    },
    optimizeOnly: {
      heading: "Optimera endast",
      tooltip: "Att bara optimera vissa delar kräver att planen redan har körts (lösts) en gång tidigare.",
      players: "Spelare",
      schedule: "Tider & banor",
      coaches: "Tränare",
    },
    blocking: {
      heading: "Ta hänsyn till andra låsta planer:",
      tooltip:
        "Andra sparade/låsta planer i samma säsong kan dela personer, tränare och banor med den här planen (t.ex. Herr och Dam torsdag). Kryssa i vilka resurser som ska blockeras åt de andra planerna innan den här optimeringen körs.",
      blockCoaches: "Blockera tränare",
      blockPlayers: "Blockera spelare/personer",
      blockCourts: "Blockera banor/tider",
      conflictsAsWarnings: "Visa konflikter men tillåt ändå",
    },
    startButton: "Starta optimering",
    cancelButton: "Avbryt optimering",
    startFailed: "Kunde inte starta optimeringen",
    cancelFailed: "Kunde inte avbryta optimeringen",
    progress: {
      heading: "Optimering pågår…",
      waitingForFirstResult: "Beräknar första resultatet…",
      improvementCount: (n: number) => `${n} förbättringar hittade`,
      elapsed: (elapsedS: number, limitS: number) => `${elapsedS} s av ${limitS} s`,
    },
    // v0.3.0 WI-2 (user feedback: "jag skulle vilja se det live... en nice marknadsföringsgrej") -
    // the live "watch it optimize" grid shown while a non-GREEDY solve runs (LiveSolveView.tsx).
    live: {
      heading: "Live-vy",
      improvementNumber: (n: number) => `Förbättring #${n}`,
      waitlistLabel: (n: number) => `Kölista: ${n}`,
      emptyGroup: "Tom",
      emptyWaitlist: "Ingen på kölista just nu.",
      finishedHint: "Slutresultat från körningen.",
      goToResultsLink: "Visa i Resultat",
    },
    lastRun: {
      heading: "Senaste körning",
      empty: "Ingen optimering har körts ännu för den här planen.",
      feasibleTitle: "Genomförbar lösning",
      infeasibleTitle: (n: number) => `${n} hårda brott kvarstår`,
      // WI-C ("re-run doesn't feel like it re-runs" user feedback v0.4 #4, root cause B/C).
      unchangedNote:
        "Resultatet är identiskt med föregående körning – optimeringen hittade ingen bättre lösning med nuvarande inställningar och data. Prova längre optimeringstid, eller kryssa i 'Börja om från grunden' under Avancerat.",
      cancelledBadge: "Avbruten",
      failedBadge: "Misslyckades",
      duration: (s: number) => `Tog ${s} s`,
      when: (text: string) => `Kördes ${text}`,
    },
    analysis: {
      heading: "Analys",
      loadFailed: "Kunde inte hämta planeringsanalysen",
      constraintSummariesHeading: "Constraints",
      table: {
        label: "Constraint",
        level: "Nivå",
        weight: "Vikt",
        score: "Poäng",
        matches: "Antal",
      },
      hardViolationsHeading: "Hårda brott",
      noHardViolations: "Inga hårda brott.",
      waitlistHeading: "Kölista",
      noWaitlist: "Ingen på kölista.",
      problematicGroupsHeading: "Mest problematiska grupper",
      noProblematicGroups: "Inga problematiska grupper.",
      manualReviewHeading: "Behöver manuell granskning",
      noManualReview: "Ingen flaggad för manuell granskning.",
    },
  },
  results: {
    heading: "Resultat",
    loadFailed: "Kunde inte hämta resultat",
    empty: "Ingen optimering har körts ännu. Gå till fliken Optimering för att starta en körning.",
    explainBasedOn: (time: string) => `Förklaringar baserade på senaste körning: ${time}`,
    noRunTooltip: "Ingen körning har gjorts ännu. Gå till fliken Optimering för att starta en optimering.",
    viewToggle: {
      cards: "Kort",
      schedule: "Schema",
    },
    groupCard: {
      // User feedback v0.4.1: the old "n/target/max spelare" chip was not self-explanatory - spell
      // out what each number means. Handles all four presence combinations of target/max.
      playersCount: (n: number, target: number | null, max: number | null) => {
        if (target != null && max != null) {
          return `${n} spelare (mål ${target}, max ${max})`;
        }
        if (target != null) {
          return `${n} spelare (mål ${target})`;
        }
        if (max != null) {
          return `${n} spelare (max ${max})`;
        }
        return `${n} spelare`;
      },
      levelMean: "Nivåsnitt",
      levelSpread: "Nivåspridning",
      noLevelData: "Nivådata saknas",
      noBlock: "Ingen tid/bana tilldelad",
      noCoach: "Ingen tränare tilldelad",
      blockLocked: "Tid/bana låst",
      blockLockTooltip: "Lås gruppens tid/bana",
      blockUnlockTooltip: "Lås upp gruppens tid/bana",
      coachLocked: "Tränare låst",
      coachLockTooltip: "Lås tränaren till gruppen",
      coachUnlockTooltip: "Lås upp tränaren",
      lockBlockFailed: "Kunde inte låsa tid/bana",
      lockCoachFailed: "Kunde inte låsa tränaren",
      membersHeading: "Spelare",
      lockButton: "Lås",
      unlockButton: "Lås upp",
      lockFailed: "Kunde inte låsa spelaren",
      unlockFailed: "Kunde inte låsa upp spelaren",
      explainButton: "Förklara",
      testMoveButton: "Testa flytt",
      explainGroupButton: "Förklara grupp",
      sourceBadge: {
        imported: "Importerad",
        manual: "Manuell",
        solver: "Optimerad",
        locked: "Låst",
      },
    },
    waitlist: {
      heading: "Oplacerad / Kölista",
      empty: "Alla spelare är placerade i en grupp.",
      priorityLabel: (value: string) => `Prioritet: ${value}`,
      explainButton: "Förklara",
    },
    schedule: {
      timeColumn: "Tid",
      free: "Ledig",
      inactive: "Inaktiverad",
      conflictsHeading: "Konflikter",
      noConflicts: "Inga konflikter hittade i säsongen.",
      loadFailed: "Kunde inte hämta konflikter",
      conflictBadge: "Konflikt",
      conflictType: {
        PERSON_DOUBLE_BOOKED: "Person dubbelbokad",
        COACH_PLAYS_WHILE_COACHING: "Tränare spelar samtidigt som den tränar",
        COURT_DOUBLE_BOOKED: "Bana dubbelbokad",
      },
    },
    explain: {
      title: (name: string) => `Varför hamnade ${name} här?`,
      loadFailed: "Kunde inte hämta förklaringen",
      // Truthfulness nuance (M7 review): a stale explanation is RE-COMPUTED from the plan's CURRENT
      // state, not replayed from the old run's snapshot - so the banner must not claim the content
      // is "based on" the old run. It reflects the current state; what's inaktuell is the RUN.
      staleBanner:
        "Planen har ändrats efter denna körning — förklaringen speglar nuvarande läge. Kör om optimeringen för en förklaring som matchar en aktuell körning.",
      selectedGroupHeading: "Vald grupp",
      positiveHeading: "Positiva faktorer",
      negativeHeading: "Negativa faktorer",
      brokenWishesHeading: "Brutna önskemål",
      noPositive: "Inga positiva faktorer registrerade.",
      noNegative: "Inga negativa faktorer.",
      noBrokenWishes: "Inga brutna önskemål.",
      brokenWishWith: (person: string) => `Med ${person}`,
      // v0.3.0 WI-5 (user feedback: "Förklaringen ... bör även visa om det beror på att en annan
      // spelare påverkas av en tränare") - the second-order "via a coach" section. indirectFactors[]
      // entries carry their own finished Swedish messageSv from the backend (same pattern as every
      // other factor list); only the section's own heading/empty-state text lives here.
      indirectFactorsHeading: "Indirekt påverkan",
      noIndirectFactors: "Ingen indirekt påverkan via tränare hittad.",
      appliedWeightsHeading: "Tillämpade vikter",
      appliedWeightsTable: {
        label: "Constraint",
        level: "Nivå",
        weight: "Vikt",
      },
      alternativesHeading: "Alternativ som övervägdes",
      noAlternatives: "Inga alternativa grupper att visa.",
      newlyBrokenHeading: "Nya brott",
      newlyFixedHeading: "Löser",
      whyNotHeading: "Varför inte …?",
      whyNotPlaceholder: "Välj en grupp",
      whyNotButton: "Visa",
      whyNotFailed: "Kunde inte hämta jämförelsen",
      verdict: {
        wouldBreakHard: "Skulle bryta hård regel",
        better: "Bättre",
        neutral: "Ingen påverkan",
        worse: "Sämre",
      },
      origin: {
        friendWish: "Kompisönskemål",
        // v0.3.0 WI-5: added ALONGSIDE friendWish (never instead of it) when the friend's own
        // presence in that candidate group is itself explained by their MUST/WANT coach wish.
        friendViaCoach: "Kompis knuten via tränare",
        coachWish: "Tränarönskemål",
        previousGroup: "Tidigare grupp",
        topScore: "Näst bäst",
      },
      waitlistedFriendLink: (name: string) => `Visa ${name}s förklaring (kölista)`,
      // v0.3.0 WI-5: the indirect-factors section links to the (placed, not waitlisted) wish
      // partner's own explanation - same navigation affordance as waitlistedFriendLink above, but
      // without the "(kölista)" suffix since this person IS placed.
      friendExplanationLink: (name: string) => `Visa ${name}s förklaring`,
      waitlist: {
        blockersHeading: "Per grupp",
        qualityWarningTitle: "Förbättring möjlig",
      },
    },
    whatIf: {
      title: (name: string) => `Testa att flytta ${name}`,
      targetGroupLabel: "Ny grupp",
      targetGroupPlaceholder: "Välj grupp",
      waitlistOption: "Kölista",
      consequenceHeading: "Konsekvens av flytt",
      scoreDeltaLabel: "Total score",
      wouldBreakHardAlert: "Flytten skulle bryta ett hårt krav.",
      groupSizeChangesHeading: "Ändrad gruppstorlek",
      groupSizeChangeLine: (name: string, from: number, to: number, max: number | null) =>
        max != null ? `${name}: ${from} → ${to} (max ${max})` : `${name}: ${from} → ${to}`,
      levelSpreadChangesHeading: "Ändrad nivåspridning",
      levelSpreadChangeLine: (name: string, from: number, to: number) => `${name} — Spridning: ${from} → ${to}`,
      consequenceLoadFailed: "Kunde inte beräkna konsekvensen av flytten",
      actions: {
        keep: "Behåll nuvarande",
        moveAnyway: "Flytta ändå",
        lockAndResolve: "Lås & markera för omoptimering",
      },
      confirmBreakHard: {
        title: "Bekräfta flytt som bryter ett hårt krav",
        message: (name: string, groupName: string) =>
          `Att flytta ${name} till ${groupName} skulle bryta ett hårt krav (t.ex. maxstorlek). Vill du flytta ändå?`,
      },
      moveFailed: "Kunde inte flytta spelaren",
      moveSuccess: (name: string, groupName: string | null) =>
        groupName != null ? `${name} flyttades till ${groupName}.` : `${name} flyttades till kölistan.`,
      lockAndResolveSuccess: (name: string) =>
        `${name} flyttades och låstes. Kör om optimeringen för att uppdatera resten av planen.`,
    },
    groupExplain: {
      title: (name: string) => `Förklaring: ${name}`,
      loadFailed: "Kunde inte hämta gruppförklaringen",
      matchesHeading: "Vad som påverkade gruppens placering",
      noMatches: "Inga registrerade faktorer.",
      warningsHeading: "Varningar",
      noWarnings: "Inga varningar.",
      membersWithBrokenWishesHeading: "Spelare med brutna önskemål",
      noBrokenWishMembers: "Inga spelare med brutna önskemål.",
    },
    // WI-D "Förbättringsförslag" (user feedback v0.4 #2): post-solve, low-hanging-fruit suggestions
    // for SMALL data changes the council could make to unlock a bigger improvement (one more seat in
    // a group, one coach's availability). Rendered by ImprovementSuggestions.tsx above the group
    // cards grid on the Resultat tab.
    suggestions: {
      heading: "Förbättringsförslag",
      subtitle: "Små ändringar i data som styrelsen kan göra för att förbättra resultatet.",
      loadFailed: "Kunde inte hämta förbättringsförslag",
      empty: "Inga uppenbara förbättringar hittades – kölistan och tränartäckningen ser bra ut.",
      // Same truthfulness nuance as results.explain.staleBanner: the list is RE-COMPUTED from the
      // plan's current state, not replayed from the old run - what's outdated is the RUN.
      staleBanner:
        "Planen har ändrats efter denna körning — förslagen speglar nuvarande läge. Kör om optimeringen för förslag som matchar en aktuell körning.",
      // "punkt", not "förslag" - the backend cap is applied before the frontend splits suggestions
      // from limitations, so the omitted items can be either.
      omittedCount: (n: number) => (n === 1 ? "1 ytterligare punkt visas inte." : `${n} ytterligare punkter visas inte.`),
      showButton: "Visa förslag",
      hideButton: "Dölj förslag",
      // User feedback v0.4.1: GROUP_MAX/GROUP_MAX_WISH describe a fixed limit (court capacity/plan
      // max sizes) the council can't actually change - rendered in their own subsection so they read
      // as an explanation of the result, not an actionable to-do alongside the real suggestions.
      limitationsHeading: "Begränsningar som påverkar resultatet",
      limitationsSubtitle: "Fasta gränser (t.ex. planens maxstorlekar) som förklarar resultatet – inget att ändra, bara att känna till.",
    },
    // "Are these groups good?" at-a-glance strip (user feedback v0.4 #5) - ResultsSummary.tsx (the
    // Resultatvy strip above ImprovementSuggestions) and the per-GroupCard status dot/border/chips,
    // both driven by groupQuality.ts's pure computeGroupQuality.
    quality: {
      regionLabel: "Kvalitetsöversikt",
      noSignals: "Inga anmärkningar.",
      hardViolations: {
        ok: "Inga hårda brott",
        bad: (n: number) => pluralize(n, "hårt brott", "hårda brott"),
      },
      waitlist: {
        ok: "Alla placerade",
        bad: (n: number) => `${n} på kölista`,
      },
      coachCoverage: (covered: number, total: number) =>
        `${covered} av ${pluralize(total, "grupp", "grupper")} har tränare`,
      softScoreLabel: "Mjukt avdrag",
      signals: {
        coachMissing: "Ingen tränare tilldelad",
        coachBelowRequired: (n: number, required: number) => `${n} av ${required} tränare tilldelade`,
        coachInPlace: "Tränare: på plats",
        sizeBelowMin: (n: number, min: number) => `Färre än minsta storlek (${n} < ${min})`,
        sizeAboveMax: (n: number, max: number) => `Över maxstorlek (${n} > ${max})`,
        sizeBelowTarget: (n: number, target: number) => `Under målstorleken (${n} av ${target})`,
        sizeAboveTarget: (n: number, target: number) => `Över målstorleken (${n} av ${target})`,
        sizeAtTarget: "Storlek på mål",
        levelOutsideBand: (mean: number, min: string, max: string) =>
          `Nivåsnittet (${mean}) ligger utanför gruppens band (${min}–${max})`,
        levelInsideBand: "Nivåsnitt inom bandet",
        topPenalty: "Störst poängavdrag i planen",
      },
      chips: {
        coachLabel: (coachCount: number, requiredCoachCount: number | null) =>
          requiredCoachCount != null && requiredCoachCount > 0
            ? `${coachCount} av ${requiredCoachCount} tränare`
            : `${coachCount} tränare`,
        levelLabel: (mean: number, spread: number) => `Nivå ${mean} (±${spread})`,
        bandSuffix: (min: string, max: string) => `band ${min}–${max}`,
      },
    },
  },
  savedPlans: {
    heading: "Sparade planer",
    description:
      "Spara ett ögonblick av den aktuella planen (grupper, spelare, tränare, tid/bana, constraint-vikter, låsningar och score). Varje sparning skapar en ny version - inget skrivs över.",
    loadFailed: "Kunde inte hämta sparade planer",
    empty: "Inga sparade planer ännu.",
    saveForm: {
      nameLabel: "Namn",
      namePlaceholder: "t.ex. Herr vecka 12",
      submit: "Spara plan",
      saveFailed: "Kunde inte spara planen",
      saveSuccess: (name: string) => `"${name}" sparades.`,
    },
    columns: {
      name: "Namn",
      status: "Status",
      score: "Poäng",
      when: "Sparad",
      version: "Version",
    },
    noScore: "—",
    status: {
      draft: "Utkast",
      saved: "Sparad",
      locked: "Låst",
      published: "Publicerad",
      archived: "Arkiverad",
    },
    /** Label per TARGET status, i.e. what the action button that transitions TO that status is
     *  called - "Lås" is the button that moves a plan from saved to locked, etc. */
    actions: {
      saved: "Spara",
      locked: "Lås",
      published: "Publicera",
      archived: "Arkivera",
    },
    lockTooltip: "Låser planen och blockerar personer/tränare/banor för andra planers optimering.",
    transitionFailed: "Kunde inte ändra status på planen",
    deleteButton: "Ta bort",
    deleteModal: {
      title: "Ta bort sparad plan",
      message: (name: string) => `Är du säker på att du vill ta bort "${name}"? Detta går inte att ångra.`,
      confirm: "Ta bort",
      deleteFailed: "Kunde inte ta bort planen",
    },
  },
  export: {
    heading: "Export",
    emptyNoRun: "Ingen optimering har körts ännu. Gå till fliken Optimering för att kunna exportera resultatet.",
    formatHeading: "Format",
    format: {
      xlsx: "Excel (.xlsx)",
      csv: "CSV (.csv)",
    },
    layoutHeading: "Layout",
    layout: {
      grouped: "Grupperad — som kansliets arbetsblad",
      flat: "Platt tabell",
    },
    layoutDisabledForCsvHint: "Grupperad layout kan bara exporteras som Excel — välj Platt tabell för CSV.",
    includeCommentsLabel: "Inkludera kommentarer i export",
    includeCommentsWarning: "Kommentarer är känsliga — dela inte filen vidare.",
    exportButton: "Exportera",
    exportFailed: "Kunde inte exportera planen",
    exportSuccess: "Exporten laddades ner.",
    anonymized: {
      heading: "Anonymiserat testdata",
      description:
        "Tar bort namn, e-post, telefon, kommentarer och andra personliga identifierare. Behåller ranking, nivå, constraints, gruppstorlek, tider och banor. Används för felsökning och open source-utveckling.",
      exportButton: "Exportera anonymiserat",
      exportFailed: "Kunde inte exportera anonymiserat testdata",
      exportSuccess: "Det anonymiserade testdatat laddades ner.",
    },
  },
  tutorial: {
    headerButtonTooltip: "Öppna guiden",
    modalTitle: "Kom igång-guiden",
    howToHeading: "Gör så här",
    stepLabel: (current: number, total: number) => `Steg ${current} av ${total}`,
    prevButton: "Föregående",
    nextButton: "Nästa",
    doneButton: "Klar",
    goThereButton: "Ta mig dit",
    goThereDisabledTooltip: "Öppna eller skapa en aktivitetsplan för att kunna gå direkt dit.",
    bannerTitle: "Ny här?",
    bannerBody:
      "Öppna kom-igång-guiden för en snabb rundtur genom hela flödet – från säsong till export.",
    bannerOpenButton: "Öppna kom-igång-guiden",
    steps: [
      {
        title: "Säsong & plan",
        body: "Allt börjar med en säsong – till exempel \"VT27\" – och en eller flera aktivitetsplaner i den, en per grupp av tränande (till exempel Herr, Dam eller Ungdom). Aktivitetsplanen är arbetsytan där du gör allting: importerar anmälningar, sätter tider och banor, och kör optimeringen.",
        bullets: [
          "Klicka på \"Skapa ny säsong\" på Startsidan.",
          "Öppna säsongen och skapa en aktivitetsplan per grupp, t.ex. Herr, Dam eller Ungdom.",
          "Du hittar alltid tillbaka via brödsmulorna högst upp på sidan.",
        ],
      },
      {
        title: "Importera anmälningar",
        body: "Ladda upp anmälningsfilen (Excel eller CSV) i importguiden. Du väljer själv vilket blad och vilken rad som är rubrikrad, mappar varje kolumn till rätt fält, och får en tydlig sammanställning innan något sparas i planen.",
        bullets: [
          "Gå till fliken Deltagare och klicka \"Importera\".",
          "Välj fil, granska bladet och mappa kolumnerna till rätt fält.",
          "Validera raderna – du ser exakt vad som blir OK, varning eller överhoppat innan du bekräftar.",
        ],
      },
      {
        title: "Strukturera fält",
        body: "Kommentarer från anmälningsformuläret (t.ex. \"vill helst spela med Anna\") visas bara som text att läsa – de tolkas ALDRIG automatiskt av systemet. Din uppgift är att läsa kommentaren och själv fylla i motsvarande strukturerade fält, som kompisönskemål eller manuell nivåscore, så att optimeringen faktiskt kan ta hänsyn till det.",
        bullets: [
          "Öppna en deltagare i Deltagarvyn för att se kommentaren till vänster.",
          "Fyll i strukturerade fält till höger utifrån vad kommentaren säger.",
          "Bara det som står i ett strukturerat fält påverkar optimeringen – fri text gör det aldrig.",
        ],
      },
      {
        title: "Tider & banor",
        body: "Under Resurser lägger du till träningstider (dag samt start- och sluttid) och anger antal banor per tid. Varje kombination av tid och bana blir en plats en grupp kan tilldelas. Behöver en enstaka bana stängas av går det att inaktivera den utan att ta bort den.",
        bullets: [
          "Klicka \"Ny tid\" och ange dag, starttid och sluttid.",
          "Ange antal banor för tiden – banorna skapas automatiskt.",
          "Inaktivera en enskild bana vid behov, t.ex. om den är bokad av någon annan just den kvällen.",
        ],
      },
      {
        title: "Tränare (valfritt!)",
        body: "Att lägga in tränare är helt valfritt – du kan optimera och få färdiga grupper helt utan dem, och resultatet visar då en vänlig notis om att grupperna fördelades utan tränartilldelning. Men lägger du in tränare kan optimeringen dessutom ta hänsyn till vilken nivå tränaren klarar, hur många grupper de orkar per dag och vecka, och när de faktiskt kan eller helst vill träna.",
        bullets: [
          "Klicka \"Ny tränare\" och länka en befintlig deltagare eller skapa en ny person.",
          "Ange vilken nivå tränaren kan träna och hur många grupper per dag/vecka.",
          "Fyll i tillgänglighet per träningstid: Otillgänglig, Tillgänglig eller Föredrar. Tider som lämnas som Okänd räknas som \"föredrar inte\" (liten minuspoäng), så det lönar sig att fylla i.",
        ],
      },
      {
        title: "Kapacitetskoll",
        body: "Innan du optimerar är det värt att titta på Kapacitet: hur många deltagare har du, hur många aktiva banor och tider finns, och räcker tränarna till? Vyn varnar dig i förväg om risk för kölista eller tränarbrist, så du hinner lägga till fler tider eller tränare innan du kör optimeringen.",
        bullets: [
          "Se antal deltagare, aktiva block och beräknad kapacitet på ett ställe.",
          "Följ varningarna om risk för kölista eller tränarbrist per träningstid.",
          "Justera tider, banor eller tränare om något ser snålt tilltaget ut.",
        ],
      },
      {
        title: "Optimera",
        body: "Optimeringen fördelar deltagare, tider/banor och tränare enligt DINA regler – de hårda kraven (t.ex. maxstorlek) som aldrig får brytas, och de mjuka önskemålen (t.ex. jämn nivåspridning eller kompisönskemål) som vägs mot varandra utifrån de vikter du satt under Fält. Resultatet är helt enkelt det bästa systemet kunde hitta enligt de regler och vikter du själv har angett – inte någon extern \"rätt\" lösning.",
        bullets: [
          "Välj en profil: Snabb för en första koll, Normal för de flesta gånger, Grundlig inför en slutgiltig plan.",
          "Kryssa i om du bara vill optimera spelare, tider/banor eller tränare den här gången.",
          "Klicka \"Starta optimering\" och följ förbättringarna live.",
        ],
      },
      {
        title: "Förstå resultatet",
        body: "På fliken Resultat ser du varje grupp med spelare, tränare, tid/bana och nivåstatistik – och en kölista för dem som inte fick plats. Klicka \"Förklara\" på en spelare för att se exakt varför den hamnade där, med positiva och negativa faktorer i klartext. Nyfiken på ett alternativ? \"Testa flytt\" visar konsekvensen av att flytta någon innan du bestämmer dig – helt riskfritt.",
        bullets: [
          "Byt mellan Kort- och Schemavy för att se resultatet på olika sätt.",
          "Klicka \"Förklara\" på valfri spelare eller grupp för en tydlig motivering.",
          "Prova \"Testa flytt\" för att se konsekvensen av en flytt innan du genomför den.",
        ],
      },
      {
        title: "Lås & kör om",
        body: "Nöjd med en spelare, tränare eller grupps tid/bana? Lås den så rör optimeringen den aldrig igen – resten av planen kan ändå förbättras vid en ny körning. Väljer du \"Lås & markera för omoptimering\" i Testa flytt-dialogen låses flytten automatiskt åt dig, och du kör sedan om optimeringen för att uppdatera resten.",
        bullets: [
          "Klicka \"Lås\" på en spelare, tränare eller en grupps tid/bana i Resultat.",
          "Gå till Optimering och starta en ny körning – låsta delar rör sig inte.",
          "Bra att kombinera med Testa flytt när du gör en manuell justering.",
        ],
      },
      {
        title: "Spara & exportera",
        body: "Spara ett resultat under fliken Planer för att skapa en historik – varje sparning är en egen version, inget skrivs över. Sedan exporterar du planen som Excel eller CSV under Export. Kommentarer följer ALDRIG med i exporten som standard – du måste aktivt kryssa i det, och får då en tydlig varning om att filen innehåller känslig text.",
        bullets: [
          "Spara en version under fliken Planer när du är nöjd.",
          "Gå till Export, välj format och layout, och ladda ner filen.",
          "Låt \"Inkludera kommentarer\" vara avbockad om du inte medvetet behöver dem.",
        ],
      },
    ],
  },
  playerSearch: {
    actionIconTooltip: "Sök deltagare (Ctrl/Cmd+F)",
    placeholder: "Sök deltagare…",
    nothingFound: "Inga deltagare hittades.",
    levelBadge: (level: number) => `Nivå ${level}`,
    resultsWaitlistBadge: "Kölista",
  },
} as const;

export type Sv = typeof sv;
