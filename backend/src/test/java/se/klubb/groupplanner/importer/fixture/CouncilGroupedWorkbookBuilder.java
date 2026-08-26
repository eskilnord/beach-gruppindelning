package se.klubb.groupplanner.importer.fixture;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.List;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.xssf.usermodel.XSSFSheet;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;

/**
 * Anonymized structural clone of the council's grouped registration workbook layout (two category
 * sheets, flat header row, column-A metadata stack per group, float-formatted member ids,
 * pipe-concatenated previous-group history, Excel numeric time fraction). Built entirely from
 * fictional names — never from the confidential real file (CLAUDE.md).
 */
public final class CouncilGroupedWorkbookBuilder {

    public static final List<String> HEADERS = List.of(
            "Grupp",
            "Förnamn",
            "Efternamn",
            "MedlemsId",
            "Medlemsnummer",
            "Rank",
            "RankInfo",
            "GruppFöregåendeTermin",
            "Tid",
            "Tränare",
            "InternKommentar",
            "Kommentar",
            "Epost",
            "Mobil",
            "Personnummer",
            "AnmäldAktivitet");

    private CouncilGroupedWorkbookBuilder() {
    }

    public static byte[] build() throws IOException {
        try (XSSFWorkbook workbook = new XSSFWorkbook()) {
            writeHerr(workbook);
            writeDam(workbook);
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            workbook.write(out);
            return out.toByteArray();
        }
    }

    private static void writeHerr(XSSFWorkbook workbook) {
        XSSFSheet sheet = workbook.createSheet("Herr");
        writeHeader(sheet);
        int row = 1;

        // Group 1 — column A stacks color name / group label / time / coach across player rows.
        writePlayer(sheet, row++, "Torsdag Herr Solgul",
                "Ada", "Andersson", 1001.0, 21001.0, 800.0, "3, 2",
                "Torsdag Herr 1 (Vårtermin 2025) |Torsdag Herr 2",
                0.75, "Kim Svensson", "ada.andersson@example.se", "070-111 11 11");
        writePlayer(sheet, row++, "Grupp 1",
                "Bo", "Berg", 1002.0, 21002.0, 750.0, "4, 3",
                "Torsdag Herr 1 (Hösttermin 2025)",
                null, "Kim Svensson", "bo.berg@example.se", "070-222 22 22");
        writePlayer(sheet, row++, "18:00",
                "Calle", "Carlsson", 1003.0, 21003.0, 700.0, "5, 2",
                "Torsdag Herr 2 (Hösttermin 2025) |Äldre",
                0.75, "Kim Svensson", "calle.carlsson@example.se", "070-333 33 33");
        writePlayer(sheet, row++, "Tränare: Kim Svensson",
                "David", "Dahl", 1004.0, 21004.0, 650.0, "6, 2",
                "Torsdag Herr 3 (Hösttermin 2025)",
                null, "Kim Svensson", "david.dahl@example.se", "070-444 44 44");
        writeCount(sheet, row++, 4);
        row++; // blank separator

        writePlayer(sheet, row++, "Torsdag Herr Lila",
                "Erik", "Ek", 1005.0, 21005.0, 600.0, "7, 1",
                "Torsdag Herr 3 (Vårtermin 2025) |Reserv",
                0.8125, "Vera Nilsson", "erik.ek@example.se", "070-555 55 55");
        writePlayer(sheet, row++, "Grupp 2",
                "Filip", "Fors", 1006.0, 21006.0, 580.0, "8, 1",
                "Torsdag Herr 4 (Hösttermin 2025)",
                null, "Vera Nilsson", "filip.fors@example.se", "070-666 66 66");
        writeCount(sheet, row, 2);
    }

    private static void writeDam(XSSFWorkbook workbook) {
        XSSFSheet sheet = workbook.createSheet("Dam");
        writeHeader(sheet);
        int row = 1;

        writePlayer(sheet, row++, "Torsdag Dam Magenta",
                "Greta", "Gren", 2001.0, 22001.0, 900.0, "2, Elit",
                "Torsdag Dam 1 (Hösttermin 2025)",
                0.75, "Stefan Andreasson", "greta.gren@example.se", "070-777 77 77");
        writePlayer(sheet, row++, "Grupp 1",
                "Hanna", "Holm", 2002.0, 22002.0, 850.0, "3, 1",
                "Torsdag Dam 1 (Vårtermin 2025) |Torsdag Dam 2",
                0.75, "Stefan Andreasson", "hanna.holm@example.se", "070-888 88 88");
        writePlayer(sheet, row++, "Tränare: Stefan Andreasson",
                "Ida", "Ivarsson", 2003.0, 22003.0, 820.0, "4, 2",
                "Torsdag Dam 2 (Hösttermin 2025)",
                null, "Stefan Andreasson", "ida.ivarsson@example.se", "070-999 99 99");
        writeCount(sheet, row++, 3);
        row++;

        writePlayer(sheet, row++, "Torsdag Dam Cyan",
                "Jenny", "Jansson", 2004.0, 22004.0, 800.0, "5, 2",
                "Torsdag Dam 2 (Vårtermin 2025)",
                null, "Per Ljunggren", "jenny.jansson@example.se", "070-101 01 01");
        writePlayer(sheet, row++, "Grupp 2",
                "Klara", "Karlsson", 2005.0, 22005.0, 780.0, "6, 1",
                "Torsdag Dam 3 (Hösttermin 2025)",
                0.8125, "Per Ljunggren", "klara.karlsson@example.se", "070-202 02 02");
        writeCount(sheet, row, 2);
    }

    private static void writeHeader(XSSFSheet sheet) {
        Row row = sheet.createRow(0);
        for (int i = 0; i < HEADERS.size(); i++) {
            row.createCell(i).setCellValue(HEADERS.get(i));
        }
    }

    private static void writeCount(XSSFSheet sheet, int rowIndex, int count) {
        Row row = sheet.createRow(rowIndex);
        row.createCell(0).setCellValue(count + " spelare");
    }

    private static void writePlayer(
            XSSFSheet sheet,
            int rowIndex,
            String colA,
            String firstName,
            String lastName,
            double memberId,
            double memberNumber,
            double rank,
            String rankInfo,
            String previousGroup,
            Double timeFraction,
            String coach,
            String email,
            String phone) {
        Row row = sheet.createRow(rowIndex);
        row.createCell(0).setCellValue(colA);
        row.createCell(1).setCellValue(firstName);
        row.createCell(2).setCellValue(lastName);
        row.createCell(3).setCellValue(memberId);
        row.createCell(4).setCellValue(memberNumber);
        row.createCell(5).setCellValue(rank);
        row.createCell(6).setCellValue(rankInfo);
        row.createCell(7).setCellValue(previousGroup);
        if (timeFraction != null) {
            row.createCell(8).setCellValue(timeFraction);
        }
        if (coach != null) {
            row.createCell(9).setCellValue(coach);
        }
        row.createCell(10).setCellValue("");
        row.createCell(11).setCellValue("Önskar samma grupp");
        row.createCell(12).setCellValue(email);
        row.createCell(13).setCellValue(phone);
        // Deliberately blank — a filled personnummer cell must never be imported, and leaving it
        // empty here also avoids the block detector mistaking data rows for header rows.
        row.createCell(14).setCellValue("");
        row.createCell(15).setCellValue("Torsdagsträning VT");
    }
}
