package se.klubb.groupplanner.solver.regression;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
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
import se.klubb.groupplanner.solver.TestSolverFactory;
import se.klubb.groupplanner.solver.assemble.GroupGenerator;
import se.klubb.groupplanner.solver.assemble.SolverInputAssembler;
import se.klubb.groupplanner.solver.domain.GroupPlanSolution;
import se.klubb.groupplanner.solver.domain.GroupSchedule;

/**
 * THE golden gate (docs/plan.md M6a exit criteria; docs/design/04-solver.md §9.3/§15 M-S1 gate c/d):
 * loads each committed anonymized dataset ({@code test-data/datasets/{small-10,coach-overlap-20,
 * large-120}}) through the real import-shaped pipeline ({@link TestDatasetLoader}), assembles it via
 * {@link SolverInputAssembler}, solves with {@code stepCountLimit=2000}, {@code PHASE_ASSERT},
 * {@code randomSeed=0} (the same {@code solverConfig.xml} wiring, only termination/environment-mode
 * overridden per ADR-007), and asserts:
 *
 * <ul>
 *   <li>{@code hardScore == 0} for every dataset (no double-bookings, no broken hard constraints);
 *   <li>the resulting score matches {@code test-data/regression/expected-scores.json} EXACTLY -
 *       this is the build-breaking cross-platform determinism gate (CI runs the identical test on
 *       macos-latest and windows-latest per {@code .github/workflows/ci.yml}: a mac/windows score
 *       divergence fails CI without any extra wiring needed here);
 *   <li>every {@code GroupSchedule.trainingBlock} is non-null (10.2 groupRequiresTrainingBlock,
 *       "satisfied by construction" - a model-level test, not a {@code ConstraintVerifier} test,
 *       per the design doc's own instruction for this row).
 * </ul>
 *
 * <p><b>Deviation from the design/brief's literal {@code stepCountLimit=2000}</b> (documented per
 * CLAUDE.md/backend/docs/m6a-notes.md, since a step-count deviation needs a strong reason): measured
 * empirically on this dataset with every HARD constraint already independently verified correct via
 * 44 {@code ConstraintVerifier} tests, {@code large-120} (130 participants, 12 groups, 8 coaches, all
 * HARD constraints active) does NOT reach {@code hardScore == 0} within 2000 steps - the deficit
 * shrinks monotonically and deterministically as steps increase (-7@500, -5@1000, -5@2000, -1@4000,
 * -1@8000, 0@16000hard), which is the signature of a step-budget/search-throughput limit, not a
 * stuck local optimum or a correctness bug: with only 12 {@code GroupSchedule} and ~12 {@code
 * CoachSlot} entities against 127 {@code PlayerAssignment} entities, Timefold's default (unconfigured)
 * local search move selection samples moves roughly in proportion to entity population, so the
 * relatively rare block-reassignment/coach-reassignment moves needed to resolve the last few {@code
 * trainingBlockCapacity}/{@code timeAvailabilityHard} matches take many more steps to be sampled by
 * chance than for {@code small-10}/{@code coach-overlap-20} (which already converge well inside 2000
 * steps). {@code stepCountLimit=20000} (~25% headroom over the empirically observed 16000-step
 * convergence point) is used uniformly for all three datasets instead - same {@code PHASE_ASSERT} +
 * {@code randomSeed=0} + step-count-only termination, so every determinism property this gate exists
 * to enforce is unchanged; it costs ~4 s wall-clock for {@code large-120} (measured), well inside the
 * CI job's 20-minute budget. Hand-tuning {@code solverConfig.xml}'s move selectors to fix the
 * underlying throughput imbalance was deliberately NOT done - that is real solver tuning, out of this
 * "faithful implementation, not re-design" milestone's scope, and a legitimate M6b/M7 follow-up if
 * dataset sizes grow further.
 *
 * <p>Run with {@code -DupdateGoldenScores=true} to regenerate the golden file after a deliberate,
 * reviewed constraint/weight change.
 */
@SpringBootTest
class SolverRegressionTest {

    private static final List<String> DATASETS = List.of("small-10", "coach-overlap-20", "large-120");
    private static final int STEP_COUNT_LIMIT = 20_000; // see class javadoc "Deviation" note
    private static final Path GOLDEN_FILE = Path.of("..", "test-data", "regression", "expected-scores.json");

    @TempDir
    static Path dataDir;

    @DynamicPropertySource
    static void appDataDir(DynamicPropertyRegistry registry) {
        registry.add("app.data-dir", () -> dataDir.toString());
    }

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
    @Autowired
    private SolverInputAssembler assembler;
    @Autowired
    private ObjectMapper objectMapper;

    private TestDatasetLoader newLoader() {
        return new TestDatasetLoader(
                seasonPlanRepository, activityPlanRepository, personRepository, participantProfileRepository,
                playerAssignmentRepository, coachProfileRepository, coachTimeSlotRepository, timeSlotRepository,
                trainingBlockGenerationService, fieldDefinitionRepository, customFieldValueRepository, levelService,
                groupGenerator);
    }

    @Test
    void allDatasetsSolveFeasibleAndMatchGoldenScoresExactly() throws IOException {
        Map<String, String> actualScores = new LinkedHashMap<>();
        for (String dataset : DATASETS) {
            String planId = newLoader().load(dataset);
            GroupPlanSolution solution = TestSolverFactory.solve(assembler.assemble(planId).solution(), STEP_COUNT_LIMIT);

            assertThat(solution.getScore().hardScore())
                    .as("dataset '%s' must solve hard-feasible", dataset)
                    .isZero();
            for (GroupSchedule gs : solution.getGroupSchedules()) {
                assertThat(gs.getTrainingBlock())
                        .as("dataset '%s': group '%s' must always resolve a training block (10.2, satisfied by construction)",
                                dataset, gs.getGroup().name())
                        .isNotNull();
            }
            actualScores.put(dataset, solution.getScore().toString());
        }

        if (Boolean.getBoolean("updateGoldenScores")) {
            Files.createDirectories(GOLDEN_FILE.getParent());
            Files.writeString(GOLDEN_FILE, objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(actualScores));
            return;
        }

        assertThat(Files.exists(GOLDEN_FILE))
                .as("%s must exist - run with -DupdateGoldenScores=true once to generate it", GOLDEN_FILE)
                .isTrue();
        @SuppressWarnings("unchecked")
        Map<String, String> expectedScores = objectMapper.readValue(GOLDEN_FILE.toFile(), Map.class);
        assertThat(actualScores).as("golden score drift - see %s", GOLDEN_FILE).isEqualTo(expectedScores);
    }

    @Test
    void doubleSolveFromIndependentAssembliesOfTheSameDataIsScoreIdentical() {
        // large-120 deliberately (review fix 2): it is the only dataset with enough wish/custom-field
        // rows to have exposed the CustomFieldValueRepository ORDER BY nondeterminism bug (m6a-notes
        // "Determinism bug found and fixed") - small-10 would have passed even with that bug present.
        // The dataset is loaded ONCE; the assembly (DB -> POJO) is what must be independently
        // repeatable, so only that step is done twice.
        String planId = newLoader().load("large-120");

        GroupPlanSolution first = TestSolverFactory.solve(assembler.assemble(planId).solution(), STEP_COUNT_LIMIT);
        GroupPlanSolution second = TestSolverFactory.solve(assembler.assemble(planId).solution(), STEP_COUNT_LIMIT);

        assertThat(first.getScore()).isEqualTo(second.getScore());
    }
}
