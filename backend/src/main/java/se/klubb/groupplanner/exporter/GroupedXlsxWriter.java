package se.klubb.groupplanner.exporter;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.ArrayList;
import java.util.List;
import org.apache.poi.ss.usermodel.BorderStyle;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.CellStyle;
import org.apache.poi.ss.usermodel.Font;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.util.WorkbookUtil;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.springframework.stereotype.Component;

/**
 * The "council's own layout" (docs/plan.md Context, spec §20): one sheet per plan; per group, column
 * A stacks {@code Grupp}/{@code Ordning}/{@code Tid}/{@code Tränare} metadata rows, then a player
 * header row and one row per player ({@code Namn, Ranking, Nivåscore, Tidigare grupp[, Kommentar],
 * Varningar}), a player-count row, and a blank separator row before the next group. After every
 * group: a {@code Kölista} section for waitlisted participants + priority.
 *
 * <p>Styling is deliberately minimal (task M8 item 2: "POI styling minimal but readable") — bold
 * group names and column headers, thin borders on the header row only.
 */
@Component
public class GroupedXlsxWriter {

    private static final String[] PLAYER_HEADERS_BASE = {"Namn", "Ranking", "Nivåscore", "Tidigare grupp"};
    private static final String COMMENT_HEADER = "Kommentar";
    private static final String WARNINGS_HEADER = "Varningar";
    private static final String[] WAITLIST_HEADERS = {"Namn", "Ranking", "Nivåscore", "Prioritet"};

    public byte[] write(ExportData data, boolean includeComments) {
        try (XSSFWorkbook workbook = new XSSFWorkbook()) {
            Sheet sheet = workbook.createSheet(sheetName(data));
            CellStyle boldStyle = boldStyle(workbook);
            CellStyle headerStyle = headerStyle(workbook);

            int rowIdx = 0;
            for (ExportGroup group : data.groups()) {
                rowIdx = writeGroupBlock(sheet, rowIdx, group, includeComments, boldStyle, headerStyle);
            }
            writeWaitlistSection(sheet, rowIdx, data.waitlist(), boldStyle, headerStyle);

            for (int c = 0; c < 6; c++) {
                sheet.autoSizeColumn(c);
            }

            ByteArrayOutputStream out = new ByteArrayOutputStream();
            workbook.write(out);
            return out.toByteArray();
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to write grouped xlsx export", e);
        }
    }

    private int writeGroupBlock(Sheet sheet, int rowIdx, ExportGroup group, boolean includeComments, CellStyle bold, CellStyle header) {
        setCell(sheet, rowIdx++, 0, group.name(), bold);
        setCell(sheet, rowIdx++, 0, "Ordning: " + (group.groupOrder() != null ? group.groupOrder() : "-"), null);
        setCell(sheet, rowIdx++, 0, "Tid: " + orDash(group.timeSlotLabel()) + (group.courtName() != null ? " / " + group.courtName() : ""), null);
        setCell(sheet, rowIdx++, 0, "Tränare: " + (group.coachNames() != null ? group.coachNames() : "Ingen tränare"), null);

        List<String> headers = playerHeaders(includeComments);
        Row headerRow = sheet.createRow(rowIdx++);
        for (int c = 0; c < headers.size(); c++) {
            Cell cell = headerRow.createCell(c);
            cell.setCellValue(headers.get(c));
            cell.setCellStyle(header);
        }

        for (ExportPlayer player : group.players()) {
            Row row = sheet.createRow(rowIdx++);
            int c = 0;
            row.createCell(c++).setCellValue(player.displayName());
            setNumericOrBlank(row, c++, player.rankingPoints());
            setNumericOrBlank(row, c++, player.estimatedLevel());
            row.createCell(c++).setCellValue(orEmpty(player.previousGroupName()));
            if (includeComments) {
                row.createCell(c++).setCellValue(orEmpty(player.comment()));
            }
            row.createCell(c).setCellValue(String.join("; ", player.warnings()));
        }

        setCell(sheet, rowIdx++, 0, "Antal spelare: " + group.players().size(), null);
        rowIdx++; // blank separator row between groups.
        return rowIdx;
    }

    private void writeWaitlistSection(Sheet sheet, int rowIdx, List<ExportWaitlistEntry> waitlist, CellStyle bold, CellStyle header) {
        setCell(sheet, rowIdx++, 0, "Kölista", bold);
        Row headerRow = sheet.createRow(rowIdx++);
        for (int c = 0; c < WAITLIST_HEADERS.length; c++) {
            Cell cell = headerRow.createCell(c);
            cell.setCellValue(WAITLIST_HEADERS[c]);
            cell.setCellStyle(header);
        }
        for (ExportWaitlistEntry entry : waitlist) {
            Row row = sheet.createRow(rowIdx++);
            row.createCell(0).setCellValue(entry.displayName());
            setNumericOrBlank(row, 1, entry.rankingPoints());
            setNumericOrBlank(row, 2, entry.estimatedLevel());
            if (entry.priority() != null) {
                row.createCell(3).setCellValue(entry.priority());
            } else {
                row.createCell(3);
            }
        }
        setCell(sheet, rowIdx, 0, "Antal på kölista: " + waitlist.size(), null);
    }

    private static List<String> playerHeaders(boolean includeComments) {
        List<String> headers = new ArrayList<>(List.of(PLAYER_HEADERS_BASE));
        if (includeComments) {
            headers.add(COMMENT_HEADER);
        }
        headers.add(WARNINGS_HEADER);
        return headers;
    }

    private static void setCell(Sheet sheet, int rowIdx, int col, String value, CellStyle style) {
        Row row = sheet.getRow(rowIdx);
        if (row == null) {
            row = sheet.createRow(rowIdx);
        }
        Cell cell = row.createCell(col);
        cell.setCellValue(value);
        if (style != null) {
            cell.setCellStyle(style);
        }
    }

    private static void setNumericOrBlank(Row row, int col, Double value) {
        if (value != null) {
            row.createCell(col).setCellValue(value);
        } else {
            row.createCell(col);
        }
    }

    private static String orEmpty(String s) {
        return s == null ? "" : s;
    }

    private static String orDash(String s) {
        return (s == null || s.isBlank()) ? "-" : s;
    }

    /** Excel sheet names are limited to 31 chars and forbid {@code : \ / ? * [ ]} - {@link
     * WorkbookUtil#createSafeSheetName} handles both. Falls back to the plan name if no category is
     * set. */
    private static String sheetName(ExportData data) {
        String raw = (data.category() != null && !data.category().isBlank()) ? data.category() : data.planName();
        return WorkbookUtil.createSafeSheetName(raw != null ? raw : "Grupper");
    }

    private CellStyle boldStyle(XSSFWorkbook workbook) {
        Font font = workbook.createFont();
        font.setBold(true);
        CellStyle style = workbook.createCellStyle();
        style.setFont(font);
        return style;
    }

    private CellStyle headerStyle(XSSFWorkbook workbook) {
        Font font = workbook.createFont();
        font.setBold(true);
        CellStyle style = workbook.createCellStyle();
        style.setFont(font);
        style.setBorderBottom(BorderStyle.THIN);
        return style;
    }
}
