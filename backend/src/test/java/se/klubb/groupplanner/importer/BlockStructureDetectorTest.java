package se.klubb.groupplanner.importer;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.ByteArrayInputStream;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import se.klubb.groupplanner.importer.BlockStructureDetector.BlockStructure;
import se.klubb.groupplanner.importer.BlockStructureDetector.RowClass;
import se.klubb.groupplanner.importer.fixture.GroupedExportWorkbookBuilder;
import se.klubb.groupplanner.importer.fixture.MessyWorkbookBuilder;
import se.klubb.groupplanner.importer.parse.ParsedCell;
import se.klubb.groupplanner.importer.parse.ParsedSheet;
import se.klubb.groupplanner.importer.parse.ParsedWorkbook;
import se.klubb.groupplanner.importer.parse.XlsxParser;

/** WP1: {@link BlockStructureDetector} - Layout 1 (this app's own grouped export) and Layout 2 (the
 *  messy council layout), plus the conservative negative guardrails. */
class BlockStructureDetectorTest {

    // -----------------------------------------------------------------------------------------
    // Layout 1 - repeated headers (this app's own GroupedXlsxWriter export).
    // -----------------------------------------------------------------------------------------

    @Test
    void detectsRepeatedHeaderBlocksInTheGroupedExport() throws Exception {
        ParsedSheet sheet = parseSingleSheet(GroupedExportWorkbookBuilder.build());
        Optional<BlockStructure> detected = BlockStructureDetector.detect(sheet, 0);
        assertThat(detected).isPresent();
        BlockStructure bs = detected.get();
        assertThat(bs.blockCount()).isEqualTo(3);

        int astrid = findRow(sheet, "Astrid Svensson");
        int bengt = findRow(sheet, "Bengt Karlsson");
        int cecilia = findRow(sheet, "Cecilia Nilsson");
        assertThat(bs.groupNameByRow().get(astrid)).isEqualTo("Torsdagsträning 1");
        assertThat(bs.groupNameByRow().get(bengt)).isEqualTo("Torsdagsträning 1");
        assertThat(bs.groupNameByRow().get(cecilia)).isEqualTo("Torsdagsträning 1");
        assertThat(bs.classByRow().get(astrid)).isEqualTo(RowClass.PLAYER);

        int david = findRow(sheet, "David Eriksson");
        assertThat(bs.groupNameByRow().get(david)).isEqualTo("Torsdagsträning 2");

        int greta = findRow(sheet, "Greta Persson");
        assertThat(bs.groupNameByRow().get(greta)).isEqualTo("Torsdagsträning 3");

        // Heading / metadata / header / count rows -> STRUCTURE.
        int heading = findRow(sheet, "Torsdagsträning 1");
        assertThat(bs.classByRow().get(heading)).isEqualTo(RowClass.STRUCTURE);
        int ordning = findRow(sheet, "Ordning: 1");
        assertThat(bs.classByRow().get(ordning)).isEqualTo(RowClass.STRUCTURE);
        int tid = findRow(sheet, "Tid: 18:00");
        assertThat(bs.classByRow().get(tid)).isEqualTo(RowClass.STRUCTURE);
        int tranare = findRow(sheet, "Tränare: Anna Andersson");
        assertThat(bs.classByRow().get(tranare)).isEqualTo(RowClass.STRUCTURE);
        int header = findRow(sheet, "Namn");
        assertThat(bs.classByRow().get(header)).isEqualTo(RowClass.STRUCTURE);
        int count = findRow(sheet, "Antal spelare: 3");
        assertThat(bs.classByRow().get(count)).isEqualTo(RowClass.STRUCTURE);

        // Kölista (waitlist) rows -> PLAYER but no previous group.
        int jonas = findRow(sheet, "Jonas Karlsson");
        assertThat(bs.classByRow().get(jonas)).isEqualTo(RowClass.PLAYER);
        assertThat(bs.groupNameByRow().get(jonas)).isNull();
        int klara = findRow(sheet, "Klara Hansson");
        assertThat(bs.groupNameByRow().get(klara)).isNull();
    }

    @Test
    void hybridFileWithColumnAMetadataInsideRepeatedHeaderBlocksKeepsEveryPlayerRow() {
        // Adversarial review MAJOR 1: a file that combines Layout 1's single-cell headings + repeated
        // headers with column-A metadata on the player rows themselves (the messy layout's own shape)
        // must not silently drop players just because their first cell looks like "18:00"/"Tränare: X".
        ParsedSheet sheet = sheetOf(
                List.of(cell("Grupp Alpha")),
                List.of(cell(""), cell("Förnamn"), cell("Efternamn"), cell("Rank")),
                List.of(cell("18:00"), cell("Nils"), cell("Åström"), cell("900")),
                List.of(cell("Tränare: Frida"), cell("Eva"), cell("Berg"), cell("850")),
                List.of(),
                List.of(cell("Grupp Beta")),
                List.of(cell(""), cell("Förnamn"), cell("Efternamn"), cell("Rank")),
                List.of(cell("19:30"), cell("Ola"), cell("Nord"), cell("700")),
                List.of(cell("Tränare: Kalle"), cell("Siri"), cell("Holm"), cell("650")));

        Optional<BlockStructure> detected = BlockStructureDetector.detect(sheet, 1);
        assertThat(detected).isPresent();
        BlockStructure bs = detected.get();
        assertThat(bs.blockCount()).isEqualTo(2);

        assertThat(bs.classByRow().get(2)).isEqualTo(RowClass.PLAYER);
        assertThat(bs.classByRow().get(3)).isEqualTo(RowClass.PLAYER);
        assertThat(bs.groupNameByRow().get(2)).isEqualTo("Grupp Alpha");
        assertThat(bs.groupNameByRow().get(3)).isEqualTo("Grupp Alpha");

        assertThat(bs.classByRow().get(7)).isEqualTo(RowClass.PLAYER);
        assertThat(bs.classByRow().get(8)).isEqualTo(RowClass.PLAYER);
        assertThat(bs.groupNameByRow().get(7)).isEqualTo("Grupp Beta");
        assertThat(bs.groupNameByRow().get(8)).isEqualTo("Grupp Beta");
    }

    // -----------------------------------------------------------------------------------------
    // Layout 2 - column-A metadata stack (the messy council layout).
    // -----------------------------------------------------------------------------------------

    @Test
    void detectsColumnAMetadataStackBlocksInTheMessyFixture() throws Exception {
        MessyWorkbookBuilder.BuiltWorkbook built = MessyWorkbookBuilder.build();
        ParsedWorkbook workbook = XlsxParser.parse(new ByteArrayInputStream(built.bytes()));
        ParsedSheet sheet = workbook.sheets().get(0);

        Optional<BlockStructure> detected = BlockStructureDetector.detect(sheet, built.headerRowIndex());
        assertThat(detected).isPresent();
        BlockStructure bs = detected.get();
        assertThat(bs.blockCount()).isEqualTo(3);

        for (String label : List.of("p001", "p002", "p003", "p004")) {
            assertThat(bs.groupNameByRow().get(built.row(label))).as(label).isEqualTo("Grupp 1");
        }
        for (String label : List.of("p005", "p006", "p007", "p008")) {
            assertThat(bs.groupNameByRow().get(built.row(label))).as(label).isEqualTo("Grupp 2");
        }
        for (String label : List.of("p011", "p012", "p013", "p014")) {
            assertThat(bs.groupNameByRow().get(built.row(label))).as(label).isEqualTo("Grupp 3");
        }

        assertThat(bs.classByRow().get(built.row("group1CountRow"))).isEqualTo(RowClass.STRUCTURE);
        assertThat(bs.classByRow().get(built.row("group2CountRow"))).isEqualTo(RowClass.STRUCTURE);
        assertThat(bs.classByRow().get(built.row("group3CountRow"))).isEqualTo(RowClass.STRUCTURE);

        assertThat(bs.classByRow().get(built.row("kolistaHeaderRow"))).isEqualTo(RowClass.STRUCTURE);
        assertThat(bs.classByRow().get(built.row("utanforHeaderRow"))).isEqualTo(RowClass.STRUCTURE);
        assertThat(bs.groupNameByRow().get(built.row("p015"))).isNull();
        assertThat(bs.groupNameByRow().get(built.row("p006Duplicate"))).isNull();
        assertThat(bs.groupNameByRow().get(built.row("p016"))).isNull();
    }

    @Test
    void dotFormattedTimeStillCountsAsATimeCategoryAndQualifiesTheBlock() {
        // MINOR 2: normalize() turns '.' into a space ("18.00" -> "18 00"), so time-likeness must be
        // tested against the RAW cell text; "18.00" + "Tränare: X" is 2 distinct categories (time,
        // tränare) with no count row and no "Grupp N" label - qualifies via categories, label via (b).
        ParsedSheet sheet = sheetOf(
                List.of(cell("Förnamn"), cell("Efternamn"), cell("Rank")),
                List.of(cell("Blå 1"), cell("Nils"), cell("Åström"), cell("900")),
                List.of(cell("18.00"), cell("Eva"), cell("Berg"), cell("850")),
                List.of(cell("Tränare: Frida"), cell("Ola"), cell("Nord"), cell("700")),
                List.of(),
                List.of(cell("Röd 2"), cell("Siri"), cell("Holm"), cell("650")),
                List.of(cell("19.30"), cell("Bo"), cell("Berg"), cell("600")),
                List.of(cell("Tränare: Kalle"), cell("Ida"), cell("Sund"), cell("550")));

        Optional<BlockStructure> detected = BlockStructureDetector.detect(sheet, 0);
        assertThat(detected).isPresent();
        assertThat(detected.get().blockCount()).isEqualTo(2);
        assertThat(detected.get().groupNameByRow().get(1)).isEqualTo("Blå 1");
        assertThat(detected.get().groupNameByRow().get(5)).isEqualTo("Röd 2");
    }

    @Test
    void countRowOnlyQualificationWithNoConfidentLabelYieldsPlayerRowsWithNoGroup() {
        // MINOR 3: a run that qualifies ONLY via a count row (col-0 has < 2 recognized metadata
        // categories) must not fall back to labeling the block with a player's own first name.
        ParsedSheet sheet = sheetOf(
                List.of(cell("Förnamn"), cell("Efternamn"), cell("Rank")),
                List.of(cell("Nils"), cell("Åström"), cell("900")),
                List.of(cell("Eva"), cell("Berg"), cell("850")),
                List.of(cell("2 spelare")),
                List.of(),
                List.of(cell("Ola"), cell("Nord"), cell("700")),
                List.of(cell("Siri"), cell("Holm"), cell("650")),
                List.of(cell("2 spelare")));

        Optional<BlockStructure> detected = BlockStructureDetector.detect(sheet, 0);
        // No confident (a)/(b) label anywhere in either run - both runs are recognized (PLAYER rows,
        // count rows STRUCTURE) but never accumulate a usable block label, so overall detection must
        // stay conservative (no blocks reached the MIN_BLOCK_COUNT threshold via a real label).
        assertThat(detected).isEmpty();
    }

    @Test
    void firstRepeatedHeaderRowFindsTheEarliestRepeatedPlayerHeaderSignature() throws Exception {
        ParsedSheet sheet = parseSingleSheet(GroupedExportWorkbookBuilder.build());
        int header = findRow(sheet, "Namn");

        assertThat(BlockStructureDetector.firstRepeatedHeaderRow(sheet)).contains(header);
        // The width-1 "Torsdagsträning 1" heading row comes first in the sheet but is not itself a
        // repeated player-header row - it must never be returned.
        assertThat(BlockStructureDetector.firstRepeatedHeaderRow(sheet)).isNotEqualTo(Optional.of(0));
    }

    // -----------------------------------------------------------------------------------------
    // Guardrails - conservative negatives.
    // -----------------------------------------------------------------------------------------

    @Test
    void flatSheetWithASingleHeaderYieldsNoBlockStructure() {
        ParsedSheet sheet = sheetOf(
                List.of(cell("Förnamn"), cell("Efternamn"), cell("Rank"), cell("Epost")),
                List.of(cell("Nils"), cell("Åström"), cell("940"), cell("nils@example.se")),
                List.of(cell("Eva"), cell("Berg"), cell("820"), cell("eva@example.se")),
                List.of(cell("Ola"), cell("Nord"), cell("700"), cell("ola@example.se")));

        assertThat(BlockStructureDetector.detect(sheet, 0)).isEmpty();
    }

    @Test
    void flatSheetWithStrayBlankRowsYieldsNoBlockStructure() {
        ParsedSheet sheet = sheetOf(
                List.of(cell("Förnamn"), cell("Efternamn"), cell("Rank"), cell("Epost")),
                List.of(cell("Nils"), cell("Åström"), cell("940"), cell("nils@example.se")),
                List.of(),
                List.of(cell("Eva"), cell("Berg"), cell("820"), cell("eva@example.se")),
                List.of(),
                List.of(cell("Ola"), cell("Nord"), cell("700"), cell("ola@example.se")));

        assertThat(BlockStructureDetector.detect(sheet, 0)).isEmpty();
    }

    @Test
    void aSingleColumnAMetadataBlockYieldsNoBlockStructure() {
        ParsedSheet sheet = sheetOf(
                List.of(cell("Förnamn"), cell("Efternamn"), cell("Rank")),
                List.of(cell("Grupp 1"), cell("Nils"), cell("Åström"), cell("940")),
                List.of(cell("18:00"), cell("Eva"), cell("Berg"), cell("820")),
                List.of(cell("Tränare: Frida"), cell("Ola"), cell("Nord"), cell("700")),
                List.of(cell("3 spelare")));

        assertThat(BlockStructureDetector.detect(sheet, 0)).isEmpty();
    }

    // -----------------------------------------------------------------------------------------

    private static ParsedSheet parseSingleSheet(byte[] xlsxBytes) throws Exception {
        ParsedWorkbook workbook = XlsxParser.parse(new ByteArrayInputStream(xlsxBytes));
        return workbook.sheets().get(0);
    }

    private static int findRow(ParsedSheet sheet, String cellText) {
        for (int r = 0; r < sheet.rowCount(); r++) {
            for (int c = 0; c < sheet.columnCount(); c++) {
                if (cellText.equals(sheet.cellAt(r, c).rawString())) {
                    return r;
                }
            }
        }
        throw new IllegalArgumentException("No row contains cell text: " + cellText);
    }

    @SafeVarargs
    private static ParsedSheet sheetOf(List<ParsedCell>... rows) {
        return new ParsedSheet("Test", List.of(rows));
    }

    private static ParsedCell cell(String text) {
        return ParsedCell.ofString(text);
    }
}
