package se.klubb.groupplanner.api;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.Path;
import java.time.Instant;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import se.klubb.groupplanner.domain.ActivityPlan;
import se.klubb.groupplanner.domain.SeasonPlan;
import se.klubb.groupplanner.repo.ActivityPlanRepository;
import se.klubb.groupplanner.repo.SeasonPlanRepository;
import se.klubb.groupplanner.util.Uuid7;

/**
 * Field builder CRUD for plan-scoped CUSTOM fields (spec §9, docs/plan.md M4 row): create/patch/
 * delete validation matrix (field type vs constraint type compatibility, standard-field write
 * protection, MEDIUM rejection per ADR-006).
 */
@SpringBootTest
@AutoConfigureMockMvc
class FieldDefinitionCrudControllerTest {

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

    private String createPlan() {
        Instant now = Instant.now();
        SeasonPlan season = seasonPlanRepository.insert(new SeasonPlan(Uuid7.generate(), "VT26", null, null, "active", now, now));
        ActivityPlan plan = activityPlanRepository.insert(
                new ActivityPlan(Uuid7.generate(), season.id(), "Herr", "beach", "draft", null, null, null, null, now, now));
        return plan.id();
    }

    @Test
    void createsThePersonRelationExampleFieldFromSpecSection9Point1() throws Exception {
        String planId = createPlan();
        String body = objectMapper.writeValueAsString(new FieldDefinitionController.CreateFieldDefinitionRequest(
                "wantsToPlayWith", "Vill spela med", "personRelation", true, "SAME_GROUP", "SOFT", 80, null, null, null, null));

        String response = mockMvc.perform(post("/api/plans/" + planId + "/field-definitions")
                        .header("X-GP-Token", VALID_TOKEN)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.key").value("wantsToPlayWith"))
                .andExpect(jsonPath("$.storageKind").value("CUSTOM"))
                .andExpect(jsonPath("$.isStandard").value(false))
                .andExpect(jsonPath("$.hardOrSoft").value("SOFT"))
                .andExpect(jsonPath("$.weight").value(80))
                .andReturn().getResponse().getContentAsString();
        String id = objectMapper.readTree(response).get("id").asText();

        // The new field must show up in the plan's visible-fields listing alongside the 20 globals
        // (19 spec §9.2 fields + mustNotPlayWith, added in M6a - backend/docs/m6a-notes.md).
        mockMvc.perform(get("/api/plans/" + planId + "/field-definitions").header("X-GP-Token", VALID_TOKEN))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(21));

        // Cleanup path: a custom field can be deleted.
        mockMvc.perform(delete("/api/field-definitions/" + id).header("X-GP-Token", VALID_TOKEN))
                .andExpect(status().isNoContent());
        mockMvc.perform(get("/api/plans/" + planId + "/field-definitions").header("X-GP-Token", VALID_TOKEN))
                .andExpect(jsonPath("$.length()").value(20));
    }

    @Test
    void rejectsConstraintTypeIncompatibleWithFieldType() throws Exception {
        String planId = createPlan();
        // A text field cannot drive SAME_GROUP (that's a personRelation-shaped constraint).
        String body = objectMapper.writeValueAsString(new FieldDefinitionController.CreateFieldDefinitionRequest(
                "someTextField", "Fritext", "text", true, "SAME_GROUP", "SOFT", 50, null, null, null, null));

        mockMvc.perform(post("/api/plans/" + planId + "/field-definitions")
                        .header("X-GP-Token", VALID_TOKEN)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").exists());
    }

    @Test
    void rejectsMediumHardOrSoftWithClearMessage() throws Exception {
        String planId = createPlan();
        String body = objectMapper.writeValueAsString(new FieldDefinitionController.CreateFieldDefinitionRequest(
                "someNumberField", "Ett tal", "number", true, "LEVEL_BALANCE_INPUT", "MEDIUM", 50, null, null, null, null));

        mockMvc.perform(post("/api/plans/" + planId + "/field-definitions")
                        .header("X-GP-Token", VALID_TOKEN)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value(org.hamcrest.Matchers.containsString("MEDIUM")));
    }

    @Test
    void rejectsHardConstraintWithAWeight() throws Exception {
        String planId = createPlan();
        String body = objectMapper.writeValueAsString(new FieldDefinitionController.CreateFieldDefinitionRequest(
                "mustField", "Måste-fält", "personRelation", true, "SAME_GROUP", "HARD", 50, null, null, null, null));

        mockMvc.perform(post("/api/plans/" + planId + "/field-definitions")
                        .header("X-GP-Token", VALID_TOKEN)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").exists());
    }

    @Test
    void rejectsSoftConstraintWithoutAPositiveWeight() throws Exception {
        String planId = createPlan();
        String body = objectMapper.writeValueAsString(new FieldDefinitionController.CreateFieldDefinitionRequest(
                "softField", "Mjukt fält", "personRelation", true, "SAME_GROUP", "SOFT", null, null, null, null, null));

        mockMvc.perform(post("/api/plans/" + planId + "/field-definitions")
                        .header("X-GP-Token", VALID_TOKEN)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").exists());
    }

    /** M6a review fix 5: the ScoreHeadroomTest overflow analysis assumes weights are capped at
     * WeightLimits.MAX_WEIGHT (10 000) — the field-builder write path must enforce it too. */
    @Test
    void rejectsSoftConstraintWeightAboveTenThousand() throws Exception {
        String planId = createPlan();
        String body = objectMapper.writeValueAsString(new FieldDefinitionController.CreateFieldDefinitionRequest(
                "tooHeavyField", "För tungt fält", "personRelation", true, "SAME_GROUP", "SOFT",
                se.klubb.groupplanner.fields.WeightLimits.MAX_WEIGHT + 1, null, null, null, null));

        mockMvc.perform(post("/api/plans/" + planId + "/field-definitions")
                        .header("X-GP-Token", VALID_TOKEN)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value(org.hamcrest.Matchers.containsString("10000")));
    }

    @Test
    void rejectsKeyCollisionWithAStandardField() throws Exception {
        String planId = createPlan();
        String body = objectMapper.writeValueAsString(new FieldDefinitionController.CreateFieldDefinitionRequest(
                "rankingPoints", "Dubblett", "number", false, null, null, null, null, null, null, null));

        mockMvc.perform(post("/api/plans/" + planId + "/field-definitions")
                        .header("X-GP-Token", VALID_TOKEN)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").exists());
    }

    @Test
    void rejectsMultiSelectFieldWithoutOptions() throws Exception {
        String planId = createPlan();
        String body = objectMapper.writeValueAsString(new FieldDefinitionController.CreateFieldDefinitionRequest(
                "shirtSizes", "Tröjstorlekar", "multiSelect", false, null, null, null, null, null, null, null));

        mockMvc.perform(post("/api/plans/" + planId + "/field-definitions")
                        .header("X-GP-Token", VALID_TOKEN)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").exists());
    }

    @Test
    void standardFieldPatchAllowsOptimizationSubsetButRejectsLabelChange() throws Exception {
        String planId = createPlan();
        // rankingPoints is global/standard (seeded in V2).
        String response = mockMvc.perform(get("/api/plans/" + planId + "/field-definitions").header("X-GP-Token", VALID_TOKEN))
                .andReturn().getResponse().getContentAsString();
        String rankingPointsId = objectMapper.readTree(response).get(0).get("id").asText();

        // Allowed: weight change.
        String patchBody = objectMapper.writeValueAsString(new FieldDefinitionController.UpdateFieldDefinitionRequest(
                null, null, null, null, 150, null, null, null, null));
        mockMvc.perform(patch("/api/field-definitions/" + rankingPointsId)
                        .header("X-GP-Token", VALID_TOKEN)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(patchBody))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.weight").value(150))
                .andExpect(jsonPath("$.key").value("rankingPoints"));

        // Forbidden: label change on a standard field.
        String labelPatch = objectMapper.writeValueAsString(new FieldDefinitionController.UpdateFieldDefinitionRequest(
                "Nytt namn", null, null, null, null, null, null, null, null));
        mockMvc.perform(patch("/api/field-definitions/" + rankingPointsId)
                        .header("X-GP-Token", VALID_TOKEN)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(labelPatch))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").exists());
    }

    /**
     * The seeded standard {@code priority} field is the one MEDIUM-classified field (V2:
     * is_standard=1, hard_or_soft=MEDIUM, affects_optimization=1) — its weight scales M6's
     * {@code unassignedPlayer} waitlist penalty (ADR-006). The M4 review found the guardrail was
     * inverted on PATCH: benign edits 400'd while reclassification succeeded. These tests pin the
     * corrected behavior: weight/explanationText edits allowed, MEDIUM and affectsOptimization
     * immutable.
     */
    @Test
    void reservedMediumPriorityFieldAllowsWeightAndExplanationTextEdits() throws Exception {
        String planId = createPlan();
        String priorityId = fieldIdByKey(planId, "priority");

        // Allowed: explanationText only (regression for the "field is un-editable" bug: this used
        // to 400 because the reconstructed MEDIUM tripped the unconditional MEDIUM rejection).
        String explanationPatch = objectMapper.writeValueAsString(new FieldDefinitionController.UpdateFieldDefinitionRequest(
                null, null, null, null, null, null, "Styr kölistan vid platsbrist", null, null));
        mockMvc.perform(patch("/api/field-definitions/" + priorityId)
                        .header("X-GP-Token", VALID_TOKEN)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(explanationPatch))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.explanationText").value("Styr kölistan vid platsbrist"))
                .andExpect(jsonPath("$.hardOrSoft").value("MEDIUM"))
                .andExpect(jsonPath("$.affectsOptimization").value(true));

        // Allowed: weight change (stays MEDIUM).
        String weightPatch = objectMapper.writeValueAsString(new FieldDefinitionController.UpdateFieldDefinitionRequest(
                null, null, null, null, 200, null, null, null, null));
        mockMvc.perform(patch("/api/field-definitions/" + priorityId)
                        .header("X-GP-Token", VALID_TOKEN)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(weightPatch))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.weight").value(200))
                .andExpect(jsonPath("$.hardOrSoft").value("MEDIUM"));

        // Weight floor >= 1 still applies.
        String zeroWeightPatch = objectMapper.writeValueAsString(new FieldDefinitionController.UpdateFieldDefinitionRequest(
                null, null, null, null, 0, null, null, null, null));
        mockMvc.perform(patch("/api/field-definitions/" + priorityId)
                        .header("X-GP-Token", VALID_TOKEN)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(zeroWeightPatch))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").exists());
    }

    @Test
    void reservedMediumPriorityFieldCannotBeReclassifiedOrMadeInformationOnly() throws Exception {
        String planId = createPlan();
        String priorityId = fieldIdByKey(planId, "priority");

        // Forbidden: reclassify MEDIUM -> SOFT (this used to SUCCEED and corrupt the field).
        String softPatch = objectMapper.writeValueAsString(new FieldDefinitionController.UpdateFieldDefinitionRequest(
                null, null, null, "SOFT", null, null, null, null, null));
        mockMvc.perform(patch("/api/field-definitions/" + priorityId)
                        .header("X-GP-Token", VALID_TOKEN)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(softPatch))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value(org.hamcrest.Matchers.containsString("MEDIUM")));

        // Forbidden: turn optimization off (also used to SUCCEED).
        String infoPatch = objectMapper.writeValueAsString(new FieldDefinitionController.UpdateFieldDefinitionRequest(
                null, false, null, null, null, null, null, null, null));
        mockMvc.perform(patch("/api/field-definitions/" + priorityId)
                        .header("X-GP-Token", VALID_TOKEN)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(infoPatch))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").exists());

        // The field is untouched after both rejected attempts.
        mockMvc.perform(get("/api/plans/" + planId + "/field-definitions").header("X-GP-Token", VALID_TOKEN))
                .andExpect(jsonPath("$[?(@.key=='priority')].hardOrSoft").value("MEDIUM"))
                .andExpect(jsonPath("$[?(@.key=='priority')].affectsOptimization").value(true));
    }

    /**
     * M6a carryover (backend/docs/m6a-notes.md item 8): {@code constraintType} on the reserved
     * {@code priority} field must be just as immutable as {@code hardOrSoft}. Before this fix, a
     * PATCH could retarget it to {@code LEVEL_BALANCE_INPUT} (also compatible with the field's
     * {@code number} fieldType) without ever touching {@code hardOrSoft=MEDIUM} - {@code
     * SolverInputAssembler} keys its priority parsing off {@code constraintType == PRIORITY} (not
     * off the field's {@code key}), so this would have silently defaulted every participant's
     * priority to 3, breaking §2's "shed lowest-priority-first" waitlist guarantee without any
     * MEDIUM-rejection error ever firing.
     */
    @Test
    void reservedMediumPriorityFieldCannotHaveItsConstraintTypeChanged() throws Exception {
        String planId = createPlan();
        String priorityId = fieldIdByKey(planId, "priority");

        String retargetPatch = objectMapper.writeValueAsString(new FieldDefinitionController.UpdateFieldDefinitionRequest(
                null, null, "LEVEL_BALANCE_INPUT", null, null, null, null, null, null));
        mockMvc.perform(patch("/api/field-definitions/" + priorityId)
                        .header("X-GP-Token", VALID_TOKEN)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(retargetPatch))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value(org.hamcrest.Matchers.containsString("PRIORITY")));

        mockMvc.perform(get("/api/plans/" + planId + "/field-definitions").header("X-GP-Token", VALID_TOKEN))
                .andExpect(jsonPath("$[?(@.key=='priority')].constraintType").value("PRIORITY"))
                .andExpect(jsonPath("$[?(@.key=='priority')].hardOrSoft").value("MEDIUM"));
    }

    private String fieldIdByKey(String planId, String key) throws Exception {
        String response = mockMvc.perform(get("/api/plans/" + planId + "/field-definitions").header("X-GP-Token", VALID_TOKEN))
                .andReturn().getResponse().getContentAsString();
        for (com.fasterxml.jackson.databind.JsonNode node : objectMapper.readTree(response)) {
            if (key.equals(node.get("key").asText())) {
                return node.get("id").asText();
            }
        }
        throw new AssertionError("No field with key " + key);
    }

    @Test
    void standardFieldsCannotBeDeleted() throws Exception {
        String planId = createPlan();
        String response = mockMvc.perform(get("/api/plans/" + planId + "/field-definitions").header("X-GP-Token", VALID_TOKEN))
                .andReturn().getResponse().getContentAsString();
        String rankingPointsId = objectMapper.readTree(response).get(0).get("id").asText();

        mockMvc.perform(delete("/api/field-definitions/" + rankingPointsId).header("X-GP-Token", VALID_TOKEN))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").exists());
    }

    @Test
    void patchingHardOrSoftToHardAutomaticallyDropsAStaleWeight() throws Exception {
        String planId = createPlan();
        String createBody = objectMapper.writeValueAsString(new FieldDefinitionController.CreateFieldDefinitionRequest(
                "friendWish", "Kompisönskemål", "personRelation", true, "SAME_GROUP", "SOFT", 70, null, null, null, null));
        String created = mockMvc.perform(post("/api/plans/" + planId + "/field-definitions")
                        .header("X-GP-Token", VALID_TOKEN)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(createBody))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        String id = objectMapper.readTree(created).get("id").asText();

        String patchBody = objectMapper.writeValueAsString(new FieldDefinitionController.UpdateFieldDefinitionRequest(
                null, null, null, "HARD", null, null, null, null, null));
        mockMvc.perform(patch("/api/field-definitions/" + id)
                        .header("X-GP-Token", VALID_TOKEN)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(patchBody))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.hardOrSoft").value("HARD"))
                .andExpect(jsonPath("$.weight").doesNotExist());
    }

    @Test
    void createUnderUnknownPlanReturns404() throws Exception {
        String body = objectMapper.writeValueAsString(new FieldDefinitionController.CreateFieldDefinitionRequest(
                "someField", "Etikett", "text", false, null, null, null, null, null, null, null));

        mockMvc.perform(post("/api/plans/does-not-exist/field-definitions")
                        .header("X-GP-Token", VALID_TOKEN)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error").exists());
    }
}
