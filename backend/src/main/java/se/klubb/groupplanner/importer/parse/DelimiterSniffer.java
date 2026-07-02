package se.klubb.groupplanner.importer.parse;

/**
 * CSV delimiter sniff: {@code ;} vs {@code ,} (docs/design/02-product-data-ui.md §2: "Swedish Excel
 * exports use ;"). Counts occurrences of each candidate on the first non-blank lines and picks the
 * more frequent one, defaulting to {@code ;} on a tie (the Swedish-Excel-default case, e.g. a
 * single-column file with no delimiters at all in either count).
 */
public final class DelimiterSniffer {

    private DelimiterSniffer() {
    }

    public static char sniff(String content) {
        String[] lines = content.split("\\R", -1);
        long semicolons = 0;
        long commas = 0;
        int consideredLines = 0;
        for (String line : lines) {
            if (line.isBlank()) {
                continue;
            }
            semicolons += line.chars().filter(c -> c == ';').count();
            commas += line.chars().filter(c -> c == ',').count();
            consideredLines++;
            if (consideredLines >= 5) {
                break;
            }
        }
        return semicolons >= commas ? ';' : ',';
    }
}
