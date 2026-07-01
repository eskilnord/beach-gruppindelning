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
    importTooltip: "Kommer i M3",
    openButton: "Öppna",
    loadFailed: "Kunde inte hämta säsonger",
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
    },
    participantsPlaceholder: "—",
    loadFailed: "Kunde inte hämta säsongen",
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
  },
} as const;

export type Sv = typeof sv;
