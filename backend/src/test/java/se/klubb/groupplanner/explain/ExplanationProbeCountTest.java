package se.klubb.groupplanner.explain;

import static org.assertj.core.api.Assertions.assertThat;

import ai.timefold.solver.core.api.score.buildin.hardmediumsoftlong.HardMediumSoftLongScore;
import ai.timefold.solver.core.api.solver.SolutionManager;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import se.klubb.groupplanner.explain.ExplanationDtos.PersonExplanationResponse;
import se.klubb.groupplanner.repo.ActivityPlanRepository;
import se.klubb.groupplanner.repo.CoachAssignmentRepository;
import se.klubb.groupplanner.repo.CoachProfileRepository;
import se.klubb.groupplanner.repo.CustomFieldValueRepository;
import se.klubb.groupplanner.repo.ExplanationRecordRepository;
import se.klubb.groupplanner.repo.FieldDefinitionRepository;
import se.klubb.groupplanner.repo.OptimizationRunRepository;
import se.klubb.groupplanner.repo.ParticipantProfileRepository;
import se.klubb.groupplanner.repo.PersonRepository;
import se.klubb.groupplanner.repo.PlayerAssignmentRepository;
import se.klubb.groupplanner.repo.SeasonPlanRepository;
import se.klubb.groupplanner.repo.TimeSlotRepository;
import se.klubb.groupplanner.repo.TrainingGroupRepository;
import se.klubb.groupplanner.resources.TrainingBlockGenerationService;
import se.klubb.groupplanner.solver.assemble.SolverInputAssembler;
import se.klubb.groupplanner.solver.domain.GroupPlanSolution;

/**
 * M-E1 pin: "probe count per drawer open must stay EXACTLY groups+1 (+restore-verify)" — wraps the
 * REAL {@link SolutionManager} bean in a counting {@link Proxy} (every {@code analyze(...)} overload
 * shares the method name {@code "analyze"}, so counting by name catches both {@code FETCH_ALL} probes
 * and the {@code FETCH_SHALLOW} restore-verify call {@link MoveProbe} makes once per request), then
 * drives {@link ExplanationService#explainPerson} for a PLACED player through a manually-constructed
 * service instance (same constructor the Spring bean uses, same autowired collaborators — only the
 * {@link SolutionManager}/{@link MoveProbe}/{@link ExplanationCache} are test-local, so this test can
 * never accidentally pollute the shared Spring-context singleton other tests in this class run against).
 *
 * <p>Expected total: {@code numberOfGroups + 1} — {@code 1} {@code FETCH_ALL} base analysis (in {@code
 * loadContext}) + {@code (numberOfGroups - 1)} {@code FETCH_ALL} alternative-group probes ({@link
 * ExplanationService#buildAlternatives}, E1-refactored to return its probe map for {@code
 * CausalNarrator} to reuse at ZERO extra calls) + {@code 1} {@code FETCH_SHALLOW} restore-verify.
 */
@SpringBootTest
class ExplanationProbeCountTest {

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
    private TrainingGroupRepository trainingGroupRepository;
    @Autowired
    private TimeSlotRepository timeSlotRepository;
    @Autowired
    private TrainingBlockGenerationService trainingBlockGenerationService;
    @Autowired
    private FieldDefinitionRepository fieldDefinitionRepository;
    @Autowired
    private CustomFieldValueRepository customFieldValueRepository;
    @Autowired
    private OptimizationRunRepository optimizationRunRepository;
    @Autowired
    private CoachProfileRepository coachProfileRepository;
    @Autowired
    private CoachAssignmentRepository coachAssignmentRepository;
    @Autowired
    private SolverInputAssembler solverInputAssembler;
    @Autowired
    private SolutionManager<GroupPlanSolution, HardMediumSoftLongScore> realSolutionManager;
    @Autowired
    private ExplanationRecordRepository explanationRecordRepository;
    @Autowired
    private ObjectMapper objectMapper;

    @SuppressWarnings("unchecked")
    @Test
    void probeCountIsExactlyGroupsPlusOneIncludingRestoreVerify() {
        ExplanationTestFixture fx = new ExplanationTestFixture(
                seasonPlanRepository, activityPlanRepository, personRepository, participantProfileRepository,
                playerAssignmentRepository, trainingGroupRepository, timeSlotRepository, trainingBlockGenerationService,
                fieldDefinitionRepository, customFieldValueRepository, optimizationRunRepository,
                coachProfileRepository, coachAssignmentRepository);
        List<String> blocks = fx.addTimeSlotWithBlocks("Torsdag 18.00-19.30", 3);
        String groupA = fx.addGroup("Grupp A", 1, 1, 5, 5, blocks.get(0));
        String groupB = fx.addGroup("Grupp B", 2, 1, 5, 5, blocks.get(1));
        fx.addGroup("Grupp C", 3, 1, 5, 5, blocks.get(2));
        int numberOfGroups = 3;

        String kalle = fx.addParticipant("Kalle", "Karlsson", 500.0, 3);
        fx.place(kalle, groupA);
        // M-E2 review fix (test gap): the ORIGINAL fixture had no wish at all, so CausalNarrator's
        // whole per-wish narration path (which reuses buildAlternatives' probe map at ZERO extra
        // analyze() calls - the exact claim this test pins) was never actually exercised. A friend
        // wish guarantees at least one unmetWishes entry gets narrated.
        String lisa = fx.addParticipant("Lisa", "Larsson", 500.0, 3);
        fx.place(lisa, groupB);
        fx.wish(kalle, lisa, "playWith");
        String runId = fx.insertFinishedRun();

        AtomicInteger analyzeCalls = new AtomicInteger();
        InvocationHandler handler = (proxy, method, args) -> {
            if ("analyze".equals(method.getName())) {
                analyzeCalls.incrementAndGet();
            }
            return method.invoke(realSolutionManager, args);
        };
        SolutionManager<GroupPlanSolution, HardMediumSoftLongScore> countingSolutionManager =
                (SolutionManager<GroupPlanSolution, HardMediumSoftLongScore>) Proxy.newProxyInstance(
                        SolutionManager.class.getClassLoader(), new Class<?>[] {SolutionManager.class}, handler);

        MoveProbe countingProbe = new MoveProbe(countingSolutionManager);
        ExplanationService svc = new ExplanationService(
                activityPlanRepository, optimizationRunRepository, participantProfileRepository, trainingGroupRepository,
                coachAssignmentRepository, coachProfileRepository, personRepository, solverInputAssembler,
                countingSolutionManager, countingProbe, new ExplanationCache(), new WishAnalysisCache(), explanationRecordRepository, objectMapper);

        PersonExplanationResponse response = svc.explainPerson(fx.planId, runId, kalle);

        assertThat(response.selectedGroup()).isNotNull();
        assertThat(response.unmetWishes()).isNotEmpty();
        assertThat(analyzeCalls.get()).isEqualTo(numberOfGroups + 1);
    }

    /** M-E2 review fix (test gap): a ONE-group plan is the boundary case where {@code
     * buildAlternatives}'s "every OTHER group" candidate set is EMPTY - no {@link MoveProbe#evaluate}
     * call happens at all, so the total probe count collapses to exactly the base {@code FETCH_ALL}
     * analysis (no restore-verify either, since that only runs inside {@code evaluate}'s own {@code
     * finally} block). */
    @SuppressWarnings("unchecked")
    @Test
    void oneGroupPlanProbeCountIsExactlyOneBaseAnalysisNoRestoreVerify() {
        ExplanationTestFixture fx = new ExplanationTestFixture(
                seasonPlanRepository, activityPlanRepository, personRepository, participantProfileRepository,
                playerAssignmentRepository, trainingGroupRepository, timeSlotRepository, trainingBlockGenerationService,
                fieldDefinitionRepository, customFieldValueRepository, optimizationRunRepository,
                coachProfileRepository, coachAssignmentRepository);
        List<String> blocks = fx.addTimeSlotWithBlocks("Torsdag 18.00-19.30", 1);
        String groupA = fx.addGroup("Grupp A", 1, 1, 5, 5, blocks.get(0));

        String kalle = fx.addParticipant("Kalle", "Karlsson", 500.0, 3);
        fx.place(kalle, groupA);
        String runId = fx.insertFinishedRun();

        AtomicInteger analyzeCalls = new AtomicInteger();
        InvocationHandler handler = (proxy, method, args) -> {
            if ("analyze".equals(method.getName())) {
                analyzeCalls.incrementAndGet();
            }
            return method.invoke(realSolutionManager, args);
        };
        SolutionManager<GroupPlanSolution, HardMediumSoftLongScore> countingSolutionManager =
                (SolutionManager<GroupPlanSolution, HardMediumSoftLongScore>) Proxy.newProxyInstance(
                        SolutionManager.class.getClassLoader(), new Class<?>[] {SolutionManager.class}, handler);

        MoveProbe countingProbe = new MoveProbe(countingSolutionManager);
        ExplanationService svc = new ExplanationService(
                activityPlanRepository, optimizationRunRepository, participantProfileRepository, trainingGroupRepository,
                coachAssignmentRepository, coachProfileRepository, personRepository, solverInputAssembler,
                countingSolutionManager, countingProbe, new ExplanationCache(), new WishAnalysisCache(), explanationRecordRepository, objectMapper);

        PersonExplanationResponse response = svc.explainPerson(fx.planId, runId, kalle);

        assertThat(response.selectedGroup()).isNotNull();
        assertThat(analyzeCalls.get()).isEqualTo(1);
    }
}
