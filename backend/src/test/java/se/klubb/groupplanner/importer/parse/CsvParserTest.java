package se.klubb.groupplanner.importer.parse;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

/**
 * Charset/delimiter matrix (M3 brief): UTF-8 with BOM, windows-1252 (with the cp1252-only chars
 * the real file's exports can contain: '’' … '–'), semicolon-delimited, and
 * comma-delimited - see docs/plan.md's red-team correction ("windows-1252, not ISO-8859-1").
 */
class CsvParserTest {

    @Test
    void utf8WithBomAndCommaDelimiter() throws Exception {
        String csv = "Förnamn,Efternamn,Kommentar\nÅsa,Öberg,\"Bra spelare\"\n";
        byte[] bytes = withUtf8Bom(csv);

        ParsedWorkbook workbook = CsvParser.parse(new ByteArrayInputStream(bytes));
        ParsedSheet sheet = workbook.sheets().get(0);

        assertThat(sheet.cellAt(0, 0).rawString()).isEqualTo("Förnamn");
        assertThat(sheet.cellAt(1, 0).rawString()).isEqualTo("Åsa");
        assertThat(sheet.cellAt(1, 1).rawString()).isEqualTo("Öberg");
    }

    @Test
    void semicolonDelimitedSwedishExport() throws Exception {
        String csv = "Förnamn;Efternamn;Rank\nNils;Åström;940\nKarin;Söderberg;784\n";
        byte[] bytes = csv.getBytes(StandardCharsets.UTF_8);

        ParsedWorkbook workbook = CsvParser.parse(new ByteArrayInputStream(bytes));
        ParsedSheet sheet = workbook.sheets().get(0);

        assertThat(sheet.rowCount()).isEqualTo(3);
        assertThat(sheet.cellAt(1, 1).rawString()).isEqualTo("Åström");
        assertThat(sheet.cellAt(2, 1).rawString()).isEqualTo("Söderberg");
    }

    @Test
    void commaDelimitedIsSniffedWhenCommasDominate() throws Exception {
        String csv = "Förnamn,Efternamn,Rank\nNils,Åström,940\n";
        byte[] bytes = csv.getBytes(StandardCharsets.UTF_8);

        ParsedWorkbook workbook = CsvParser.parse(new ByteArrayInputStream(bytes));
        ParsedSheet sheet = workbook.sheets().get(0);

        assertThat(sheet.cellAt(0, 2).rawString()).isEqualTo("Rank");
        assertThat(sheet.cellAt(1, 2).rawString()).isEqualTo("940");
    }

    @Test
    void windows1252FallbackDecodesSwedishAndSmartPunctuationCorrectly() throws Exception {
        // '’' (RIGHT SINGLE QUOTATION MARK), '…' (HORIZONTAL ELLIPSIS), '–' (EN DASH) -
        // all valid in windows-1252 but NOT valid UTF-8 byte sequences on their own, so the strict
        // UTF-8 decode attempt must fail and the windows-1252 fallback must kick in (not ISO-8859-1,
        // which cannot represent these three characters at all - docs/plan.md's red-team correction).
        String csv = "Förnamn;Kommentar\n"
                + "Björn;Kommer ej – har semester…\n"
                + "Görel;Spelarens egen kommentar’s\n";
        Charset windows1252 = Charset.forName("windows-1252");
        byte[] bytes = csv.getBytes(windows1252);

        ParsedWorkbook workbook = CsvParser.parse(new ByteArrayInputStream(bytes));
        ParsedSheet sheet = workbook.sheets().get(0);

        assertThat(sheet.cellAt(1, 0).rawString()).isEqualTo("Björn");
        assertThat(sheet.cellAt(1, 1).rawString()).isEqualTo("Kommer ej – har semester…");
        assertThat(sheet.cellAt(2, 0).rawString()).isEqualTo("Görel");
    }

    @Test
    void charsetRoundTripKeepsSwedishCharactersIntactFromCp1252() throws Exception {
        Charset windows1252 = Charset.forName("windows-1252");
        String original = "Åsa Öberg-Ångström";
        byte[] bytes = ("Namn\n" + original + "\n").getBytes(windows1252);

        ParsedWorkbook workbook = CsvParser.parse(new ByteArrayInputStream(bytes));
        ParsedSheet sheet = workbook.sheets().get(0);

        assertThat(sheet.cellAt(1, 0).rawString()).isEqualTo(original);
    }

    private static byte[] withUtf8Bom(String content) throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.write(0xEF);
        out.write(0xBB);
        out.write(0xBF);
        out.write(content.getBytes(StandardCharsets.UTF_8));
        return out.toByteArray();
    }
}
