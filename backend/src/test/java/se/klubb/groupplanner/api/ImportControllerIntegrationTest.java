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
