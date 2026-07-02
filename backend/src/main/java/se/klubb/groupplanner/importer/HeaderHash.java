package se.klubb.groupplanner.importer;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.List;
import java.util.Locale;
import se.klubb.groupplanner.importer.parse.ParsedSheet;

/**
 * Computes a stable hash of a sheet's header row, used to key {@code import_template} (spec §8.3
 * step 5/8 "Spara som importmall") so a structurally identical file (same headers, same order) can
 * be auto-suggested on a later upload (docs/plan.md: "reusable import templates keyed by header
 * hash").
 *
 * <p>Normalizes each header cell (trim + lowercase) before hashing so trivial whitespace/case
 * differences between exports of "the same" file don't defeat the match.
 */
public final class HeaderHash {

    private HeaderHash() {
    }

    public static String computeForSheet(ParsedSheet sheet, int headerRowIndex) {
        List<String> headerTexts = sheet.rowAt(headerRowIndex).stream()
                .map(cell -> cell.rawString().strip().toLowerCase(Locale.ROOT))
                .toList();
        return compute(headerTexts);
    }

    public static String compute(List<String> normalizedHeaderTexts) {
        String joined = String.join("|", normalizedHeaderTexts);
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(joined.getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder(hash.length * 2);
            for (byte b : hash) {
                hex.append(String.format("%02x", b));
            }
            return hex.toString();
        } catch (NoSuchAlgorithmException e) {
            // SHA-256 is guaranteed available on every JDK per the MessageDigest algorithm spec.
            throw new IllegalStateException(e);
        }
    }
}
