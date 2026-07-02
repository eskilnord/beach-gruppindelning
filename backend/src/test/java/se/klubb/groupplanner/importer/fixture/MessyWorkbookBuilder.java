package se.klubb.groupplanner.importer.fixture;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.apache.poi.openxml4j.opc.PackagePart;
import org.apache.poi.openxml4j.opc.PackagePartName;
import org.apache.poi.openxml4j.opc.PackagingURIHelper;
import org.apache.poi.openxml4j.opc.TargetMode;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.CellStyle;
import org.apache.poi.ss.usermodel.DateUtil;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.xssf.usermodel.XSSFCellStyle;
import org.apache.poi.xssf.usermodel.XSSFClientAnchor;
import org.apache.poi.xssf.usermodel.XSSFComment;
import org.apache.poi.xssf.usermodel.XSSFDrawing;
import org.apache.poi.xssf.usermodel.XSSFRichTextString;
import org.apache.poi.xssf.usermodel.XSSFSheet;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;

/**
 * Builds an in-memory {@code .xlsx} workbook shaped like the real confidential file's messy layout
 * (M3 brief CONTEXT section) from committed, anonymized data in
 * {@code ../test-data/datasets/large-120/participants.csv} - never from the real file (CLAUDE.md).
 *
 * <p>Reproduces, generically (no column names/values hardcoded from the real file beyond the shape
 * itself - CLAUDE.md §26.1): a header row, a first column that stacks per-group metadata (color
 * name / group number / time slot / coach) vertically across a group's rows with blank separator
 * rows between groups, a "N spelare" count row with no name data, a mixed-type {@code Tid} column
 * (genuine Excel time cells alongside free text like {@code "ej 21"} and one genuinely invalid
 * value), a text-formatted number with NBSP thousands separator, a within-file duplicate name, a
 * legacy cell comment, a best-effort threaded comment, a coach-wish column, an {@code isCoach}
 * column, a member-id column (externalId; the {@code p006Duplicate} row deliberately repeats
 * {@code p006}'s member id - the same member listed twice), and trailing {@code Kölista}/"Utanför
 * pga tid" sections with annotation-only rows.
 *
 * <p>Row indices are never hardcoded by callers - {@link BuiltWorkbook#rowIndexByLabel()} maps a
 * semantic label (e.g. {@code "p004"}, the row with the genuinely invalid time text) to its actual
 * 0-based row index, so the layout can change without every test needing updating.
 */
public final class MessyWorkbookBuilder {

    public static final String SHEET_NAME = "Herr";
    public static final List<String> HEADERS = List.of(
            "", "Förnamn", "Efternamn", "Rank", "Kommentar", "Epost", "Tid", "Tidigare grupp", "Tränare", "Ledare", "MedlemsId");

    private MessyWorkbookBuilder() {
    }

    public record ParticipantSource(String id, String firstName, String lastName, String email, String rankingPoints, String previousGroupName) {
    }

    public record BuiltWorkbook(byte[] bytes, Map<String, Integer> rowIndexByLabel, int headerRowIndex) {
        public int row(String label) {
            Integer index = rowIndexByLabel.get(label);
            if (index == null) {
                throw new IllegalArgumentException("No such row label: " + label + " (known: " + rowIndexByLabel.keySet() + ")");
            }
            return index;
        }
    }

    public static BuiltWorkbook build() throws IOException {
        List<ParticipantSource> people = readParticipantSources();

        try (XSSFWorkbook workbook = new XSSFWorkbook()) {
            XSSFSheet sheet = workbook.createSheet(SHEET_NAME);
            CellStyle timeStyle = workbook.createCellStyle();
            timeStyle.setDataFormat(workbook.getCreationHelper().createDataFormat().getFormat("hh:mm"));

            Map<String, Integer> rowIndexByLabel = new LinkedHashMap<>();
            int[] nextRow = {0};

            writeRow(sheet, nextRow[0]++, HEADERS.toArray(new String[0]));

            // --- Group 1: metadata rows 1-4 double as player rows 1-4; row 5 is a count-only row
            // (no name -> "saknat namn"); row 6 is a fully blank separator ("tomma rad"). ---
            ParticipantSource p1 = people.get(0);
            ParticipantSource p2 = people.get(1);
            ParticipantSource p3 = people.get(2);
            ParticipantSource p4 = people.get(3);

            int r = nextRow[0];
            rowIndexByLabel.put("p001", r);
            writeRow(sheet, r++, "Blå", p1.firstName(), p1.lastName(), "", "Vill helst spela med kompisar",
                    p1.email(), null, p1.previousGroupName(), "", "", p1.id());
            setTimeCell(sheet, r - 1, 6, timeStyle, "18:00");
            setNumericCell(sheet, r - 1, 3, Double.parseDouble(p1.rankingPoints()));

            rowIndexByLabel.put("p002", r);
            writeRow(sheet, r++, "Grupp 1", p2.firstName(), p2.lastName(), p2.rankingPoints(), "", p2.email(),
                    "18:00", p2.previousGroupName(), "", "", p2.id());
            attachLegacyComment(sheet, r - 1, 4, "Ring innan match - allergi mot jordnötter.");

            rowIndexByLabel.put("p003", r); // å/ä/ö name (Söderberg) + NBSP-thousands rank text.
            writeRow(sheet, r++, "18:00", p3.firstName(), p3.lastName(), "1 234,5",
                    "Ny i klubben, vill gärna ha råd om utrustning", p3.email(), "ej 21", p3.previousGroupName(), "", "", p3.id());

            rowIndexByLabel.put("p004", r); // Genuinely invalid number AND invalid time text.
            writeRow(sheet, r++, "Tränare: Frida", p4.firstName(), p4.lastName(), "ett tusen", "",
                    p4.email(), "arton", p4.previousGroupName(), "", "", p4.id());
            attachThreadedComment(workbook, sheet, r - 1, 5, "Uppföljning: kolla betalning.");

            rowIndexByLabel.put("group1CountRow", r);
            writeRow(sheet, r++, "4 spelare");

            rowIndexByLabel.put("blankRow1", r);
            r++; // Entirely blank separator row - createRow() not even called.

            // --- Group 2 ---
            ParticipantSource p5 = people.get(4);
            ParticipantSource p6 = people.get(5);
            ParticipantSource p7 = people.get(6);
            ParticipantSource p8 = people.get(7);

            rowIndexByLabel.put("p005", r);
            writeRow(sheet, r++, "Röd", p5.firstName(), p5.lastName(), null, "", p5.email(), null, p5.previousGroupName(), "", "", p5.id());
            setTimeCell(sheet, r - 1, 6, timeStyle, "19:30");
            setNumericCell(sheet, r - 1, 3, Double.parseDouble(p5.rankingPoints()));

            rowIndexByLabel.put("p006", r); // First half of a within-file name duplicate (also see "p006Duplicate").
            writeRow(sheet, r++, "Grupp 2", p6.firstName(), p6.lastName(), p6.rankingPoints(), "", p6.email(),
                    "18:00", p6.previousGroupName(), "", "", p6.id());

            rowIndexByLabel.put("p007", r); // Has a coach wish.
            writeRow(sheet, r++, "19:30", p7.firstName(), p7.lastName(), null, "", p7.email(), null,
                    p7.previousGroupName(), "Kalle", "", p7.id());
            setTimeCell(sheet, r - 1, 6, timeStyle, "21:00");
            setNumericCell(sheet, r - 1, 3, Double.parseDouble(p7.rankingPoints()));

            rowIndexByLabel.put("p008", r); // Blank ranking -> "saknad ranking".
            writeRow(sheet, r++, "Tränare: Frida", p8.firstName(), p8.lastName(), "", "", p8.email(), "",
                    p8.previousGroupName(), "", "", p8.id());

            rowIndexByLabel.put("group2CountRow", r);
            writeRow(sheet, r++, "4 spelare");

            rowIndexByLabel.put("blankRow2", r);
            r++;

            // --- Group 3 ---
            ParticipantSource p11 = people.get(10);
            ParticipantSource p12 = people.get(11);
            ParticipantSource p13 = people.get(12);
            ParticipantSource p14 = people.get(13);

            rowIndexByLabel.put("p011", r);
            writeRow(sheet, r++, "Gul", p11.firstName(), p11.lastName(), null, "", p11.email(), null, p11.previousGroupName(), "", "", p11.id());
            setTimeCell(sheet, r - 1, 6, timeStyle, "16:30");
            setNumericCell(sheet, r - 1, 3, Double.parseDouble(p11.rankingPoints()));

            rowIndexByLabel.put("p012", r);
            writeRow(sheet, r++, "Grupp 3", p12.firstName(), p12.lastName(), p12.rankingPoints(), "", p12.email(),
                    "", p12.previousGroupName(), "Anna", "", p12.id());

            rowIndexByLabel.put("p013", r);
            writeRow(sheet, r++, "16:30", p13.firstName(), p13.lastName(), p13.rankingPoints(), "", p13.email(),
                    "ej 18", p13.previousGroupName(), "", "", p13.id());

            rowIndexByLabel.put("p014", r);
            writeRow(sheet, r++, "Tränare: Marcus", p14.firstName(), p14.lastName(), p14.rankingPoints(), "",
                    p14.email(), "18:00, 19:30", p14.previousGroupName(), "", "", p14.id());

            rowIndexByLabel.put("group3CountRow", r);
            writeRow(sheet, r++, "4 spelare");

            rowIndexByLabel.put("blankRow3", r);
            r++;

            // --- A row that imports a coach directly (isCoach column truthy), spec §13.1. ---
            rowIndexByLabel.put("coachRow", r);
            writeRow(sheet, r++, "", "Coach", "Persson", "", "", "coach.persson@example.se", "", "", "", "x", "coach001");

            rowIndexByLabel.put("blankRow4", r);
            r++;

            // --- Kölista (waiting list) section: an annotation-only header row, then players,
            // including a second occurrence of an already-used name (within-file duplicate). ---
            rowIndexByLabel.put("kolistaHeaderRow", r);
            writeRow(sheet, r++, "Kölista");
            // A section heading merged across a few columns - real registration sheets commonly
            // do this; the parser must tolerate merged regions without crashing (M3 brief).
            sheet.addMergedRegion(new org.apache.poi.ss.util.CellRangeAddress(r - 1, r - 1, 0, 3));

            ParticipantSource p15 = people.get(14);
            rowIndexByLabel.put("p015", r); // Comma-separated multi-time value.
            writeRow(sheet, r++, "", p15.firstName(), p15.lastName(), p15.rankingPoints(), "", p15.email(),
                    "18:00,19:30", p15.previousGroupName(), "", "", p15.id());

            rowIndexByLabel.put("p006Duplicate", r); // Same normalized name as "p006" above.
            writeRow(sheet, r++, "", p6.firstName(), p6.lastName(), p6.rankingPoints(), "", "annanmail@example.se",
                    "", p6.previousGroupName(), "", "", p6.id());

            rowIndexByLabel.put("blankRow5", r);
            r++;

            // --- "Utanför pga tid" (excluded due to time constraints) section. ---
            rowIndexByLabel.put("utanforHeaderRow", r);
            writeRow(sheet, r++, "Utanför pga tid");
            sheet.addMergedRegion(new org.apache.poi.ss.util.CellRangeAddress(r - 1, r - 1, 0, 3));

            ParticipantSource p16 = people.get(15);
            rowIndexByLabel.put("p016", r);
            writeRow(sheet, r++, "", p16.firstName(), p16.lastName(), p16.rankingPoints(), "", p16.email(), "",
                    p16.previousGroupName(), "", "", p16.id());

            nextRow[0] = r;

            ByteArrayOutputStream out = new ByteArrayOutputStream();
            workbook.write(out);
            return new BuiltWorkbook(out.toByteArray(), rowIndexByLabel, 0);
        }
    }

    private static void writeRow(XSSFSheet sheet, int rowIndex, String... values) {
        Row row = sheet.createRow(rowIndex);
        for (int c = 0; c < values.length; c++) {
            if (values[c] == null) {
                continue; // Leave the cell entirely absent (blank), not an empty-string cell.
            }
            row.createCell(c).setCellValue(values[c]);
        }
    }

    private static void setNumericCell(XSSFSheet sheet, int rowIndex, int columnIndex, double value) {
        Cell cell = sheet.getRow(rowIndex).createCell(columnIndex);
        cell.setCellValue(value);
    }

    private static void setTimeCell(XSSFSheet sheet, int rowIndex, int columnIndex, CellStyle timeStyle, String hhmm) {
        Cell cell = sheet.getRow(rowIndex).createCell(columnIndex);
        cell.setCellValue(DateUtil.convertTime(hhmm));
        cell.setCellStyle(timeStyle);
    }

    private static void attachLegacyComment(XSSFSheet sheet, int rowIndex, int columnIndex, String text) {
        XSSFDrawing drawing = sheet.createDrawingPatriarch();
        XSSFClientAnchor anchor = drawing.createAnchor(0, 0, 0, 0, columnIndex, rowIndex, columnIndex + 2, rowIndex + 2);
        XSSFComment comment = drawing.createCellComment(anchor);
        comment.setString(new XSSFRichTextString(text));
        comment.setAuthor("Kansliet");
        sheet.getRow(rowIndex).getCell(columnIndex).setCellComment(comment);
    }

    /**
     * Best-effort creation of a modern "threaded comment" OOXML part (Excel 365's successor to the
     * legacy note/comment mechanism POI's high-level API supports). POI 5.5.1 has no high-level
     * builder for these (verified: no {@code Threaded*} class in poi/poi-ooxml 5.5.1), so this adds
     * the real {@code xl/threadedComments/...} and {@code xl/persons/...} parts directly via the
     * public {@code OPCPackage} part API, wired to the worksheet/workbook with the relationship
     * types Microsoft's extension defines. The goal is solely to prove {@code XlsxParser} tolerates
     * a file containing one (never crashes, still reads cell values correctly) - nothing in this
     * codebase ever reads threaded-comment content.
     */
    private static void attachThreadedComment(XSSFWorkbook workbook, XSSFSheet sheet, int rowIndex, int columnIndex, String text) {
        try {
            String cellRef = new org.apache.poi.ss.util.CellReference(rowIndex, columnIndex).formatAsString();
            String personId = "{5A2FE9E0-0000-0000-0000-000000000001}";
            String commentId = "{5A2FE9E0-0000-0000-0000-000000000002}";

            String personsXml = "<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>"
                    + "<personList xmlns=\"http://schemas.microsoft.com/office/spreadsheetml/2018/threadedcomments\" "
                    + "xmlns:x=\"http://schemas.openxmlformats.org/spreadsheetml/2006/main\">"
                    + "<person displayName=\"Kansliet\" id=\"" + personId + "\" userId=\"Kansliet\" providerId=\"None\"/>"
                    + "</personList>";

            String threadedCommentsXml = "<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>"
                    + "<ThreadedComments xmlns=\"http://schemas.microsoft.com/office/spreadsheetml/2018/threadedcomments\">"
                    + "<threadedComment ref=\"" + cellRef + "\" dT=\"2026-01-01T00:00:00.00Z\" personId=\"" + personId
                    + "\" id=\"" + commentId + "\"><text>" + escapeXml(text) + "</text></threadedComment>"
                    + "</ThreadedComments>";

            PackagePartName threadedCommentsPartName =
                    PackagingURIHelper.createPartName("/xl/threadedComments/threadedComment1.xml");
            PackagePart threadedCommentsPart = workbook.getPackage()
                    .createPart(threadedCommentsPartName, "application/vnd.ms-excel.threadedcomments+xml");
            writePart(threadedCommentsPart, threadedCommentsXml);

            PackagePartName personsPartName = PackagingURIHelper.createPartName("/xl/persons/person.xml");
            PackagePart personsPart = workbook.getPackage()
                    .createPart(personsPartName, "application/vnd.ms-excel.person+xml");
            writePart(personsPart, personsXml);

            PackagePart sheetPart = sheet.getPackagePart();
            sheetPart.addRelationship(threadedCommentsPartName, TargetMode.INTERNAL,
                    "http://schemas.microsoft.com/office/2017/10/relationships/threadedComment", null);
            workbook.getPackagePart().addRelationship(personsPartName, TargetMode.INTERNAL,
                    "http://schemas.microsoft.com/office/2017/10/relationships/person", null);
        } catch (Exception e) {
            // Best-effort fixture enrichment: if the low-level OPC wiring above ever breaks against a
            // future POI version, degrade to "no threaded comment" rather than fail every import test.
            System.err.println("MessyWorkbookBuilder: could not attach threaded comment (non-fatal): " + e);
        }
    }

    private static void writePart(PackagePart part, String xml) throws IOException {
        try (OutputStream os = part.getOutputStream()) {
            os.write(xml.getBytes(StandardCharsets.UTF_8));
        }
    }

    private static String escapeXml(String text) {
        return text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }

    private static List<ParticipantSource> readParticipantSources() throws IOException {
        Path csvPath = Path.of("..", "test-data", "datasets", "large-120", "participants.csv");
        List<String> lines = Files.readAllLines(csvPath, StandardCharsets.UTF_8);
        List<ParticipantSource> sources = new ArrayList<>();
        for (int i = 1; i < lines.size(); i++) { // Skip the header line.
            String[] cols = lines.get(i).split(",", -1);
            sources.add(new ParticipantSource(cols[0], cols[1], cols[2], cols[3], cols[4], cols[5]));
        }
        return sources;
    }
}
