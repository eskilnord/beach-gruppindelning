package se.klubb.groupplanner.importer;

import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import se.klubb.groupplanner.importer.match.JaroWinkler;

/**
 * Suggests a {@link MappingTargetKind} for a column from its header text, via a Swedish/English
 * synonym table with a fuzzy-match fallback (spec §8.4 "Mappa kolumner" / M3 brief: "a synonym
 * table, not hardcoded to one file" - CLAUDE.md forbids hardcoding to the real file's exact column
 * names, so this works from generic domain vocabulary instead).
 *
 * <p>Never suggests {@link MappingTargetKind#CUSTOM_FIELD} (that requires knowing which custom
 * fields exist for the plan, which is a per-request concern - see {@code ImportController}) or
 * {@link MappingTargetKind#IS_COACH}/{@link MappingTargetKind#COACH_NAME} beyond the synonym table
 * below. An empty result means "no confident suggestion" - the wizard UI defaults such columns to
 * {@link MappingTargetKind#IGNORE} and lets the user pick manually.
 *
 * <p>Some headers are deliberately suggested as {@link MappingTargetKind#IGNORE} with an explicit
 * reason (personnummer, informational columns, time-wish columns that cannot be committed as
 * structured time relations) so one-click import can show why they were skipped rather than
 * silently dropping them.
 */
public final class ColumnMappingSuggester {

    private static final double FUZZY_THRESHOLD = 0.85;

    // Normalized (lowercase, punctuation-collapsed) synonym -> target. Order doesn't matter for
    // exact lookups; for the fuzzy fallback every entry is considered and the best score wins.
    private static final Map<String, MappingTargetKind> SYNONYMS = buildSynonyms();

    /** Exact-match IGNORE headers with a fixed Swedish reason (privacy / informational / unimportable). */
    private static final Map<String, String> EXPLICIT_IGNORE_REASONS = buildExplicitIgnoreReasons();

    private ColumnMappingSuggester() {
    }

    public static Optional<MappingTargetKind> suggest(String headerText) {
        return suggestDetailed(headerText).map(ColumnSuggestion::kind);
    }

    /**
     * Like {@link #suggest}, but also returns a Swedish reason and a confidence score for the
     * one-click review screen.
     */
    public static Optional<ColumnSuggestion> suggestDetailed(String headerText) {
        if (headerText == null || headerText.isBlank()) {
            return Optional.empty();
        }
        String normalized = normalize(headerText);

        String ignoreReason = EXPLICIT_IGNORE_REASONS.get(normalized);
        if (ignoreReason != null) {
            return Optional.of(ColumnSuggestion.of(MappingTargetKind.IGNORE, ignoreReason, 1.0));
        }

        MappingTargetKind exact = SYNONYMS.get(normalized);
        if (exact != null) {
            return Optional.of(ColumnSuggestion.of(
                    exact, "Matchade rubriken \"" + headerText.strip() + "\"", 1.0));
        }

        String bestSynonym = null;
        double bestScore = 0.0;
        for (String synonym : SYNONYMS.keySet()) {
            double score = JaroWinkler.similarity(normalized, synonym);
            if (score > bestScore) {
                bestScore = score;
                bestSynonym = synonym;
            }
        }
        if (bestSynonym != null && bestScore >= FUZZY_THRESHOLD) {
            MappingTargetKind kind = SYNONYMS.get(bestSynonym);
            return Optional.of(ColumnSuggestion.of(
                    kind,
                    "Liknande rubrik \"" + bestSynonym + "\" (träffsäkerhet "
                            + Math.round(bestScore * 100) + "%)",
                    bestScore));
        }
        return Optional.empty();
    }

    static String normalize(String text) {
        String replaced = text.strip().toLowerCase(Locale.ROOT)
                .replace('-', ' ')
                .replace('_', ' ')
                .replace('.', ' ');
        return replaced.replaceAll("\\s+", " ").strip();
    }

    private static Map<String, MappingTargetKind> buildSynonyms() {
        Map<String, MappingTargetKind> map = new LinkedHashMap<>();
        putAll(map, MappingTargetKind.FIRST_NAME,
                "förnamn", "fornamn", "first name", "firstname", "fname");
        putAll(map, MappingTargetKind.LAST_NAME,
                "efternamn", "last name", "lastname", "surname", "lname", "sur name");
        putAll(map, MappingTargetKind.DISPLAY_NAME,
                "namn", "name", "fullständigt namn", "fullstandigt namn", "full name", "fullname");
        putAll(map, MappingTargetKind.EMAIL,
                "epost", "e post", "e postadress", "email", "e mail", "mejl", "mail", "mailadress");
        putAll(map, MappingTargetKind.PHONE,
                "mobil", "telefon", "tel", "phone", "mobile", "mobilnummer", "telefonnummer", "mobiltelefon");
        putAll(map, MappingTargetKind.EXTERNAL_ID,
                "medlemsid", "medlemsnummer", "externalid", "external id", "memberid", "member id", "medlems id");
        putAll(map, MappingTargetKind.RANKING_POINTS,
                "rank", "ranking", "rankingpoints", "ranking points", "poäng", "poang", "seriespelsranking");
        putAll(map, MappingTargetKind.PREVIOUS_GROUP_NAME,
                "tidigare grupp", "föregående grupp", "foregaende grupp", "previous group",
                "group last term", "förra gruppen", "forra gruppen", "gruppföregåendetermin", "gruppforegaendetermin");
        putAll(map, MappingTargetKind.PREVIOUS_GROUP_LEVEL,
                "tidigare nivå", "tidigare niva", "previous level", "previousgrouplevel", "förra nivån", "forra nivan");
        putAll(map, MappingTargetKind.MANUAL_LEVEL_SCORE,
                "nivåscore", "nivascore", "manual level", "manuell nivå", "manuell niva", "manuallevelscore", "nivåbedömning");
        putAll(map, MappingTargetKind.COMMENT,
                "kommentar", "comment", "anmälningskommentar", "anmalningskommentar", "comments", "kommentar från anmälan");
        putAll(map, MappingTargetKind.INTERNAL_NOTE,
                "intern kommentar", "internkommentar", "internal note", "internalnote", "anteckning", "intern anteckning");
        putAll(map, MappingTargetKind.COACH_NAME,
                "tränare", "tranare", "coach", "önskad tränare", "onskad tranare", "wants coach", "coach wish", "coach namn");
        putAll(map, MappingTargetKind.IS_COACH,
                "är tränare", "ar tranare", "is coach", "coach flag", "ledare", "tränarflagga", "tranarflagga");
        return map;
    }

    private static Map<String, String> buildExplicitIgnoreReasons() {
        Map<String, String> map = new LinkedHashMap<>();
        putIgnore(map, "Importeras aldrig (integritetsskydd)",
                "personnummer", "person nr", "personnr", "pnr", "ssn", "national id");
        putIgnore(map, "Informationskolumn – importeras inte",
                "rankinfo", "rank info", "rankinginfo", "ranking info");
        putIgnore(map, "Informationskolumn – importeras inte",
                "anmäld aktivitet", "anmald aktivitet", "anmäldaktivitet", "anmaldaktivitet", "registered activity");
        putIgnore(map, "Informationskolumn – importeras inte",
                "varningar", "varning", "warnings", "warning");
        // timeRelation custom fields cannot be committed from free-text Excel cells
        // (ImportCommitService.TIME_RELATION_IMPORT_WARNING) — surface that as an explicit ignore.
        putIgnore(map, "Tidsönskemål anges under Deltagare efter importen",
                "tid", "tidönskemål", "tidonskemal", "time wish", "önskad tid", "onskad tid");
        return map;
    }

    private static void putAll(Map<String, MappingTargetKind> map, MappingTargetKind kind, String... synonyms) {
        for (String synonym : synonyms) {
            map.put(normalize(synonym), kind);
        }
    }

    private static void putIgnore(Map<String, String> map, String reason, String... synonyms) {
        for (String synonym : synonyms) {
            map.put(normalize(synonym), reason);
        }
    }
}
