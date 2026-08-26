package se.klubb.groupplanner.importer;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import se.klubb.groupplanner.groups.PreviousGroupNormalizer;
import se.klubb.groupplanner.groups.PreviousGroupRef;
import se.klubb.groupplanner.importer.parse.ParsedSheet;

/**
 * Single source of truth for the "which column is the previous-group source" decision (B5): a file
 * can carry BOTH a real 'Tidigare grupp' column AND the app's own block structure (the {@code
 * ColumnMapping#BLOCK_GROUP_COLUMN_INDEX} synthetic column, {@link BlockStructureDetector}) - the
 * block structure IS the file's own current grouping (newest), while a real column is typically
 * older history. Extracted from {@code ImportAnalysisService}/{@code ImportController} (both used
 * to duplicate this rule, always preferring the real column, which is wrong for the app's own
 * grouped export - see B5 spec) so both call sites now delegate here.
 *
 * <p><b>Deterministic ladder</b> (confidence is always {@code 1.0} - every branch is a confident
 * decision, never a guess):
 * <ol>
 *   <li>A saved import-template mapping pins a column to {@code previousGroupName} -&gt; that column
 *       wins unconditionally (this is the unchanged pre-B5 behavior).
 *   <li>Only one candidate exists (no real column suggests {@code previousGroupName}, or no block
 *       structure was detected) -&gt; it wins by default.
 *   <li>Both candidates exist AND BOTH carry a parseable term: compare the maximum {@link
 *       PreviousGroupRef#termKey()} found across up to {@link #SAMPLE_LIMIT} non-blank sample values
 *       from each. The strictly higher score wins - it carries demonstrably newer information. (B5
 *       review fix: {@code GroupedXlsxWriter} NEVER labels a block heading with a term - block labels
 *       are bare group names - while a real 'Tidigare grupp' column routinely does, so comparing
 *       "highest term key" whenever EITHER side has one used to always favor a term-bearing real
 *       column over a term-less block, even though the term-less block is the file's own CURRENT
 *       grouping and the real column is stale history. Requiring both sides to actually carry a term
 *       before this rule may even apply closes that hole; an asymmetric case (only one side has a
 *       term at all, or neither does) falls through to rule 4/5 below instead.)
 *   <li>Not applicable (asymmetric or no term evidence, or a genuine tie in step 3) -&gt; the SYNTHETIC
 *       block column wins by default: the file's own current grouping is inherently the newest
 *       information, a real 'Tidigare grupp' column is typically stale history copied from a previous
 *       export.
 *   <li>Exception to 4 (a majority-null-ordinal guard): if fewer than half of the block column's
 *       sampled labels yield a derivable {@link PreviousGroupRef#groupOrder()} while at least half of
 *       the real column's samples do, the real column wins instead - this guards against block
 *       labels that are not group-level names at all (e.g. plain colors or coach names used as the
 *       block heading), where the real column is clearly the more reliable source despite step 3 not
 *       applying (or tying).
 * </ol>
 */
public final class PreviousGroupColumnChooser {

    /** Sample-value cap per candidate (spec: "up to 10 non-blank sample values"). */
    private static final int SAMPLE_LIMIT = 10;

    /** Sentinel {@code termKey} for a candidate with no parseable term anywhere in its samples. */
    private static final int NO_TERM = -1;

    private PreviousGroupColumnChooser() {
    }

    /** One candidate column's identity + up to {@link #SAMPLE_LIMIT} non-blank sample values (more
     *  may be passed in; only the first {@link #SAMPLE_LIMIT} are examined). {@code columnIndex} is
     *  either a real 0-based sheet column, or {@link ColumnMapping#BLOCK_GROUP_COLUMN_INDEX} for the
     *  synthetic block-group column. */
    public record Candidate(int columnIndex, String headerLabel, List<String> sampleValues) {
    }

    /**
     * @param chosenColumnIndex the winning candidate's column index.
     * @param chosenReasonSv plain-Swedish reason for the winning choice.
     * @param confidence always {@code 1.0} (see class javadoc).
     * @param loserColumnIndex the other candidate's column index, or {@code null} when only one
     *     candidate was passed in (nothing to mark IGNORE).
     * @param loserReasonSv plain-Swedish reason the other candidate should be mapped IGNORE, or
     *     {@code null} together with {@code loserColumnIndex}.
     */
    public record Decision(
            int chosenColumnIndex, String chosenReasonSv, double confidence, Integer loserColumnIndex, String loserReasonSv) {
    }

    /**
     * @param realColumn the real (non-synthetic) column that suggests {@code previousGroupName}, or
     *     {@code null} if no such column exists on this sheet.
     * @param blockColumn the synthetic block-group column, or {@code null} if no block structure was
     *     detected on this sheet.
     * @param realPinnedByTemplate a saved import template pins {@code realColumn} to {@code
     *     previousGroupName} (ignored/unreachable when {@code realColumn} is {@code null}).
     * @param blockPinnedByTemplate a saved import template pins {@code blockColumn} to {@code
     *     previousGroupName} (ignored/unreachable when {@code blockColumn} is {@code null}).
     */
    public static Decision choose(
            Candidate realColumn, Candidate blockColumn, boolean realPinnedByTemplate, boolean blockPinnedByTemplate) {
        if (realColumn == null && blockColumn == null) {
            throw new IllegalArgumentException("choose() requires at least one candidate column");
        }

        // 1. Template pin wins unconditionally.
        if (realPinnedByTemplate && realColumn != null) {
            return new Decision(realColumn.columnIndex(), "Från sparad importmall", 1.0,
                    blockColumn != null ? blockColumn.columnIndex() : null,
                    blockColumn != null ? "Från sparad importmall (annan kolumn vald)" : null);
        }
        if (blockPinnedByTemplate && blockColumn != null) {
            return new Decision(blockColumn.columnIndex(), "Från sparad importmall", 1.0,
                    realColumn != null ? realColumn.columnIndex() : null,
                    realColumn != null ? "Från sparad importmall (annan kolumn vald)" : null);
        }

        // 2. Single candidate.
        if (realColumn == null) {
            return new Decision(blockColumn.columnIndex(),
                    "Härledd från filens gruppblock (" + "enda källan för tidigare grupp)", 1.0, null, null);
        }
        if (blockColumn == null) {
            return new Decision(realColumn.columnIndex(), "Enda kolumnen som kan vara tidigare grupp", 1.0, null, null);
        }

        // 3. Compare newest-term evidence - ONLY when BOTH candidates carry a parseable term (B5
        // review fix, see class javadoc). A term-less candidate never wins/loses via this rule.
        int realTermKey = maxTermKey(realColumn.sampleValues());
        int blockTermKey = maxTermKey(blockColumn.sampleValues());
        if (realTermKey != NO_TERM && blockTermKey != NO_TERM) {
            if (realTermKey > blockTermKey) {
                return new Decision(
                        realColumn.columnIndex(),
                        "Kolumnen \"" + realColumn.headerLabel() + "\" innehåller en nyare termin",
                        1.0,
                        blockColumn.columnIndex(),
                        "Tidigare grupp hämtas från kolumnen \"" + realColumn.headerLabel() + "\"");
            }
            if (blockTermKey > realTermKey) {
                return new Decision(
                        blockColumn.columnIndex(),
                        "Filens gruppblock innehåller en nyare termin",
                        1.0,
                        realColumn.columnIndex(),
                        "Tidigare grupp hämtas från filens gruppblock");
            }
        }

        // 5. Exception to the tie default: the real column wins when the block labels mostly fail to
        // yield a group order while the real column's samples mostly do.
        double realOrderRate = orderParseRate(realColumn.sampleValues());
        double blockOrderRate = orderParseRate(blockColumn.sampleValues());
        if (blockOrderRate < 0.5 && realOrderRate >= 0.5) {
            return new Decision(
                    realColumn.columnIndex(),
                    "Kolumnen \"" + realColumn.headerLabel() + "\" ger fler tolkningsbara gruppnivåer än filens gruppblock",
                    1.0,
                    blockColumn.columnIndex(),
                    "Tidigare grupp hämtas från kolumnen \"" + realColumn.headerLabel() + "\"");
        }

        // 4. Default: the synthetic block column wins.
        return new Decision(
                blockColumn.columnIndex(),
                "Filens egen gruppindelning används som tidigare grupp",
                1.0,
                realColumn.columnIndex(),
                "Tidigare grupp hämtas från filens gruppblock");
    }

    /** The highest {@code termKey} found across up to {@link #SAMPLE_LIMIT} non-blank samples, or
     *  {@link #NO_TERM} when none of them carry a parseable term. */
    private static int maxTermKey(List<String> sampleValues) {
        int best = NO_TERM;
        int examined = 0;
        for (String value : sampleValues) {
            if (value == null || value.isBlank()) {
                continue;
            }
            if (examined >= SAMPLE_LIMIT) {
                break;
            }
            examined++;
            PreviousGroupRef ref = PreviousGroupNormalizer.parse(value);
            if (ref != null && ref.termKey() != null && ref.termKey() > best) {
                best = ref.termKey();
            }
        }
        return best;
    }

    /** Fraction (0.0-1.0) of up to {@link #SAMPLE_LIMIT} non-blank samples that yield a derivable
     *  {@link PreviousGroupRef#groupOrder()}. {@code 0.0} when there are no non-blank samples at all. */
    private static double orderParseRate(List<String> sampleValues) {
        int examined = 0;
        int parsed = 0;
        for (String value : sampleValues) {
            if (value == null || value.isBlank()) {
                continue;
            }
            if (examined >= SAMPLE_LIMIT) {
                break;
            }
            examined++;
            PreviousGroupRef ref = PreviousGroupNormalizer.parse(value);
            if (ref != null && ref.groupOrder() != null) {
                parsed++;
            }
        }
        return examined == 0 ? 0.0 : (double) parsed / examined;
    }

    // -----------------------------------------------------------------------------------------
    // Shared sampling helpers (MINOR 8: single home - previously duplicated near-identically in
    // both ImportAnalysisService and ImportController).
    // -----------------------------------------------------------------------------------------

    /**
     * Up to {@link #SAMPLE_LIMIT} non-blank cell values for a real column, for use as a {@link
     * Candidate}'s {@code sampleValues} - a larger, non-deduplicated sample than a UI preview needs,
     * since {@link #choose} needs enough evidence to compare term recency / order-parse rate.
     *
     * <p>MINOR 6 fix: when {@code blockStructure} is present, only rows classified {@link
     * BlockStructureDetector.RowClass#PLAYER} are sampled - otherwise a repeated header row (whose
     * own "Tidigare grupp" HEADER TEXT sits in this exact column) or a Kölista row (whose same column
     * index is reused for an unrelated "Prioritet" integer, {@code GroupedXlsxWriter}'s waitlist
     * layout) would pollute the sample and skew {@code orderParseRate}/term-recency scoring.
     */
    public static List<String> sampleRealColumnValues(
            ParsedSheet sheet, int headerRowIndex, int columnIndex, BlockStructureDetector.BlockStructure blockStructure) {
        List<String> samples = new ArrayList<>(SAMPLE_LIMIT);
        for (int r = headerRowIndex + 1; r < sheet.rowCount() && samples.size() < SAMPLE_LIMIT; r++) {
            if (blockStructure != null && blockStructure.classByRow().get(r) != BlockStructureDetector.RowClass.PLAYER) {
                continue;
            }
            var cell = sheet.cellAt(r, columnIndex);
            if (!cell.isBlank()) {
                samples.add(cell.rawString());
            }
        }
        return samples;
    }

    /**
     * Up to {@link #SAMPLE_LIMIT} DISTINCT non-blank block-group labels (in row/block order), for use
     * as the synthetic block column's {@link Candidate#sampleValues()}. MINOR 7 fix: deduplicated -
     * every player row under the same block carries the identical label, so an undeduplicated sample
     * of the first {@link #SAMPLE_LIMIT} rows could easily be 10 copies of block #1's own label alone,
     * never reaching block #2/#3/... at all.
     */
    public static List<String> sampleBlockLabels(BlockStructureDetector.BlockStructure blockStructure) {
        java.util.LinkedHashSet<String> samples = new java.util.LinkedHashSet<>();
        for (Map.Entry<Integer, String> entry : new TreeMap<>(blockStructure.groupNameByRow()).entrySet()) {
            if (samples.size() >= SAMPLE_LIMIT) {
                break;
            }
            if (entry.getValue() != null && !entry.getValue().isBlank()) {
                samples.add(entry.getValue());
            }
        }
        return new ArrayList<>(samples);
    }
}
