package se.klubb.groupplanner.importer.parse;

import java.io.IOException;
import java.io.InputStream;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.List;
import org.apache.poi.UnsupportedFileFormatException;
import org.apache.poi.ooxml.POIXMLException;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.CellValue;
import org.apache.poi.ss.usermodel.DataFormatter;
import org.apache.poi.ss.usermodel.DateUtil;
import org.apache.poi.ss.usermodel.FormulaEvaluator;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;

/**
 * Parses an {@code .xlsx} workbook into a {@link ParsedWorkbook}: every sheet, every cell,
 * preserving typed values for numeric/date/time cells (docs/plan.md red-team correction) while
 * rendering a locale-formatted display string via {@link DataFormatter} for every cell.
 *
 * <p>Cell comments and threaded comments are simply never read - POI's cell-value APIs used here
 * (0-based row/column iteration, {@code getCellValue}/{@code getNumericCellValue}/...) are entirely
 * unaffected by their presence, so a workbook carrying either kind of comment parses identically to
 * one without (M3 brief: "tolerate cell comments/threaded comments/merged cells - never crash").
 * Likewise, merged cells are read as POI naturally exposes them (a real value in the top-left cell,
 * blank for the rest of the merged region) - no special-casing needed, and no crash either way.
 *
 * <p>Every cell read is wrapped so a single malformed cell (e.g. a formula POI cannot evaluate)
 * degrades to {@link CellType#ERROR} rather than aborting the whole parse.
 */
public final class XlsxParser {

    private static final java.util.Locale SV_SE = java.util.Locale.forLanguageTag("sv-SE");

    private XlsxParser() {
    }

    public static ParsedWorkbook parse(InputStream inputStream) throws IOException {
        try (XSSFWorkbook workbook = new XSSFWorkbook(inputStream)) {
            DataFormatter dataFormatter = new DataFormatter(SV_SE);
            FormulaEvaluator evaluator = workbook.getCreationHelper().createFormulaEvaluator();

            List<ParsedSheet> sheets = new ArrayList<>();
            for (int sheetIndex = 0; sheetIndex < workbook.getNumberOfSheets(); sheetIndex++) {
                Sheet sheet = workbook.getSheetAt(sheetIndex);
                sheets.add(parseSheet(sheet, dataFormatter, evaluator));
            }
            return new ParsedWorkbook(sheets);
        } catch (POIXMLException | UnsupportedFileFormatException e) {
            // A text/binary file renamed .xlsx, or a corrupt OOXML zip: a user upload problem, not
            // a server error. Rewrapped as IllegalArgumentException so ImportSessionService maps it
            // to a 400 rather than the generic 500 handler (M3 review finding 7).
            // (UnsupportedFileFormatException - the NotOfficeXmlFileException/EmptyFileException
            // family - already extends IllegalArgumentException; caught here anyway so both failure
            // modes carry the same uniform message.)
            throw new IllegalArgumentException(
                    "Filen kunde inte läsas som .xlsx (skadad eller fel format): " + e.getMessage(), e);
        }
    }

    private static ParsedSheet parseSheet(Sheet sheet, DataFormatter dataFormatter, FormulaEvaluator evaluator) {
        List<List<ParsedCell>> rows = new ArrayList<>();
        int lastRowNum = sheet.getLastRowNum(); // 0-based index of the last row containing data, or -1 if empty.
        for (int rowIndex = 0; rowIndex <= lastRowNum; rowIndex++) {
            Row row = sheet.getRow(rowIndex);
            rows.add(parseRow(row, dataFormatter, evaluator));
        }
        return new ParsedSheet(sheet.getSheetName(), rows);
    }

    private static List<ParsedCell> parseRow(Row row, DataFormatter dataFormatter, FormulaEvaluator evaluator) {
        if (row == null) {
            return List.of();
        }
        List<ParsedCell> cells = new ArrayList<>();
        int lastCellNum = row.getLastCellNum(); // 1-based exclusive bound, or -1 if the row has no cells.
        for (int columnIndex = 0; columnIndex < lastCellNum; columnIndex++) {
            Cell cell = row.getCell(columnIndex);
            cells.add(safeParseCell(cell, dataFormatter, evaluator));
        }
        return cells;
    }

    private static ParsedCell safeParseCell(Cell cell, DataFormatter dataFormatter, FormulaEvaluator evaluator) {
        try {
            return parseCell(cell, dataFormatter, evaluator);
        } catch (RuntimeException e) {
            // Never let one malformed cell abort the whole import (M3 brief: "never crash").
            String fallback;
            try {
                fallback = dataFormatter.formatCellValue(cell);
            } catch (RuntimeException ignored) {
                fallback = "";
            }
            return new ParsedCell(fallback == null ? "" : fallback, null, CellType.ERROR);
        }
    }

    private static ParsedCell parseCell(Cell cell, DataFormatter dataFormatter, FormulaEvaluator evaluator) {
        if (cell == null) {
            return ParsedCell.blank();
        }

        String rawString = dataFormatter.formatCellValue(cell, evaluator);

        org.apache.poi.ss.usermodel.CellType effectiveType = cell.getCellType();
        if (effectiveType == org.apache.poi.ss.usermodel.CellType.FORMULA) {
            CellValue evaluated = evaluator.evaluate(cell);
            if (evaluated == null) {
                return new ParsedCell(rawString, null, CellType.ERROR);
            }
            return switch (evaluated.getCellType()) {
                case NUMERIC -> numericOrDateCell(cell, rawString, evaluated.getNumberValue());
                case STRING -> new ParsedCell(rawString, evaluated.getStringValue(), CellType.STRING);
                case BOOLEAN -> new ParsedCell(rawString, evaluated.getBooleanValue(), CellType.BOOLEAN);
                case BLANK -> ParsedCell.blank();
                default -> new ParsedCell(rawString, null, CellType.ERROR);
            };
        }

        return switch (effectiveType) {
            case NUMERIC -> numericOrDateCell(cell, rawString, cell.getNumericCellValue());
            case STRING -> new ParsedCell(rawString, cell.getStringCellValue(), CellType.STRING);
            case BOOLEAN -> new ParsedCell(rawString, cell.getBooleanCellValue(), CellType.BOOLEAN);
            case BLANK -> ParsedCell.blank();
            default -> new ParsedCell(rawString, null, CellType.ERROR);
        };
    }

    /**
     * Distinguishes a genuine calendar date from a time-of-day-only value (both are represented as
     * an xlsx "date-formatted" numeric cell). A value whose integer (day) part is zero is a pure
     * time-of-day (e.g. Excel's {@code 18:00:00} stores as {@code 0.75}); anything else is treated
     * as a calendar date - see {@link ParsedCell} for why {@code typedValue} only ever carries
     * {@link LocalDate} or {@link LocalTime}, never {@link LocalDateTime}.
     */
    private static ParsedCell numericOrDateCell(Cell cell, String rawString, double numericValue) {
        if (!DateUtil.isCellDateFormatted(cell)) {
            return new ParsedCell(rawString, numericValue, CellType.NUMERIC);
        }
        LocalDateTime dateTime = DateUtil.getLocalDateTime(numericValue);
        if (Math.floor(numericValue) == 0.0) {
            return new ParsedCell(rawString, dateTime.toLocalTime(), CellType.TIME);
        }
        return new ParsedCell(rawString, dateTime.toLocalDate(), CellType.DATE);
    }
}
