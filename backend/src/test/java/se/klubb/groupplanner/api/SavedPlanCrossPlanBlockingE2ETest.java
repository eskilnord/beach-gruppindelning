package se.klubb.groupplanner.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
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
import se.klubb.groupplanner.domain.CoachProfile;
import se.klubb.groupplanner.domain.OptimizationRun;
import se.klubb.groupplanner.domain.ParticipantProfile;
import se.klubb.groupplanner.domain.Person;
import se.klubb.groupplanner.domain.PlayerAssignment;
import se.klubb.groupplanner.domain.TimeSlot;
import se.klubb.groupplanner.domain.TrainingBlock;
import se.klubb.groupplanner.domain.TrainingGroup;
import se.klubb.groupplanner.level.LevelService;
import se.klubb.groupplanner.repo.ActivityPlanRepository;
import se.klubb.groupplanner.repo.CoachAssignmentRepository;
import se.klubb.groupplanner.repo.CoachProfileRepository;
import se.klubb.groupplanner.repo.CoachTimeSlotRepository;
import se.klubb.groupplanner.repo.CustomFieldValueRepository;
import se.klubb.groupplanner.repo.FieldDefinitionRepository;
import se.klubb.groupplanner.repo.OptimizationRunRepository;
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
import se.klubb.groupplanner.util.Uuid7;

/**
 * THE spec §23.6 acceptance path, through the REAL API end to end (M8 task item 4/5): "Herr torsdag
 * låses. Tränare/personer/banor som är upptagna i herrplanen blockeras" (spec §14.3's own example) —
 * unlike {@code solver.CrossPlanBlockingTest}/{@code solver.assemble.SavedPlanUsageAssemblyTest}
 * (which call {@code SavedPlanService.materialize} directly, the M6b-era internal-only path), this
 * test drives the exact same scenario through {@code POST /api/plans/{planId}/saved-plans} + {@code
 * PATCH .../saved-plans/{id} {status:"locked"}} — the REAL M8 save/lock endpoints — then a REAL async
 * solve of a second plan with {@code blocking.blockCoaches=true}.
 *
 * <p>Uses the {@code small-10} fixture (already named "Anna Åkesson" for coach {@code c02} — the
 * same person spec §13.2's own worked example is named after) loaded TWICE into the SAME season, so
 * both plans' {@code t01} time slot ("Torsdag 18.00–19.30") share an identical {@code TimeKey} by
 * construction (same {@code timeslots.csv}). Herr's group 1 is deterministically pinned to {@code
 * t01} with Anna as its coach (same hand-placement style {@code loadAndSchedulePlan} uses elsewhere —
 * the NEW thing this test proves is the save/lock/solve-blocking PATH, not a new way to build the
 * fixture); Anna is then added as a PLAYER participant in the Dam plan and a real Timefold solve
 * (with coach-blocking enabled) must never place her in whichever Dam group ends up scheduled at
 * 18.00.
 */
@SpringBootTest
@AutoConfigureMockMvc
class SavedPlanCrossPlanBlockingE2ETest {

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
    private CoachAssignmentRepository coachAssignmentRepository;
    @Autowired
    private CoachTimeSlotRepository coachTimeSlotRepository;
    @Autowired
    private TimeSlotRepository timeSlotRepository;
    @Autowired
    private TrainingBlockRepository trainingBlockRepository;
    @Autowired
    private TrainingBlockGenerationService trainingBlockGenerationService;
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
    private OptimizationRunRepository optimizationRunRepository;
    @Autowired
    private SavedPlanResourceUsageRepository savedPlanResourceUsageRepository;
    @Autowired
    private com.fasterxml.jackson.databind.ObjectMapper objectMapper;

    @Test
    void savingAndLockingHerrThroughTheRealEndpointsBlocksAnnaFromDamAtEighteen() throws Exception {
        String herrPlanId = loadPlan(null);
        String seasonId = activityPlanRepository.findById(herrPlanId).orElseThrow().seasonPlanId();
        String damPlanId = loadPlan(seasonId);

        TimeSlot herrEighteen = eighteenOClockSlot(herrPlanId);
        TrainingBlock herrEighteenBlock = trainingBlockRepository.findByTimeSlotId(herrEighteen.id()).get(0);
        List<TrainingGroup> herrGroups = trainingGroupRepository.findByActivityPlanId(herrPlanId);
        TrainingGroup herrGroupAtEighteen = herrGroups.get(0);
        trainingGroupRepository.lockToBlock(herrGroupAtEighteen.id(), herrEighteenBlock.id());

        CoachProfile anna = findCoachByFirstName(herrPlanId, "Anna");
        coachAssignmentRepository.lockToGroup(anna.id(), herrGroupAtEighteen.id());

        // --- POST /api/plans/{herrPlanId}/saved-plans - THE real M8 save endpoint ---
        String saveBody = objectMapper.writeValueAsString(new SavedPlanController.SaveRequest("Herr torsdag"));
        String saveResponse = mockMvc.perform(post("/api/plans/" + herrPlanId + "/saved-plans")
                        .header("X-GP-Token", VALID_TOKEN).contentType(MediaType.APPLICATION_JSON).content(saveBody))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("saved"))
                .andReturn().getResponse().getContentAsString();
        String savedPlanId = objectMapper.readTree(saveResponse).get("id").asText();

        // Usage materialized on SAVE already (task item 1's red-team correction), before any lock.
        assertThat(savedPlanResourceUsageRepository.findBySavedPlanId(savedPlanId))
                .as("saved_plan_resource_usage rows must exist right after SAVE, not only after lock")
                .anySatisfy(u -> assertThat(u.personId()).isEqualTo(anna.personId()));

        // --- PATCH .../saved-plans/{id} {status:"locked"} - THE real M8 lock endpoint ---
        String lockBody = objectMapper.writeValueAsString(new SavedPlanController.StatusRequest("locked"));
        mockMvc.perform(patch("/api/plans/" + herrPlanId + "/saved-plans/" + savedPlanId)
                        .header("X-GP-Token", VALID_TOKEN).contentType(MediaType.APPLICATION_JSON).content(lockBody))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("locked"));

        // --- Anna also plays in the Dam plan (spec §13.2's exact shared-person shape) ---
        ParticipantProfile annaAsPlayer = participantProfileRepository.insert(new ParticipantProfile(
                Uuid7.generate(), anna.personId(), damPlanId, 500.0, "seriespel", null, null, null, null, null,
                null, null, false, false));
        playerAssignmentRepository.insertImportedIfAbsent(annaAsPlayer.id());

        // --- Dam solve WITH coach blocking - the real async solve endpoint ---
        String solveBody = "{\"profile\":\"FAST\",\"blocking\":{\"blockCoaches\":true}}";
        mockMvc.perform(post("/api/plans/" + damPlanId + "/solve")
                        .header("X-GP-Token", VALID_TOKEN).contentType(MediaType.APPLICATION_JSON).content(solveBody))
                .andExpect(status().isAccepted());

        await().atMost(Duration.ofSeconds(60)).pollInterval(Duration.ofMillis(200)).untilAsserted(() -> {
            Optional<OptimizationRun> run = optimizationRunRepository.findLatestByActivityPlanId(damPlanId);
            assertThat(run).isPresent();
            assertThat(run.get().status()).isEqualTo(OptimizationRun.STATUS_FINISHED);
        });

        // --- Whichever Dam group ends up at 18.00, Anna must not be in it ---
        TimeSlot damEighteen = eighteenOClockSlot(damPlanId);
        List<String> damBlockIdsAtEighteen = trainingBlockRepository.findByTimeSlotId(damEighteen.id())
                .stream().map(TrainingBlock::id).toList();
        List<String> damGroupIdsAtEighteen = trainingGroupRepository.findByActivityPlanId(damPlanId).stream()
                .filter(g -> g.assignedTrainingBlockId() != null && damBlockIdsAtEighteen.contains(g.assignedTrainingBlockId()))
                .map(TrainingGroup::id)
                .toList();

        Optional<PlayerAssignment> annaFinal = playerAssignmentRepository.findByParticipantProfileId(annaAsPlayer.id());
        boolean placedAtEighteen = annaFinal.isPresent() && annaFinal.get().groupId() != null
                && damGroupIdsAtEighteen.contains(annaFinal.get().groupId());
        assertThat(placedAtEighteen)
                .as("Anna is coaching Herr torsdag at 18.00 (locked) - blockCoaches=true must keep the Dam solve "
                        + "from placing her as a PLAYER in whichever Dam group lands on the same 18.00 slot")
                .isFalse();
    }

    private String loadPlan(String seasonPlanId) {
        TestDatasetLoader loader = new TestDatasetLoader(
                seasonPlanRepository, activityPlanRepository, personRepository, participantProfileRepository,
                playerAssignmentRepository, coachProfileRepository, coachTimeSlotRepository, timeSlotRepository,
                trainingBlockGenerationService, fieldDefinitionRepository, customFieldValueRepository, levelService,
                groupGenerator);
        return loader.load("small-10", seasonPlanId);
    }

    private TimeSlot eighteenOClockSlot(String planId) {
        return timeSlotRepository.findByActivityPlanId(planId).stream()
                .filter(s -> "18:00".equals(s.startTime()))
                .findFirst()
                .orElseThrow(() -> new IllegalStateException("small-10 fixture must have an 18:00 slot"));
    }

    private CoachProfile findCoachByFirstName(String planId, String firstName) {
        for (CoachProfile coach : coachProfileRepository.findByActivityPlanId(planId)) {
            Person person = personRepository.findById(coach.personId()).orElseThrow();
            if (firstName.equals(person.firstName())) {
                return coach;
            }
        }
        throw new IllegalStateException("small-10 fixture must have a coach named " + firstName);
    }
}
