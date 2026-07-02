package se.klubb.groupplanner.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import se.klubb.groupplanner.domain.ParticipantProfile;
import se.klubb.groupplanner.domain.TrainingGroup;
import se.klubb.groupplanner.level.LevelService;
import se.klubb.groupplanner.repo.ActivityPlanRepository;
import se.klubb.groupplanner.repo.CoachProfileRepository;
import se.klubb.groupplanner.repo.CoachTimeSlotRepository;
import se.klubb.groupplanner.repo.CustomFieldValueRepository;
import se.klubb.groupplanner.repo.FieldDefinitionRepository;
import se.klubb.groupplanner.repo.ParticipantProfileRepository;
import se.klubb.groupplanner.repo.PersonRepository;
import se.klubb.groupplanner.repo.PlayerAssignmentRepository;
import se.klubb.groupplanner.repo.SavedPlanResourceUsageRepository;
import se.klubb.groupplanner.repo.SeasonPlanRepository;
import se.klubb.groupplanner.repo.TimeSlotRepository;
import se.klubb.groupplanner.repo.TrainingBlockRepository;
import se.klubb.groupplanner.repo.TrainingGroupRepository;
import se.klubb.groupplanner.resources.TrainingBlockGenerationService;
import se.klubb.groupplanner.solver.assemble.GroupGenerator;
import se.klubb.groupplanner.solver.regression.TestDatasetLoader;

/**
 * The "spara plan" REST lifecycle (spec §14.1-§14.4, M8 task item 1/4): {@code POST}/{@code GET}
 * list+one/{@code PATCH}/{@code DELETE} on {@code /api/plans/{planId}/saved-plans[/{id}]}. Covers
 * the full legal/illegal PATCH transition matrix via the real endpoint (complementing {@code
 * savedplan.SavedPlanLifecycleTest}'s pure unit coverage of {@code SavedPlanLifecycle} itself),
 * DELETE's draft/saved-only restriction, and materialization-refreshes-on-re-save.
 */
@SpringBootTest
@AutoConfigureMockMvc
class SavedPlanControllerIntegrationTest {

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
    private PlayerAssignmentRepository playerAssignmentRepository;
    @Autowired
    private CoachProfileRepository coachProfileRepository;
    @Autowired
    private CoachTimeSlotRepository coachTimeSlotRepository;
    @Autowired
    private TimeSlotRepository timeSlotRepository;
    @Autowired
    private TrainingBlockGenerationService trainingBlockGenerationService;
    @Autowired
    private TrainingBlockRepository trainingBlockRepository;
    @Autowired
    private TrainingGroupRepository trainingGroupRepository;
    @Autowired
    private FieldDefinitionRepository fieldDefinitionRepository;
    @Autowired
    private CustomFieldValueRepository customFieldValueRepository;
    @Autowired
    private LevelService levelService;
    @Autowired
    private GroupGenerator groupGenerator;
    @Autowired
    private SavedPlanResourceUsageRepository savedPlanResourceUsageRepository;

    private String loadPlan() {
        TestDatasetLoader loader = new TestDatasetLoader(
                seasonPlanRepository, activityPlanRepository, personRepository, participantProfileRepository,
                playerAssignmentRepository, coachProfileRepository, coachTimeSlotRepository, timeSlotRepository,
                trainingBlockGenerationService, fieldDefinitionRepository, customFieldValueRepository, levelService,
                groupGenerator);
        return loader.load("small-10");
    }

    @Test
    void postCreatesAStatusSavedRowWithAParsedSnapshot() throws Exception {
        String planId = loadPlan();

        String body = objectMapper.writeValueAsString(new SavedPlanController.SaveRequest("Min sparade plan"));
        mockMvc.perform(post("/api/plans/" + planId + "/saved-plans")
                        .header("X-GP-Token", VALID_TOKEN).contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.name").value("Min sparade plan"))
                .andExpect(jsonPath("$.status").value("saved"))
                .andExpect(jsonPath("$.snapshot.groups").isArray())
                .andExpect(jsonPath("$.snapshot.constraintWeights").isArray());

        mockMvc.perform(get("/api/plans/" + planId + "/saved-plans")
                        .header("X-GP-Token", VALID_TOKEN))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].name").value("Min sparade plan"));
    }

    @Test
    void postWithoutNameIs400() throws Exception {
        String planId = loadPlan();
        mockMvc.perform(post("/api/plans/" + planId + "/saved-plans")
                        .header("X-GP-Token", VALID_TOKEN).contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void legalTransitionMatrixThroughTheRealEndpoint() throws Exception {
        String planId = loadPlan();
        String savedPlanId = createSavedPlan(planId, "Legal flow");

        patchStatus(planId, savedPlanId, "locked").andExpect(status().isOk()).andExpect(jsonPath("$.status").value("locked"));
        patchStatus(planId, savedPlanId, "published").andExpect(status().isOk()).andExpect(jsonPath("$.status").value("published"));
        patchStatus(planId, savedPlanId, "archived").andExpect(status().isOk()).andExpect(jsonPath("$.status").value("archived"));
    }

    @Test
    void illegalTransitionsAreRejectedWith409() throws Exception {
        String planId = loadPlan();
        String savedPlanId = createSavedPlan(planId, "Illegal flow");

        // saved -> published (skips locked) is illegal.
        patchStatus(planId, savedPlanId, "published").andExpect(status().isConflict());

        patchStatus(planId, savedPlanId, "locked").andExpect(status().isOk());
        // locked -> saved (backward) is illegal.
        patchStatus(planId, savedPlanId, "saved").andExpect(status().isConflict());

        patchStatus(planId, savedPlanId, "archived").andExpect(status().isOk());
        // archived is terminal.
        patchStatus(planId, savedPlanId, "saved").andExpect(status().isConflict());
        patchStatus(planId, savedPlanId, "locked").andExpect(status().isConflict());
    }

    @Test
    void unknownStatusIs400() throws Exception {
        String planId = loadPlan();
        String savedPlanId = createSavedPlan(planId, "Bad status");
        patchStatus(planId, savedPlanId, "bogus").andExpect(status().isBadRequest());
    }

    @Test
    void sameStatusPatchIsAnIdempotentNoOp() throws Exception {
        String planId = loadPlan();
        String savedPlanId = createSavedPlan(planId, "Idempotent");
        patchStatus(planId, savedPlanId, "saved").andExpect(status().isOk()).andExpect(jsonPath("$.status").value("saved"));
    }

    @Test
    void deleteIsAllowedForDraftAndSavedButNotLockedOrLater() throws Exception {
        String planId = loadPlan();
        String deletable = createSavedPlan(planId, "Deletable");
        mockMvc.perform(delete("/api/plans/" + planId + "/saved-plans/" + deletable).header("X-GP-Token", VALID_TOKEN))
                .andExpect(status().isNoContent());
        mockMvc.perform(get("/api/plans/" + planId + "/saved-plans/" + deletable).header("X-GP-Token", VALID_TOKEN))
                .andExpect(status().isNotFound());

        String lockedOne = createSavedPlan(planId, "Not deletable");
        patchStatus(planId, lockedOne, "locked").andExpect(status().isOk());
        mockMvc.perform(delete("/api/plans/" + planId + "/saved-plans/" + lockedOne).header("X-GP-Token", VALID_TOKEN))
                .andExpect(status().isConflict());
    }

    @Test
    void savedPlanIdFromAnotherPlanIs404() throws Exception {
        String planA = loadPlan();
        String planB = loadPlan();
        String savedPlanId = createSavedPlan(planA, "Belongs to plan A");
        mockMvc.perform(get("/api/plans/" + planB + "/saved-plans/" + savedPlanId).header("X-GP-Token", VALID_TOKEN))
                .andExpect(status().isNotFound());
    }

    /** M8 task item 1: "materialize saved_plan_resource_usage rows on SAVE; refresh on re-save" —
     * a second save after the underlying assignments changed produces a NEW saved_plan row whose
     * usage rows reflect the NEW state, not the first save's stale usage set. */
    @Test
    void reSavingRefreshesUsageRowsFromCurrentState() throws Exception {
        String planId = loadPlan();
        List<TrainingGroup> groups = trainingGroupRepository.findByActivityPlanId(planId);
        List<se.klubb.groupplanner.domain.TrainingBlock> blocks = trainingBlockRepository.findByActivityPlanId(planId);
        trainingGroupRepository.lockToBlock(groups.get(0).id(), blocks.get(0).id());
        ParticipantProfile participant = participantProfileRepository.findByActivityPlanId(planId).get(0);
        playerAssignmentRepository.lockToGroup(participant.id(), groups.get(0).id());

        String firstSavedPlanId = createSavedPlan(planId, "First save");
        int firstUsageCount = savedPlanResourceUsageRepository.findBySavedPlanId(firstSavedPlanId).size();
        assertThat(firstUsageCount).isGreaterThan(0);
        assertThat(savedPlanResourceUsageRepository.findBySavedPlanId(firstSavedPlanId))
                .anySatisfy(u -> assertThat(u.personId()).isEqualTo(participant.personId()));

        // Move the participant to the OTHER group (still scheduled, still a valid usage row) and
        // re-save: the second save must materialize its OWN fresh usage rows independent of the
        // first save's now-stale ones.
        trainingGroupRepository.lockToBlock(groups.get(1).id(), blocks.get(1).id());
        playerAssignmentRepository.unlock(participant.id());
        playerAssignmentRepository.lockToGroup(participant.id(), groups.get(1).id());

        String secondSavedPlanId = createSavedPlan(planId, "Second save");
        assertThat(secondSavedPlanId).isNotEqualTo(firstSavedPlanId);
        // The first save's own usage rows are untouched (immutable-in-spirit snapshot) - still
        // pointing at group 0's schedule.
        assertThat(savedPlanResourceUsageRepository.findBySavedPlanId(firstSavedPlanId)).hasSize(firstUsageCount);
        // The second save's usage rows reflect the NEW (group 1) schedule.
        assertThat(savedPlanResourceUsageRepository.findBySavedPlanId(secondSavedPlanId))
                .anySatisfy(u -> assertThat(u.personId()).isEqualTo(participant.personId()));
    }

    private String createSavedPlan(String planId, String name) throws Exception {
        String body = objectMapper.writeValueAsString(new SavedPlanController.SaveRequest(name));
        String response = mockMvc.perform(post("/api/plans/" + planId + "/saved-plans")
                        .header("X-GP-Token", VALID_TOKEN).contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        return objectMapper.readTree(response).get("id").asText();
    }

    private org.springframework.test.web.servlet.ResultActions patchStatus(String planId, String savedPlanId, String status) throws Exception {
        String body = objectMapper.writeValueAsString(new SavedPlanController.StatusRequest(status));
        return mockMvc.perform(patch("/api/plans/" + planId + "/saved-plans/" + savedPlanId)
                .header("X-GP-Token", VALID_TOKEN).contentType(MediaType.APPLICATION_JSON).content(body));
    }
}
