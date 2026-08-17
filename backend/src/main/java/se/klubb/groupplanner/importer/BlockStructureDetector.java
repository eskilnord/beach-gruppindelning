package se.klubb.groupplanner.importer;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;
import se.klubb.groupplanner.importer.parse.ParsedCell;
import se.klubb.groupplanner.importer.parse.ParsedSheet;

/**
 * Detects the "group block" structure of an import file (WP1): registration sheets typically list
 * players under repeated group headings/metadata rather than a single flat header row, and the
 * block a player sits under is their most recent ("tidigare") group. This is a pure, static
 * heuristic in the style of {@link HeaderDetector} - conservative by design (returns {@link
 * Optional#empty()} unless it is confident), never a silent decision: the wizard only ever offers
 * the derived "Grupp i filen" column as an optional mapping target (see {@code ImportController}).
 *
 * <p>Two layouts are recognized:
 *
 * <ul>
 *   <li><b>Layout 1 - repeated headers</b> (this app's own {@code exporter/GroupedXlsxWriter}
 *       export): a single-cell group heading, metadata rows ({@code Ordning:}/{@code Tid:}/{@code
 *       Tränare:}), a player-header row, player rows, a count row, a blank separator - repeated per
 *       group, with the header row's exact column signature recurring.
 *   <li><b>Layout 2 - column-A metadata stack</b> (a messier council layout, see {@code
 *       importer/fixture/MessyWorkbookBuilder}): a single header row at the top of the sheet; each
 *       group's data rows are a blank-separated run whose column-0 cells carry a mix of {@code Grupp
 *       N}/time/{@code Tränare:} metadata, terminated by a "N spelare" count row.
 * </ul>
 */
public final class BlockStructureDetector {

    private static final Pattern COUNT_ROW = Pattern.compile(
            "^(antal spelare:?\\s*\\d+|\\d+\\s*spelare.*|antal på kölista:?\\s*\\d+)$");
    private static final Pattern ORDNING = Pattern.compile("^ordning:.*");
    private static final Pattern TID = Pattern.compile("^tid:.*");
    private static final Pattern TRANARE = Pattern.compile("^(tränare|tranare):.*");
    // Matched against the RAW (trimmed, not normalize()d) cell text - ColumnMappingSuggester.normalize
    // turns '.' into a space ("18.00" -> "18 00"), which would otherwise never match this pattern.
    private static final Pattern TIME_LIKE = Pattern.compile("^\\d{1,2}[:.]\\d{2}$");
    private static final Pattern SECTION_KEYWORD = Pattern.compile(
            ".*(kölista|kolista|väntelista|vantelista|utanför|utanfor|reserv(er)?).*");
    private static final Pattern GRUPP = Pattern.compile("^grupp\\s*\\d+$");
    private static final Pattern TRAILING_INT = Pattern.compile(".*\\d+$");

    /** A metadata-shaped first cell (Ordning:/Tid:/Tränare:/time-like) only makes a row structural
     *  when the row is otherwise genuinely just that one metadata line - at most this many non-blank
     *  cells. A player row that happens to carry column-A metadata alongside real name/ranking/etc.
     *  data (the messy layout's own shape) must stay PLAYER, not be silently dropped as STRUCTURE. */
    private static final int MAX_NON_BLANK_CELLS_FOR_METADATA_ROW = 2;

    private static final int MIN_NON_BLANK_CELLS_FOR_HEADER = 3;
    private static final int MIN_RESOLVED_CELLS_FOR_HEADER = 2;
    private static final int MIN_BLOCK_COUNT = 2;

    public enum RowClass { PLAYER, STRUCTURE }

    public record BlockStructure(Map<Integer, RowClass> classByRow, Map<Integer, String> groupNameByRow, int blockCount) {
    }

    private BlockStructureDetector() {
    }

    public static Optional<BlockStructure> detect(ParsedSheet sheet, int headerRowIndex) {
        Optional<BlockStructure> layout1 = detectRepeatedHeaders(sheet);
        if (layout1.isPresent()) {
            return layout1;
        }
        return detectColumnAMetadataStack(sheet, headerRowIndex);
    }

    /**
     * WP1 session-creation UX: the row of the earliest repeated player-header signature, if any -
     * independent of {@link #detect} (this needs to run BEFORE a header row is chosen at all, since
     * {@link HeaderDetector}'s own heuristic can trivially latch onto Layout 1's width-1 group-heading
     * row first: a single non-empty cell has a 100% non-blank ratio). {@code ImportSession} uses this
     * to override {@link HeaderDetector#detect}'s default when it disagrees, so re-uploading this
     * app's own export lands on the real "Namn/Ranking/..." header instead of a heading row nothing
     * can be usefully mapped from.
     */
    public static Optional<Integer> firstRepeatedHeaderRow(ParsedSheet sheet) {
        Map<String, Integer> signatureCounts = new HashMap<>();
        Map<String, Integer> firstRowBySignature = new LinkedHashMap<>();
        for (int r = 0; r < sheet.rowCount(); r++) {
            if (isPlayerHeaderRow(sheet, r)) {
                String signature = headerSignature(sheet, r);
                signatureCounts.merge(signature, 1, Integer::sum);
                firstRowBySignature.putIfAbsent(signature, r);
            }
        }
        Optional<Integer> best = Optional.empty();
        for (Map.Entry<String, Integer> entry : signatureCounts.entrySet()) {
            if (entry.getValue() >= MIN_BLOCK_COUNT) {
                int row = firstRowBySignature.get(entry.getKey());
                if (best.isEmpty() || row < best.get()) {
                    best = Optional.of(row);
                }
            }
        }
        return best;
    }

    // -------------------------------------------------------------------------------------------
    // Layout 1 - repeated headers.
    // -------------------------------------------------------------------------------------------

    private static Optional<BlockStructure> detectRepeatedHeaders(ParsedSheet sheet) {
        Map<Integer, String> signatureByRow = new HashMap<>();
        Map<String, Integer> signatureCounts = new HashMap<>();
        for (int r = 0; r < sheet.rowCount(); r++) {
            if (isPlayerHeaderRow(sheet, r)) {
                String signature = headerSignature(sheet, r);
                signatureByRow.put(r, signature);
                signatureCounts.merge(signature, 1, Integer::sum);
            }
        }
        boolean anyRepeated = signatureCounts.values().stream().anyMatch(count -> count >= MIN_BLOCK_COUNT);
        if (!anyRepeated) {
            return Optional.empty();
        }

        Map<Integer, RowClass> classByRow = new HashMap<>();
        Map<Integer, String> groupNameByRow = new HashMap<>();
        Set<String> blockLabels = new LinkedHashSet<>();
        Set<String> blockLabelsWithPlayers = new HashSet<>();

        boolean inSection = false;
        boolean collectingData = false;
        String currentGroupLabel = null;
        String pendingHeadingLabel = null;

        for (int r = 0; r < sheet.rowCount(); r++) {
            if (sheet.isRowEntirelyBlank(r)) {
                collectingData = false;
                pendingHeadingLabel = null;
                continue;
            }
            List<ParsedCell> nonBlank = nonBlankCells(sheet, r);

            if (isSectionKeywordRow(nonBlank)) {
                classByRow.put(r, RowClass.STRUCTURE);
                inSection = true;
                currentGroupLabel = null;
                collectingData = false;
                pendingHeadingLabel = null;
                continue;
            }
            if (isCountRow(nonBlank)) {
                classByRow.put(r, RowClass.STRUCTURE);
                collectingData = false;
                pendingHeadingLabel = null;
                continue;
            }
            if (signatureByRow.containsKey(r)) {
                classByRow.put(r, RowClass.STRUCTURE);
                if (!inSection) {
                    currentGroupLabel = pendingHeadingLabel;
                    if (currentGroupLabel != null) {
                        blockLabels.add(currentGroupLabel);
                    }
                } else {
                    currentGroupLabel = null;
                }
                collectingData = true;
                pendingHeadingLabel = null;
                continue;
            }
            // MAJOR 1 fix: a metadata-shaped first cell only makes the row STRUCTURE when the row is
            // genuinely just that one metadata line (<=2 non-blank cells) - a real player row whose
            // column A happens to carry "18:00"/"Tränare: X" (the messy layout's own shape, possibly
            // combined with Layout 1's repeated headers in a hybrid file) must stay PLAYER, never be
            // silently dropped just because its first cell looks like metadata.
            if (isMetadataRow(nonBlank) && nonBlank.size() <= MAX_NON_BLANK_CELLS_FOR_METADATA_ROW) {
                classByRow.put(r, RowClass.STRUCTURE);
                continue;
            }
            if (collectingData) {
                classByRow.put(r, RowClass.PLAYER);
                if (currentGroupLabel != null) {
                    groupNameByRow.put(r, currentGroupLabel);
                    blockLabelsWithPlayers.add(currentGroupLabel);
                }
                continue;
            }
            if (nonBlank.size() == 1) {
                classByRow.put(r, RowClass.STRUCTURE);
                pendingHeadingLabel = nonBlank.get(0).rawString().strip();
            }
            // Anything else while not collecting data (and not a single free-text cell) is left
            // unclassified - conservative: we simply don't know what it is.
        }

        blockLabels.retainAll(blockLabelsWithPlayers);
        if (blockLabels.size() < MIN_BLOCK_COUNT) {
            return Optional.empty();
        }
        return Optional.of(new BlockStructure(classByRow, groupNameByRow, blockLabels.size()));
    }

    private static String headerSignature(ParsedSheet sheet, int rowIndex) {
        StringBuilder sb = new StringBuilder();
        int columnCount = sheet.columnCount();
        for (int c = 0; c < columnCount; c++) {
            sb.append(ColumnMappingSuggester.normalize(sheet.cellAt(rowIndex, c).rawString())).append('|');
        }
        return sb.toString();
    }

    // -------------------------------------------------------------------------------------------
    // Layout 2 - column-A metadata stack.
    // -------------------------------------------------------------------------------------------

    private enum ColumnACategory { GRUPP_OR_TRAILING_INT, TIME, TRANARE, NONE }

    private static Optional<BlockStructure> detectColumnAMetadataStack(ParsedSheet sheet, int headerRowIndex) {
        List<List<Integer>> runs = splitIntoRuns(sheet, headerRowIndex + 1, sheet.rowCount() - 1);

        Map<Integer, RowClass> classByRow = new HashMap<>();
        Map<Integer, String> groupNameByRow = new LinkedHashMap<>();
        Set<String> blockLabels = new LinkedHashSet<>();

        for (List<Integer> run : runs) {
            int firstRow = run.get(0);
            List<ParsedCell> firstRowNonBlank = nonBlankCells(sheet, firstRow);
            if (isSectionKeywordRow(firstRowNonBlank)) {
                classByRow.put(firstRow, RowClass.STRUCTURE);
                for (int i = 1; i < run.size(); i++) {
                    int r = run.get(i);
                    if (isCountRow(nonBlankCells(sheet, r))) {
                        classByRow.put(r, RowClass.STRUCTURE);
                    } else {
                        classByRow.put(r, RowClass.PLAYER);
                        // No group name - a waitlist/excluded section is not a "previous group".
                    }
                }
                continue;
            }

            boolean containsCountRow = run.stream().anyMatch(r -> isCountRow(nonBlankCells(sheet, r)));
            Set<ColumnACategory> categories = new HashSet<>();
            for (int r : run) {
                ParsedCell col0 = sheet.cellAt(r, 0);
                if (!col0.isBlank()) {
                    categories.add(categorize(col0.rawString()));
                }
            }
            categories.remove(ColumnACategory.NONE);
            boolean qualifiesViaCategories = categories.size() >= 2;
            boolean qualifiesAsBlock = qualifiesViaCategories || containsCountRow;
            if (!qualifiesAsBlock) {
                continue; // Conservative: unrecognized run, leave unclassified.
            }

            // MINOR 3 fix: when a run qualifies ONLY because it contains a count row (not because
            // col-0 carries >=2 recognized metadata categories), fallback label (c) - "first non-blank
            // col-0 value" - is too weak a signal; it can pick up a player's own first name. Only
            // trust the strong (a)/(b) label patterns in that case.
            String label = qualifiesViaCategories ? pickLabel(sheet, run, true) : pickLabel(sheet, run, false);
            if (label == null) {
                for (int r : run) {
                    if (isCountRow(nonBlankCells(sheet, r))) {
                        classByRow.put(r, RowClass.STRUCTURE);
                    } else if (isPlayerHeaderRow(sheet, r)) {
                        classByRow.put(r, RowClass.STRUCTURE);
                    } else {
                        classByRow.put(r, RowClass.PLAYER);
                        // No confident group label - PLAYER but no previous group, not a fabricated one.
                    }
                }
                continue;
            }
            blockLabels.add(label);
            for (int r : run) {
                if (isCountRow(nonBlankCells(sheet, r))) {
                    classByRow.put(r, RowClass.STRUCTURE);
                } else if (isPlayerHeaderRow(sheet, r)) {
                    // MINOR 1 fix: a row that itself looks like a player-header row (repeated header
                    // without heading rows, or a stray header inside a block run) must never become a
                    // "participant" named e.g. "Namn" - it's structure, not data.
                    classByRow.put(r, RowClass.STRUCTURE);
                } else {
                    classByRow.put(r, RowClass.PLAYER);
                    groupNameByRow.put(r, label);
                }
            }
        }

        if (blockLabels.size() < MIN_BLOCK_COUNT) {
            return Optional.empty();
        }
        return Optional.of(new BlockStructure(classByRow, groupNameByRow, blockLabels.size()));
    }

    /** @param allowFallbackC whether the weak "first non-blank, non-metadata col-0 value" fallback (c)
     *  may be used - see the MINOR 3 fix note at the call site. */
    private static String pickLabel(ParsedSheet sheet, List<Integer> run, boolean allowFallbackC) {
        // (a) exact "Grupp N".
        for (int r : run) {
            ParsedCell col0 = sheet.cellAt(r, 0);
            if (!col0.isBlank() && GRUPP.matcher(ColumnMappingSuggester.normalize(col0.rawString())).matches()) {
                return col0.rawString().strip();
            }
        }
        // (b) any value with a trailing integer, excluding time-like/tränare/count values.
        for (int r : run) {
            ParsedCell col0 = sheet.cellAt(r, 0);
            if (col0.isBlank() || isTimeLike(col0.rawString())) {
                continue;
            }
            String normalized = ColumnMappingSuggester.normalize(col0.rawString());
            if (TRANARE.matcher(normalized).matches() || COUNT_ROW.matcher(normalized).matches()) {
                continue;
            }
            if (TRAILING_INT.matcher(normalized).matches()) {
                return col0.rawString().strip();
            }
        }
        if (!allowFallbackC) {
            return null;
        }
        // (c) first non-blank col-0 value that isn't time-like/tränare/count.
        for (int r : run) {
            ParsedCell col0 = sheet.cellAt(r, 0);
            if (col0.isBlank() || isTimeLike(col0.rawString())) {
                continue;
            }
            String normalized = ColumnMappingSuggester.normalize(col0.rawString());
            if (TRANARE.matcher(normalized).matches() || COUNT_ROW.matcher(normalized).matches()) {
                continue;
            }
            return col0.rawString().strip();
        }
        return null;
    }

    /** Category order matters: a time value like "18:00"/"18.00" always ends in a digit, so trailing-
     *  int must be tested AFTER time-likeness or TIME is unreachable (MINOR 2 fix). */
    private static ColumnACategory categorize(String rawText) {
        if (isTimeLike(rawText)) {
            return ColumnACategory.TIME;
        }
        String normalized = ColumnMappingSuggester.normalize(rawText);
        if (GRUPP.matcher(normalized).matches() || TRAILING_INT.matcher(normalized).matches()) {
            return ColumnACategory.GRUPP_OR_TRAILING_INT;
        }
        if (TRANARE.matcher(normalized).matches()) {
            return ColumnACategory.TRANARE;
        }
        return ColumnACategory.NONE;
    }

    private static List<List<Integer>> splitIntoRuns(ParsedSheet sheet, int fromRow, int toRow) {
        List<List<Integer>> runs = new ArrayList<>();
        List<Integer> current = new ArrayList<>();
        for (int r = fromRow; r <= toRow; r++) {
            if (sheet.isRowEntirelyBlank(r)) {
                if (!current.isEmpty()) {
                    runs.add(current);
                    current = new ArrayList<>();
                }
            } else {
                current.add(r);
            }
        }
        if (!current.isEmpty()) {
            runs.add(current);
        }
        return runs;
    }

    // -------------------------------------------------------------------------------------------
    // Shared row classifiers.
    // -------------------------------------------------------------------------------------------

    private static List<ParsedCell> nonBlankCells(ParsedSheet sheet, int rowIndex) {
        List<ParsedCell> result = new ArrayList<>();
        for (ParsedCell cell : sheet.rowAt(rowIndex)) {
            if (cell != null && !cell.isBlank()) {
                result.add(cell);
            }
        }
        return result;
    }

    private static boolean isCountRow(List<ParsedCell> nonBlankCells) {
        if (nonBlankCells.isEmpty()) {
            return false;
        }
        return COUNT_ROW.matcher(ColumnMappingSuggester.normalize(nonBlankCells.get(0).rawString())).matches();
    }

    private static boolean isMetadataRow(List<ParsedCell> nonBlankCells) {
        if (nonBlankCells.isEmpty()) {
            return false;
        }
        String rawFirst = nonBlankCells.get(0).rawString();
        String normalized = ColumnMappingSuggester.normalize(rawFirst);
        return ORDNING.matcher(normalized).matches() || TID.matcher(normalized).matches()
                || TRANARE.matcher(normalized).matches() || isTimeLike(rawFirst);
    }

    private static boolean isSectionKeywordRow(List<ParsedCell> nonBlankCells) {
        if (nonBlankCells.size() != 1) {
            return false;
        }
        return SECTION_KEYWORD.matcher(ColumnMappingSuggester.normalize(nonBlankCells.get(0).rawString())).matches();
    }

    /** Matched against the raw (trimmed) text, not {@code ColumnMappingSuggester.normalize()}'d text -
     *  normalization turns '.' into a space, which would otherwise make "18.00" never match. */
    private static boolean isTimeLike(String rawText) {
        return rawText != null && TIME_LIKE.matcher(rawText.strip()).matches();
    }

    private static boolean isPlayerHeaderRow(ParsedSheet sheet, int rowIndex) {
        List<ParsedCell> nonBlank = nonBlankCells(sheet, rowIndex);
        if (nonBlank.size() < MIN_NON_BLANK_CELLS_FOR_HEADER) {
            return false;
        }
        int resolved = 0;
        for (ParsedCell cell : nonBlank) {
            if (ColumnMappingSuggester.suggest(cell.rawString()).isPresent()) {
                resolved++;
            }
        }
        return resolved >= MIN_RESOLVED_CELLS_FOR_HEADER;
    }
}
