package se.klubb.groupplanner.groups;

/**
 * A normalized interpretation of one participant's free-text {@code previous_group_name}, produced
 * by {@link PreviousGroupNormalizer#parse(String)}. Pure data — no repo/domain dependency, so this
 * module can be unit tested and later wired into the importer/solver without coupling either
 * direction.
 *
 * @param rawDisplay the chosen (newest) pipe-separated segment, trimmed, exactly as written
 *     (including any term suffix) — what the UI shows the user.
 * @param canonicalName lowercased, term-stripped, punctuation-normalized, whitespace-collapsed form
 *     of the chosen segment (e.g. {@code "torsdag herr 3"}) — used for exact-match comparisons
 *     against generated group names.
 * @param categoryPart {@code canonicalName} with its trailing ordinal removed, when that ordinal was
 *     found via the trailing-integer rule (rule (a) in {@link PreviousGroupNormalizer}); {@code null}
 *     when no such ordinal was stripped or the remainder would be empty.
 * @param groupOrder the numeric group order derived from the chosen segment, or {@code null} when
 *     none could be derived.
 * @param termLabel the term annotation text as written (e.g. {@code "Vårtermin 2025"}, {@code
 *     "VT25"}), or {@code null} when the chosen segment carried no recognizable term suffix.
 * @param termKey a sortable/comparable encoding of {@code termLabel}, {@code year * 2 + (1 if
 *     hösttermin/HT else 0)} (e.g. VT2025 -&gt; 4050, HT2024 -&gt; 4049), or {@code null} when {@code
 *     termLabel} is {@code null}.
 */
public record PreviousGroupRef(
        String rawDisplay,
        String canonicalName,
        String categoryPart,
        Integer groupOrder,
        String termLabel,
        Integer termKey) {
}
