package se.klubb.groupplanner.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.Path;
import java.util.Optional;
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
import se.klubb.groupplanner.domain.Person;
import se.klubb.groupplanner.domain.PlayerAssignment;
import se.klubb.groupplanner.level.LevelService;
import se.klubb.groupplanner.repo.ActivityPlanRepository;
import se.klubb.groupplanner.repo.CoachProfileRepository;
import se.klubb.groupplanner.repo.CoachTimeSlotRepository;
import se.klubb.groupplanner.repo.CustomFieldValueRepository;
import se.klubb.groupplanner.repo.FieldDefinitionRepository;
import se.klubb.groupplanner.repo.ParticipantProfileRepository;
import se.klubb.groupplanner.repo.PersonRepository;
import se.klubb.groupplanner.repo.PlayerAssignmentRepository;
import se.klubb.groupplanner.repo.SeasonPlanRepository;
import se.klubb.groupplanner.repo.TimeSlotRepository;
import se.klubb.groupplanner.resources.TrainingBlockGenerationService;
import se.klubb.groupplanner.solver.assemble.GroupGenerator;
import se.klubb.groupplanner.solver.regression.TestDatasetLoader;
import se.klubb.groupplanner.util.Uuid7;

/**
 * Regression for the M8 jar-E2E finding (see backend/docs/m8-notes.md "Bug found during E2E"):
 * a participant created via {@code POST /api/plans/{planId}/participants} had NO {@code
 * player_assignment} row (only the import-commit path created one), so {@code
 * SolveCoordinator#persistResult}'s {@code updateGroupAndSource} — an UPDATE scoped to an existing
 * row — silently dropped the solver's placement for every such participant on writeback. Two
 * independent fixes, each with its own test here: the REST create endpoint now seeds the
 * "awaiting placement" row, AND {@code persistResult} inserts-if-absent defensively before
 * updating (covering any historical/other row-less participant).
 */
@SpringBootTest
@AutoConfigureMockMvc
class ParticipantAssignmentRowRegressionTest {

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
    private FieldDefinitionRepository fieldDefinitionRepository;
    @Autowired
    private CustomFieldValueRepository customFieldValueRepository;
    @Autowired
    private LevelService levelService;
    @Autowired
    private GroupGenerator groupGenerator;

    private String loadPlan() {
        TestDatasetLoader loader = new TestDatasetLoader(
                seasonPlanRepository, activityPlanRepository, personRepository, participantProfileRepository,
                playerAssignmentRepository, coachProfileRepository, coachTimeSlotRepository, timeSlotRepository,
                trainingBlockGenerationService, fieldDefinitionRepository, customFieldValueRepository, levelService,
                groupGenerator);
        return loader.load("small-10");
    }

    @Test
    void restCreatedParticipantGetsAnAwaitingPlacementAssignmentRow() throws Exception {
        String planId = loadPlan();
        Person person = personRepository.insert(new Person(
                Uuid7.generate(), "Regression", "Testsson", null, null, null, null, true, false, null,
                java.time.Instant.now(), java.time.Instant.now()));

        String body = objectMapper.writeValueAsString(java.util.Map.of("personId", person.id(), "rankingPoints", 500.0));
        String response = mockMvc.perform(post("/api/plans/" + planId + "/participants")
                        .header("X-GP-Token", VALID_TOKEN).contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        String participantId = objectMapper.readTree(response).get("id").asText();

        Optional<PlayerAssignment> assignment = playerAssignmentRepository.findByParticipantProfileId(participantId);
        assertThat(assignment).as("REST-created participant must have an 'awaiting placement' row").isPresent();
        assertThat(assignment.get().groupId()).isNull();
        assertThat(assignment.get().source()).isEqualTo(PlayerAssignment.SOURCE_IMPORTED);
        assertThat(assignment.get().locked()).isFalse();
    }

    @Test
    void solverWritebackPersistsPlacementEvenForAParticipantWithNoAssignmentRow() throws Exception {
        String planId = loadPlan();
        // Simulate the historical broken state: a profile with NO player_assignment row at all.
        Person person = personRepository.insert(new Person(
                Uuid7.generate(), "Radlös", "Testsson", null, null, null, null, true, false, null,
                java.time.Instant.now(), java.time.Instant.now()));
        ParticipantProfile rowless = participantProfileRepository.insert(new ParticipantProfile(
                Uuid7.generate(), person.id(), planId, 500.0, "seriespel", null, null, 500.0, 1.0, null, null, null,
                false, false));
        assertThat(playerAssignmentRepository.findByParticipantProfileId(rowless.id())).isEmpty();

        // Synchronous GREEDY solve (places everyone, writes back through the SAME persistResult a
        // real solve uses - m6b-notes.md).
        mockMvc.perform(post("/api/plans/" + planId + "/solve")
                        .header("X-GP-Token", VALID_TOKEN).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"profile\":\"GREEDY\"}"))
                .andExpect(status().isOk());

        Optional<PlayerAssignment> after = playerAssignmentRepository.findByParticipantProfileId(rowless.id());
        assertThat(after).as("persistResult must create the missing row rather than dropping the placement").isPresent();
        assertThat(after.get().groupId()).as("greedy places everyone - the placement must be persisted").isNotNull();
        assertThat(after.get().source()).isEqualTo(PlayerAssignment.SOURCE_SOLVER);
    }
}
