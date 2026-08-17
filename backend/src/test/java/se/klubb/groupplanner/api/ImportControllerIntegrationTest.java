package se.klubb.groupplanner.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.Path;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import se.klubb.groupplanner.domain.ActivityPlan;
import se.klubb.groupplanner.domain.CoachProfile;
import se.klubb.groupplanner.domain.ParticipantProfile;
import se.klubb.groupplanner.domain.Person;
import se.klubb.groupplanner.domain.SeasonPlan;
import se.klubb.groupplanner.importer.fixture.MessyWorkbookBuilder;
import se.klubb.groupplanner.repo.ActivityPlanRepository;
import se.klubb.groupplanner.repo.CoachProfileRepository;
import se.klubb.groupplanner.repo.ParticipantProfileRepository;
import se.klubb.groupplanner.repo.PersonRepository;
import se.klubb.groupplanner.repo.SeasonPlanRepository;
import se.klubb.groupplanner.util.Uuid7;

/**
 * Full import wizard flow end-to-end (spec §8.3 steps 1-8, docs/plan.md M3 exit criterion): upload
 * the messy fixture -> preview -> header -> columns (suggestions) -> mapping -> validate ->
 * decisions -> commit -> assert DB rows including {@code imported_comment}, group-metadata/blank/
 * kölista rows skipped with reasons, coach import via {@code isCoach}, template saved and
 * auto-suggested on re-upload.
 */
@SpringBootTest
@AutoConfigureMockMvc
class ImportControllerIntegrationTest {

    private static final String VALID_TOKEN = "test-secret-token";

    @TempDir
    static Path dataDir;

    @DynamicPropertySource
    static void appDataDir(DynamicPropertyRegistry registry) {
        registry.add("app.data-dir", () -> dataDir.toString());
    }

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private ObjectMapper objectMapper;
    @Autowired
    private SeasonPlanRepository seasonPlanRepository;
    @Autowired
    private ActivityPlanRepository activityPlanRepository;
    @Autowired
    private PersonRepository personRepository;
    @Autowired
    private ParticipantProfileRepository participantProfileRepository;
    @Autowired
    private CoachProfileRepository coachProfileRepository;
    @Autowired
    private JdbcClient jdbcClient;

    private String createPlan() {
        Instant now = Instant.now();
        SeasonPlan season = seasonPlanRepository.insert(new SeasonPlan(Uuid7.generate(), "VT26", null, null, "active", now, now));
        ActivityPlan plan = activityPlanRepository.insert(
                new ActivityPlan(Uuid7.generate(), season.id(), "Herr", "beach", "draft", null, null, null, null, now, now));
        return plan.id();
    }

    private MockMultipartFile fixtureFile(byte[] bytes) {
        return new MockMultipartFile("file", "fixture.xlsx",
                "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet", bytes);
    }

    @Test
    void fullWizardFlowFromUploadToCommitAndTemplateReuse() throws Exception {
        String planId = createPlan();
        MessyWorkbookBuilder.BuiltWorkbook built = MessyWorkbookBuilder.build();
        String base = "/api/plans/" + planId + "/import";

        // 1. Välj fil.
        String createResponse = mockMvc.perform(multipart(base + "/sessions")
                        .file(fixtureFile(built.bytes()))
                        .header("X-GP-Token", VALID_TOKEN))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.sheets[0].name").value(MessyWorkbookBuilder.SHEET_NAME))
                .andReturn().getResponse().getContentAsString();
        JsonNode createJson = objectMapper.readTree(createResponse);
        String sessionId = createJson.get("sessionId").asText();
        assertThat(createJson.get("sheets").get(0).get("suggestedTemplateId").isNull()).isTrue();

        String sid = base + "/sessions/" + sessionId;

        // 2-3. Förhandsgranska rader.
        mockMvc.perform(get(sid + "/preview")
                        .param("sheet", MessyWorkbookBuilder.SHEET_NAME)
                        .param("rows", "5")
                        .header("X-GP-Token", VALID_TOKEN))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.rows[0][1]").value("Förnamn"));

        // 4. Identifiera header row (also selects the sheet).
        mockMvc.perform(put(sid + "/header")
                        .header("X-GP-Token", VALID_TOKEN)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new ImportController.HeaderRequest(MessyWorkbookBuilder.SHEET_NAME, 0))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.headerRowIndex").value(0));

        // Column suggestions.
        mockMvc.perform(get(sid + "/columns").header("X-GP-Token", VALID_TOKEN))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.columns[1].headerText").value("Förnamn"))
                .andExpect(jsonPath("$.columns[1].suggestedTarget").value("firstName"))
                .andExpect(jsonPath("$.columns[2].suggestedTarget").value("lastName"))
                .andExpect(jsonPath("$.columns[5].suggestedTarget").value("email"))
                .andExpect(jsonPath("$.columns[6].suggestedTarget").doesNotExist())
                .andExpect(jsonPath("$.columns[10].suggestedTarget").value("externalId"));

        // 5. Mappa kolumner.
        List<ImportController.ColumnMappingDto> mappings = List.of(
                new ImportController.ColumnMappingDto(1, "firstName"),
                new ImportController.ColumnMappingDto(2, "lastName"),
                new ImportController.ColumnMappingDto(3, "rankingPoints"),
                new ImportController.ColumnMappingDto(4, "comment"),
                new ImportController.ColumnMappingDto(5, "email"),
                new ImportController.ColumnMappingDto(6, "customField:cannotTimes"),
                new ImportController.ColumnMappingDto(7, "previousGroupName"),
                new ImportController.ColumnMappingDto(8, "coachName"),
                new ImportController.ColumnMappingDto(9, "isCoach"),
                new ImportController.ColumnMappingDto(10, "externalId"));
        mockMvc.perform(put(sid + "/mapping")
                        .header("X-GP-Token", VALID_TOKEN)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new ImportController.MappingRequest(MessyWorkbookBuilder.SHEET_NAME, mappings))))
                .andExpect(status().isOk());

        // 7. Validera.
        String validateResponse = mockMvc.perform(get(sid + "/validate").header("X-GP-Token", VALID_TOKEN))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        JsonNode validateJson = objectMapper.readTree(validateResponse);
        assertThat(validateJson.get("skipCount").asInt()).isGreaterThan(0);
        assertThat(validateJson.get("warnCount").asInt()).isGreaterThan(0);

        // Decide the within-file name duplicate as "skip this one, keep the other".
        Map<String, ImportController.DecisionDto> decisions = new LinkedHashMap<>();
        decisions.put(String.valueOf(built.row("p006Duplicate")), new ImportController.DecisionDto("SKIP", null));
        mockMvc.perform(put(sid + "/decisions")
                        .header("X-GP-Token", VALID_TOKEN)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(decisions)))
                .andExpect(status().isOk());

        // 8. Importera (+ save as template).
        String commitResponse = mockMvc.perform(post(sid + "/commit")
                        .header("X-GP-Token", VALID_TOKEN)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new ImportController.CommitRequest(true, "Standardimport Herr"))))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        JsonNode commitJson = objectMapper.readTree(commitResponse);
        int imported = commitJson.get("imported").asInt();
        int skipped = commitJson.get("skipped").asInt();
        assertThat(imported).isGreaterThan(0);
        assertThat(skipped).isGreaterThan(0);
        assertThat(commitJson.get("importRunId").asText()).isNotBlank();
        assertThat(commitJson.get("savedTemplateId").asText()).isNotBlank();

        // Privacy regression (CLAUDE.md: comments never reach *_json columns; M3 review finding 3):
        // the import_run audit JSON must not carry any imported-comment text from the fixture.
        String importRunId = commitJson.get("importRunId").asText();
        String auditJson = jdbcClient.sql(
                        "SELECT warnings_json || char(10) || decisions_json AS audit FROM import_run WHERE id = :id")
                .param("id", importRunId)
                .query((rs, rowNum) -> rs.getString("audit"))
                .single();
        assertThat(auditJson).isNotBlank();
        assertThat(auditJson).doesNotContain("Vill helst spela med kompisar");
        assertThat(auditJson).doesNotContain("Ny i klubben, vill gärna ha råd om utrustning");
        assertThat(auditJson).doesNotContain("vill gärna ha råd");

        // --- Assert DB state. ---
        // imported_comment landed for p001's comment.
        Person johan = findPersonByEmail("johan.johansson1@example.se");
        ParticipantProfile johanProfile = participantProfileRepository.findByPersonIdAndActivityPlanId(johan.id(), planId).orElseThrow();
        assertThat(johanProfile.importedComment()).isEqualTo("Vill helst spela med kompisar");
        assertThat(johanProfile.rankingPoints()).isEqualTo(940.0);

        // docs/plan.md M4 row: LevelService recompute auto-runs after every import commit - johan
        // has rankingPoints but no manualLevelScore, so estimatedLevel = rankingPoints (identity
        // mapping) at MEDIUM confidence (0.6).
        assertThat(johanProfile.estimatedLevel()).isEqualTo(940.0);
        assertThat(johanProfile.levelConfidence()).isEqualTo(0.6);

        // The coach-import row created a coach_profile, not a participant_profile.
        Person coach = findPersonByEmail("coach.persson@example.se");
        assertThat(coach.canBeCoach()).isTrue();
        assertThat(coachProfileRepository.findByPersonIdAndActivityPlanId(coach.id(), planId)).isPresent();
        assertThat(participantProfileRepository.findByPersonIdAndActivityPlanId(coach.id(), planId)).isEmpty();

        // Group-metadata/blank/kölista annotation rows never created a person.
        assertThat(personRepository.findAll().stream().anyMatch(p -> "4 spelare".equals(p.firstName()))).isFalse();
        assertThat(personRepository.findAll().stream().anyMatch(p -> "Kölista".equals(p.firstName()))).isFalse();

        // The skipped duplicate row did not create a second "Alma Ekström".
        long almaCount = personRepository.findAll().stream()
                .filter(p -> "Alma".equalsIgnoreCase(p.firstName()) && "Ekström".equalsIgnoreCase(p.lastName()))
                .count();
        assertThat(almaCount).isEqualTo(1);

        // --- Re-upload the same fixture: the saved template must be auto-suggested by header hash. ---
        String reuploadResponse = mockMvc.perform(multipart(base + "/sessions")
                        .file(fixtureFile(built.bytes()))
                        .header("X-GP-Token", VALID_TOKEN))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        JsonNode reuploadJson = objectMapper.readTree(reuploadResponse);
        assertThat(reuploadJson.get("sheets").get(0).get("suggestedTemplateName").asText()).isEqualTo("Standardimport Herr");

        mockMvc.perform(get("/api/import/templates").header("X-GP-Token", VALID_TOKEN))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].name").value("Standardimport Herr"));
    }

    @Test
    void groupedExportUploadExposesSyntheticBlockGroupColumnAutoSuggested() throws Exception {
        // WP1: this app's own council-layout export re-uploaded must be recognized as Layout 1
        // (repeated headers), default to the REAL repeated player-header row (not the width-1 group-
        // heading row - adversarial review item 6), auto-suggest every real column from its header
        // text, and offer the synthetic "Grupp i filen" column WITHOUT auto-suggesting it (a real
        // "Tidigare grupp" column already claims previousGroupName here).
        String planId = createPlan();
        byte[] bytes = se.klubb.groupplanner.importer.fixture.GroupedExportWorkbookBuilder.build();
        String base = "/api/plans/" + planId + "/import";

        String createResponse = mockMvc.perform(multipart(base + "/sessions")
                        .file(new MockMultipartFile("file", "export.xlsx",
                                "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet", bytes))
                        .header("X-GP-Token", VALID_TOKEN))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        JsonNode createJson = objectMapper.readTree(createResponse);
        String sessionId = createJson.get("sessionId").asText();
        String sheetName = createJson.get("sheets").get(0).get("name").asText();
        String sid = base + "/sessions/" + sessionId;

        // Read the DEFAULT header row (via preview) rather than hardcoding one - it must have already
        // landed on the real "Namn/Ranking/..." row, not row 0's "Torsdagsträning 1" heading.
        String previewResponse = mockMvc.perform(get(sid + "/preview")
                        .param("sheet", sheetName).param("rows", "30")
                        .header("X-GP-Token", VALID_TOKEN))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        int defaultHeaderRow = objectMapper.readTree(previewResponse).get("headerRowIndex").asInt();
        assertThat(defaultHeaderRow).as("default header must skip past the width-1 heading row").isGreaterThan(0);

        mockMvc.perform(put(sid + "/header")
                        .header("X-GP-Token", VALID_TOKEN)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new ImportController.HeaderRequest(sheetName, defaultHeaderRow))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.headerRowIndex").value(defaultHeaderRow));

        String columnsResponse = mockMvc.perform(get(sid + "/columns").header("X-GP-Token", VALID_TOKEN))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        JsonNode columnsJson = objectMapper.readTree(columnsResponse);

        // Real columns auto-suggested from their header text - GroupedXlsxWriter's own header order
        // is Namn, Ranking, Nivåscore, Tidigare grupp, Varningar (no Kommentar - includeComments=false).
        assertThat(findColumnByIndex(columnsJson, 0).get("headerText").asText()).isEqualTo("Namn");
        assertThat(findColumnByIndex(columnsJson, 0).get("suggestedTarget").asText()).isEqualTo("displayName");
        assertThat(findColumnByIndex(columnsJson, 1).get("headerText").asText()).isEqualTo("Ranking");
        assertThat(findColumnByIndex(columnsJson, 1).get("suggestedTarget").asText()).isEqualTo("rankingPoints");
        assertThat(findColumnByIndex(columnsJson, 2).get("headerText").asText()).isEqualTo("Nivåscore");
        assertThat(findColumnByIndex(columnsJson, 2).get("suggestedTarget").asText()).isEqualTo("manualLevelScore");
        assertThat(findColumnByIndex(columnsJson, 3).get("headerText").asText()).isEqualTo("Tidigare grupp");
        assertThat(findColumnByIndex(columnsJson, 3).get("suggestedTarget").asText()).isEqualTo("previousGroupName");

        JsonNode syntheticColumn = findColumnByIndex(columnsJson, -1);
        assertThat(syntheticColumn).isNotNull();
        assertThat(syntheticColumn.get("synthetic").asBoolean()).isTrue();
        assertThat(syntheticColumn.get("headerText").asText()).isEqualTo("Grupp i filen");
        assertThat(syntheticColumn.get("suggestedTarget").isNull())
                .as("must not double-suggest previousGroupName - the real 'Tidigare grupp' column already does").isTrue();

        // The wizard's default mapping (every real column auto-suggested, the synthetic one left
        // unmapped) would import previousGroupName from the real (already-populated) column. This
        // test instead explicitly overrides to the synthetic block-group source, proving that
        // mechanism end to end: map columnIndex -1 -> previousGroupName instead of the real column 3.
        List<ImportController.ColumnMappingDto> mappings = List.of(
                new ImportController.ColumnMappingDto(0, "displayName"),
                new ImportController.ColumnMappingDto(-1, "previousGroupName"));
        mockMvc.perform(put(sid + "/mapping")
                        .header("X-GP-Token", VALID_TOKEN)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new ImportController.MappingRequest(sheetName, mappings))))
                .andExpect(status().isOk());

        String validateResponse = mockMvc.perform(get(sid + "/validate").header("X-GP-Token", VALID_TOKEN))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        JsonNode validateJson = objectMapper.readTree(validateResponse);
        boolean anyStructureSkip = false;
        for (JsonNode row : validateJson.get("rows")) {
            for (JsonNode reason : row.get("reasons")) {
                if (reason.asText().equals("Strukturrad (rubrik/metadata)")) {
                    anyStructureSkip = true;
                }
            }
        }
        assertThat(anyStructureSkip).isTrue();

        String commitResponse = mockMvc.perform(post(sid + "/commit")
                        .header("X-GP-Token", VALID_TOKEN)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new ImportController.CommitRequest(false, null))))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        JsonNode commitJson = objectMapper.readTree(commitResponse);
        assertThat(commitJson.get("imported").asInt()).isGreaterThan(0);
        assertThat(commitJson.get("warnings").toString()).contains("Tidigare grupp hämtades från filens gruppstruktur");

        // Astrid's REAL "Tidigare grupp" column value ("Torsdagsträning 1") happens to agree with her
        // actual block ("Torsdagsträning 1") - the synthetic mapping we chose reproduces it exactly.
        Person astrid = personRepository.findAll().stream()
                .filter(p -> "Astrid Svensson".equals(p.displayName()))
                .findFirst().orElseThrow();
        ParticipantProfile astridProfile =
                participantProfileRepository.findByPersonIdAndActivityPlanId(astrid.id(), planId).orElseThrow();
        assertThat(astridProfile.previousGroupName()).isEqualTo("Torsdagsträning 1");
    }

    @Test
    void blockGroupColumnMappedToIgnoreProducesNoBlockStructureProvenanceWarning() throws Exception {
        // Adversarial review MAJOR 2: MappingStep sends every column, defaulting unsuggested ones to
        // "ignore" - a -1 -> ignore mapping must NOT be mistaken for "the -1 -> previousGroupName
        // mapping was used" and must not produce the block-structure provenance warning.
        String planId = createPlan();
        byte[] bytes = se.klubb.groupplanner.importer.fixture.GroupedExportWorkbookBuilder.build();
        String base = "/api/plans/" + planId + "/import";

        String createResponse = mockMvc.perform(multipart(base + "/sessions")
                        .file(new MockMultipartFile("file", "export.xlsx",
                                "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet", bytes))
                        .header("X-GP-Token", VALID_TOKEN))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        JsonNode createJson = objectMapper.readTree(createResponse);
        String sessionId = createJson.get("sessionId").asText();
        String sheetName = createJson.get("sheets").get(0).get("name").asText();
        String sid = base + "/sessions/" + sessionId;

        String previewResponse = mockMvc.perform(get(sid + "/preview")
                        .param("sheet", sheetName).param("rows", "30")
                        .header("X-GP-Token", VALID_TOKEN))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        int defaultHeaderRow = objectMapper.readTree(previewResponse).get("headerRowIndex").asInt();
        mockMvc.perform(put(sid + "/header")
                        .header("X-GP-Token", VALID_TOKEN)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new ImportController.HeaderRequest(sheetName, defaultHeaderRow))))
                .andExpect(status().isOk());

        List<ImportController.ColumnMappingDto> mappings = List.of(
                new ImportController.ColumnMappingDto(0, "displayName"),
                new ImportController.ColumnMappingDto(-1, "ignore"));
        mockMvc.perform(put(sid + "/mapping")
                        .header("X-GP-Token", VALID_TOKEN)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new ImportController.MappingRequest(sheetName, mappings))))
                .andExpect(status().isOk());

        String commitResponse = mockMvc.perform(post(sid + "/commit")
                        .header("X-GP-Token", VALID_TOKEN)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new ImportController.CommitRequest(false, null))))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        JsonNode commitJson = objectMapper.readTree(commitResponse);
        assertThat(commitJson.get("imported").asInt()).isGreaterThan(0);
        assertThat(commitJson.get("warnings").toString()).doesNotContain("Tidigare grupp hämtades från filens gruppstruktur");
    }

    @Test
    void messyFixtureExposesSyntheticColumnButDoesNotAutoSuggestItWhenARealColumnAlreadyDoes() throws Exception {
        // The messy fixture has a real "Tidigare grupp" column (index 7) that already suggests
        // previousGroupName - the synthetic block-group column must still be offered (Layout 2 is
        // detectable in this fixture too) but must NOT also be auto-suggested for the same target.
        String planId = createPlan();
        MessyWorkbookBuilder.BuiltWorkbook built = MessyWorkbookBuilder.build();
        String base = "/api/plans/" + planId + "/import";

        String createResponse = mockMvc.perform(multipart(base + "/sessions")
                        .file(fixtureFile(built.bytes()))
                        .header("X-GP-Token", VALID_TOKEN))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        String sessionId = objectMapper.readTree(createResponse).get("sessionId").asText();
        String sid = base + "/sessions/" + sessionId;

        mockMvc.perform(put(sid + "/header")
                        .header("X-GP-Token", VALID_TOKEN)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new ImportController.HeaderRequest(MessyWorkbookBuilder.SHEET_NAME, 0))))
                .andExpect(status().isOk());

        String columnsResponse = mockMvc.perform(get(sid + "/columns").header("X-GP-Token", VALID_TOKEN))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        JsonNode columnsJson = objectMapper.readTree(columnsResponse);
        assertThat(columnsJson.get("columns").get(7).get("suggestedTarget").asText()).isEqualTo("previousGroupName");

        JsonNode syntheticColumn = findColumnByIndex(columnsJson, -1);
        assertThat(syntheticColumn).as("a synthetic column should still be offered (Layout 2 detects too)").isNotNull();
        assertThat(syntheticColumn.get("synthetic").asBoolean()).isTrue();
        assertThat(syntheticColumn.get("suggestedTarget").isNull()).as("must not double-suggest previousGroupName").isTrue();
    }

    @Test
    void columnIndexMinusOneWithoutDetectedBlockStructureIsRejected() throws Exception {
        String planId = createPlan();
        try (org.apache.poi.xssf.usermodel.XSSFWorkbook workbook = new org.apache.poi.xssf.usermodel.XSSFWorkbook()) {
            org.apache.poi.ss.usermodel.Sheet sheet = workbook.createSheet("Flat");
            org.apache.poi.ss.usermodel.Row header = sheet.createRow(0);
            header.createCell(0).setCellValue("Förnamn");
            header.createCell(1).setCellValue("Efternamn");
            org.apache.poi.ss.usermodel.Row row1 = sheet.createRow(1);
            row1.createCell(0).setCellValue("Nils");
            row1.createCell(1).setCellValue("Åström");
            org.apache.poi.ss.usermodel.Row row2 = sheet.createRow(2);
            row2.createCell(0).setCellValue("Eva");
            row2.createCell(1).setCellValue("Berg");
            java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
            workbook.write(out);

            String base = "/api/plans/" + planId + "/import";
            String createResponse = mockMvc.perform(multipart(base + "/sessions")
                            .file(new MockMultipartFile("file", "flat.xlsx",
                                    "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet", out.toByteArray()))
                            .header("X-GP-Token", VALID_TOKEN))
                    .andExpect(status().isCreated())
                    .andReturn().getResponse().getContentAsString();
            String sessionId = objectMapper.readTree(createResponse).get("sessionId").asText();
            String sid = base + "/sessions/" + sessionId;

            mockMvc.perform(put(sid + "/header")
                            .header("X-GP-Token", VALID_TOKEN)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(new ImportController.HeaderRequest("Flat", 0))))
                    .andExpect(status().isOk());

            String columnsResponse = mockMvc.perform(get(sid + "/columns").header("X-GP-Token", VALID_TOKEN))
                    .andExpect(status().isOk())
                    .andReturn().getResponse().getContentAsString();
            assertThat(findColumnByIndex(objectMapper.readTree(columnsResponse), -1))
                    .as("a flat file must not expose the synthetic column at all").isNull();

            List<ImportController.ColumnMappingDto> mappings = List.of(
                    new ImportController.ColumnMappingDto(-1, "previousGroupName"));
            mockMvc.perform(put(sid + "/mapping")
                            .header("X-GP-Token", VALID_TOKEN)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(new ImportController.MappingRequest("Flat", mappings))))
                    .andExpect(status().isBadRequest());
        }
    }

    private static JsonNode findColumnByIndex(JsonNode columnsJson, int columnIndex) {
        for (JsonNode column : columnsJson.get("columns")) {
            if (column.get("columnIndex").asInt() == columnIndex) {
                return column;
            }
        }
        return null;
    }

    @Test
    void reImportOfTheSameFileMergesOnExternalIdInsteadOfConflicting() throws Exception {
        // M3 review finding 1: a second import of the same members (same external member ids) must
        // merge onto the existing person rows, not blow up the whole commit with an opaque 409 on
        // the person.external_id UNIQUE constraint.
        String planId = createPlan();
        MessyWorkbookBuilder.BuiltWorkbook built = MessyWorkbookBuilder.build();

        runImportWithExternalIdMapped(planId, built);
        long personCountAfterFirstImport = personRepository.findAll().size();

        // Second, identical import: must commit cleanly (no 409) and create zero new persons.
        JsonNode secondCommit = runImportWithExternalIdMapped(planId, built);

        assertThat(secondCommit.get("imported").asInt()).isGreaterThan(0);
        assertThat(personRepository.findAll().size()).isEqualTo(personCountAfterFirstImport);
    }

    @Test
    void sessionUploadedForOnePlanCannotBeUsedByAnotherPlan() throws Exception {
        // M3 review finding 4: a session is bound to the plan it was uploaded for.
        String planA = createPlan();
        String planB = createPlan();
        MessyWorkbookBuilder.BuiltWorkbook built = MessyWorkbookBuilder.build();

        String createResponse = mockMvc.perform(multipart("/api/plans/" + planA + "/import/sessions")
                        .file(fixtureFile(built.bytes()))
                        .header("X-GP-Token", VALID_TOKEN))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        String sessionId = objectMapper.readTree(createResponse).get("sessionId").asText();

        // Any plan-B access to plan-A's session is a 400 - preview, header, validate, commit alike.
        String sidUnderPlanB = "/api/plans/" + planB + "/import/sessions/" + sessionId;
        mockMvc.perform(get(sidUnderPlanB + "/preview")
                        .param("sheet", MessyWorkbookBuilder.SHEET_NAME)
                        .header("X-GP-Token", VALID_TOKEN))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").exists());
        mockMvc.perform(post(sidUnderPlanB + "/commit").header("X-GP-Token", VALID_TOKEN))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").exists());

        // The owning plan still works.
        mockMvc.perform(get("/api/plans/" + planA + "/import/sessions/" + sessionId + "/preview")
                        .param("sheet", MessyWorkbookBuilder.SHEET_NAME)
                        .header("X-GP-Token", VALID_TOKEN))
                .andExpect(status().isOk());
    }

    /** Compact upload -> header -> mapping (incl. externalId) -> commit round; returns the commit JSON. */
    private JsonNode runImportWithExternalIdMapped(String planId, MessyWorkbookBuilder.BuiltWorkbook built) throws Exception {
        String base = "/api/plans/" + planId + "/import";
        String createResponse = mockMvc.perform(multipart(base + "/sessions")
                        .file(fixtureFile(built.bytes()))
                        .header("X-GP-Token", VALID_TOKEN))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        String sessionId = objectMapper.readTree(createResponse).get("sessionId").asText();
        String sid = base + "/sessions/" + sessionId;

        mockMvc.perform(put(sid + "/header")
                        .header("X-GP-Token", VALID_TOKEN)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new ImportController.HeaderRequest(MessyWorkbookBuilder.SHEET_NAME, 0))))
                .andExpect(status().isOk());

        List<ImportController.ColumnMappingDto> mappings = List.of(
                new ImportController.ColumnMappingDto(1, "firstName"),
                new ImportController.ColumnMappingDto(2, "lastName"),
                new ImportController.ColumnMappingDto(3, "rankingPoints"),
                new ImportController.ColumnMappingDto(4, "comment"),
                new ImportController.ColumnMappingDto(5, "email"),
                new ImportController.ColumnMappingDto(7, "previousGroupName"),
                new ImportController.ColumnMappingDto(9, "isCoach"),
                new ImportController.ColumnMappingDto(10, "externalId"));
        mockMvc.perform(put(sid + "/mapping")
                        .header("X-GP-Token", VALID_TOKEN)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new ImportController.MappingRequest(MessyWorkbookBuilder.SHEET_NAME, mappings))))
                .andExpect(status().isOk());

        String commitResponse = mockMvc.perform(post(sid + "/commit")
                        .header("X-GP-Token", VALID_TOKEN)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new ImportController.CommitRequest(false, null))))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        return objectMapper.readTree(commitResponse);
    }

    @Test
    void headerRowIndexIsValidatedAgainstTheSheetsRowCount() throws Exception {
        // A headerRowIndex >= rowCount (or Integer.MAX_VALUE) must be rejected with 400, not accepted
        // and later blow up ImportValidationService's headerRowIndex + 1 loop bound with an overflow.
        String planId = createPlan();
        MessyWorkbookBuilder.BuiltWorkbook built = MessyWorkbookBuilder.build();
        String base = "/api/plans/" + planId + "/import";

        String createResponse = mockMvc.perform(multipart(base + "/sessions")
                        .file(fixtureFile(built.bytes()))
                        .header("X-GP-Token", VALID_TOKEN))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        String sessionId = objectMapper.readTree(createResponse).get("sessionId").asText();
        String sid = base + "/sessions/" + sessionId;

        String previewResponse = mockMvc.perform(get(sid + "/preview")
                        .param("sheet", MessyWorkbookBuilder.SHEET_NAME)
                        .param("rows", "1")
                        .header("X-GP-Token", VALID_TOKEN))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        int rowCount = objectMapper.readTree(previewResponse).get("rowCount").asInt();

        mockMvc.perform(put(sid + "/header")
                        .header("X-GP-Token", VALID_TOKEN)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(
                                new ImportController.HeaderRequest(MessyWorkbookBuilder.SHEET_NAME, rowCount))))
                .andExpect(status().isBadRequest());

        mockMvc.perform(put(sid + "/header")
                        .header("X-GP-Token", VALID_TOKEN)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(
                                new ImportController.HeaderRequest(MessyWorkbookBuilder.SHEET_NAME, Integer.MAX_VALUE))))
                .andExpect(status().isBadRequest());

        mockMvc.perform(put(sid + "/header")
                        .header("X-GP-Token", VALID_TOKEN)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(
                                new ImportController.HeaderRequest(MessyWorkbookBuilder.SHEET_NAME, rowCount - 1))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.headerRowIndex").value(rowCount - 1));
    }

    @Test
    void requestsWithoutTokenAreRejected() throws Exception {
        String planId = createPlan();
        mockMvc.perform(get("/api/plans/" + planId + "/import/sessions/does-not-exist/validate"))
                .andExpect(status().isUnauthorized());
    }

    private Person findPersonByEmail(String email) {
        return personRepository.findAll().stream()
                .filter(p -> email.equalsIgnoreCase(p.email()))
                .findFirst()
                .orElseThrow(() -> new AssertionError("No person with email " + email));
    }
}
