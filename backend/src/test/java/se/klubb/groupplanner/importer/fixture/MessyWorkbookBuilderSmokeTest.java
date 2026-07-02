package se.klubb.groupplanner.importer.fixture;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.ByteArrayInputStream;
import org.junit.jupiter.api.Test;
import se.klubb.groupplanner.importer.parse.ParsedSheet;
import se.klubb.groupplanner.importer.parse.ParsedWorkbook;
import se.klubb.groupplanner.importer.parse.XlsxParser;

class MessyWorkbookBuilderSmokeTest {

    @Test
    void buildsAndParsesWithoutCrashing() throws Exception {
        MessyWorkbookBuilder.BuiltWorkbook built = MessyWorkbookBuilder.build();
        assertThat(built.bytes()).isNotEmpty();

        ParsedWorkbook workbook = XlsxParser.parse(new ByteArrayInputStream(built.bytes()));
        assertThat(workbook.sheets()).hasSize(1);
        ParsedSheet sheet = workbook.sheets().get(0);
        assertThat(sheet.name()).isEqualTo(MessyWorkbookBuilder.SHEET_NAME);

        int p001Row = built.row("p001");
        assertThat(sheet.cellAt(p001Row, 1).rawString()).isEqualTo("Johan");
        assertThat(sheet.cellAt(p001Row, 2).rawString()).isEqualTo("Johansson");

        int p003Row = built.row("p003");
        assertThat(sheet.cellAt(p003Row, 2).rawString()).isEqualTo("Söderberg");
    }
}
