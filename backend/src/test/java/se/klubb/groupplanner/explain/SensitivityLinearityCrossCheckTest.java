package se.klubb.groupplanner.explain;

import static ai.timefold.solver.core.api.solver.ScoreAnalysisFetchPolicy.FETCH_ALL;
import static org.assertj.core.api.Assertions.assertThat;

import ai.timefold.solver.core.api.domain.solution.ConstraintWeightOverrides;
import ai.timefold.solver.core.api.score.analysis.ScoreAnalysis;
import ai.timefold.solver.core.api.score.buildin.hardmediumsoftlong.HardMediumSoftLongScore;
import ai.timefold.solver.core.api.solver.SolutionManager;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import se.klubb.groupplanner.domain.CustomFieldValue;
import se.klubb.groupplanner.fields.PriorityOrder;
import se.klubb.groupplanner.repo.ActivityPlanRepository;
import se.klubb.groupplanner.repo.CoachAssignmentRepository;
import se.klubb.groupplanner.repo.CoachProfileRepository;
import se.klubb.groupplanner.repo.CustomFieldValueRepository;
import se.klubb.groupplanner.repo.FieldDefinitionRepository;
import se.klubb.groupplanner.repo.OptimizationRunRepository;
import se.klubb.groupplanner.repo.ParticipantProfileRepository;
import se.klubb.groupplanner.repo.PersonRepository;
import se.klubb.groupplanner.repo.PlayerAssignmentRepository;
import se.klubb.groupplanner.repo.SeasonPlanRepository;
import se.klubb.groupplanner.repo.TimeSlotRepository;
import se.klubb.groupplanner.repo.TrainingGroupRepository;
import se.klubb.groupplanner.resources.TrainingBlockGenerationService;
import se.klubb.groupplanner.solver.assemble.AssembledProblem;
import se.klubb.groupplanner.solver.assemble.SolverInputAssembler;
import se.klubb.groupplanner.solver.constraints.ConstraintKeys;
import se.klubb.groupplanner.solver.domain.Group;
import se.klubb.groupplanner.solver.domain.GroupPlanSolution;
import se.klubb.groupplanner.solver.domain.PlayerAssignment;

/**
 * M-E3 "the license": extends the M-E0 spike's own re-analyze-and-compare method ({@code
 * WeightSensitivityLinearityTest}) to {@link PrioritySensitivityCalculator} itself — the production
 * code this milestone ships, not a hand-derived prediction. For ONE real fixture probe (the exact
 * Kalle/Lisa friend-wish-vs-time-wish TRADE_OFF shape {@code CausalNarrativeTruthfulnessTest} and
 * {@code NoClaimWithoutProbeTest} both already pin), takes 3 of the calculator's 24 predicted
 * permutations and ACTUALLY re-analyzes baseline/moved under each one's {@code
 * ConstraintWeightOverrides}, asserting the calculator's {@code predictedSoftDelta} against the real
 * {@code diff.score()} — exact equality, not an approximation — closing the loop the spike's own
 * design-consequence section promised: "a pure dot product with no re-solve".
 */
@SpringBootTest
class SensitivityLinearityCrossCheckTest {

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
    private SolverInputAssembler assembler;
    @Autowired
    private SolutionManager<GroupPlanSolution, HardMediumSoftLongScore> solutionManager;
    @Autowired
    private MoveProbe moveProbe;
    @Autowired
    private JdbcClient jdbcClient;

    @Test
    void predictedSoftDeltaMatchesARealReAnalysisAcrossThreePermutations() {
        ExplanationTestFixture fx = new ExplanationTestFixture(
                seasonPlanRepository, activityPlanRepository, personRepository, participantProfileRepository,
                playerAssignmentRepository, trainingGroupRepository, timeSlotRepository, trainingBlockGenerationService,
                fieldDefinitionRepository, customFieldValueRepository, optimizationRunRepository,
                coachProfileRepository, coachAssignmentRepository);
        List<String> blocksA = fx.addTimeSlotWithBlocks("Torsdag 18.00-19.30", 1);
        List<String> blocksB = fx.addTimeSlotWithBlocks("Fredag 20.00-21.00", 1);
        String groupA = fx.addGroup("Grupp A", 1, 0, 0, 10, blocksA.get(0));
        fx.addGroup("Grupp B", 2, 0, 0, 10, blocksB.get(0));

        String kalle = fx.addParticipant("Kalle", "Karlsson", 500.0, 3);
        String lisa = fx.addParticipant("Lisa", "Larsson", 500.0, 3);
        fx.place(kalle, groupA);
        fx.place(lisa, groupA);
        fx.wish(kalle, lisa, "playWith"); // WANT_SAME, currently satisfied - breaks on move to B.
        String preferredSlotId = timeSlotIdOfBlock(blocksB.get(0));
        String fieldId = fieldDefinitionRepository.findGlobalByKey("preferTimes").orElseThrow().id();
        customFieldValueRepository.upsert(fieldId, CustomFieldValue.ENTITY_TYPE_PARTICIPANT, kalle, "[\"" + preferredSlotId + "\"]");

        AssembledProblem assembled = assembler.assemble(fx.planId);
        GroupPlanSolution solution = assembled.solution();
        ScoreAnalysis<HardMediumSoftLongScore> baseAnalysis = solutionManager.analyze(solution, FETCH_ALL);

        PlayerAssignment target = solution.getPlayerAssignments().stream()
                .filter(pa -> "Kalle Karlsson".equals(pa.getDisplayName()))
                .findFirst().orElseThrow();
        Group originalGroup = target.getGroup();
        Group candidateGroup = solution.getGroups().stream().filter(g -> g.groupOrder() == 2).findFirst().orElseThrow();

        SolutionIndex idx = SolutionIndex.of(solution);
        MoveProbe.Result result = moveProbe.evaluate(solution, baseAnalysis, target, candidateGroup, idx);
        assertThat(result.wouldBreakHard()).isFalse();
        assertThat(result.scoreDelta().hardScore()).isZero();
        assertThat(result.scoreDelta().mediumScore()).isZero();

        Map<String, HardMediumSoftLongScore> currentWeights = PrioritySensitivityCalculator.currentWeightsOf(solution);
        PrioritySensitivityCalculator.Computation computation = PrioritySensitivityCalculator.compute(
                result.perConstraint(), currentWeights, ConstraintKeys.TIME_PREFERENCE_SOFT, candidateGroup.name());
        assertThat(computation.available()).isTrue();
        assertThat(computation.orderings()).hasSize(24);

        // Three permutations: the plan's own CURRENT order (index 0 - PriorityOrder.defaultOrder()),
        // the calculator's own predicted FLIP (its suggestedOrder - a real "would help" claim), and
        // one more arbitrary permutation for extra coverage.
        PrioritySensitivityCalculator.Ordering currentOrdering = computation.orderings().get(0);
        PrioritySensitivityCalculator.Ordering flipOrdering = computation.orderings().stream()
                .filter(o -> o.order().equals(computation.suggestedOrder()))
                .findFirst().orElseThrow();
        PrioritySensitivityCalculator.Ordering thirdOrdering = computation.orderings().get(15);

        for (PrioritySensitivityCalculator.Ordering ordering : List.of(currentOrdering, flipOrdering, thirdOrdering)) {
            Map<String, HardMediumSoftLongScore> vector = new LinkedHashMap<>(currentWeights);
            PriorityOrder.weightsFor(ordering.order()).forEach((key, magnitude) -> vector.put(key, HardMediumSoftLongScore.ofSoft(magnitude)));

            solution.setConstraintWeightOverrides(ConstraintWeightOverrides.of(vector));
            ScoreAnalysis<HardMediumSoftLongScore> baseV = solutionManager.analyze(solution, FETCH_ALL);
            target.setGroup(candidateGroup);
            ScoreAnalysis<HardMediumSoftLongScore> movedV = solutionManager.analyze(solution, FETCH_ALL);
            target.setGroup(originalGroup);
            ScoreAnalysis<HardMediumSoftLongScore> diffV = movedV.diff(baseV);

            assertThat(diffV.score().hardScore()).as("hard component under order %s", ordering.order()).isZero();
            assertThat(diffV.score().mediumScore()).as("medium component under order %s", ordering.order()).isZero();
            assertThat(diffV.score().softScore())
                    .as("predicted soft delta under order %s must exactly match a real re-analysis", ordering.order())
                    .isEqualTo(ordering.predictedSoftDelta());
            assertThat(ordering.nonWorse()).isEqualTo(diffV.score().softScore() >= 0);
        }

        // Restore the plan's own weight vector so no test-local mutation leaks (defensive - this
        // solution instance is local to this test method, but keeps the intent explicit).
        solution.setConstraintWeightOverrides(ConstraintWeightOverrides.of(currentWeights));
    }

    private String timeSlotIdOfBlock(String blockId) {
        return jdbcClient.sql("SELECT time_slot_id FROM training_block WHERE id = :id").param("id", blockId).query(String.class).single();
    }
}
