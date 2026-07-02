package se.klubb.groupplanner.solver.assemble;

import static org.assertj.core.api.Assertions.assertThat;

import ai.timefold.solver.core.api.score.analysis.ConstraintAnalysis;
import ai.timefold.solver.core.api.score.analysis.ScoreAnalysis;
import ai.timefold.solver.core.api.score.buildin.hardmediumsoftlong.HardMediumSoftLongScore;
import ai.timefold.solver.core.api.solver.ScoreAnalysisFetchPolicy;
import ai.timefold.solver.core.api.solver.SolutionManager;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import se.klubb.groupplanner.fields.ConstraintWeightOverrideRequest;
import se.klubb.groupplanner.fields.ConstraintWeightService;
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
import se.klubb.groupplanner.solver.constraints.ConstraintKeys;
import se.klubb.groupplanner.solver.domain.GroupPlanSolution;
import se.klubb.groupplanner.solver.regression.TestDatasetLoader;

/**
 * The verifier-mandated complement-key fan-out test (docs/design/05-solver-verification.md minor
 * finding): "a test asserting the fan-out (change weight -&gt; both constraints' applied weight
 * changes in ScoreAnalysis)". Covers both fan-out shapes {@link ConstraintKeys#COMPLEMENTS_OF}
 * implements: a parent WITH its own code key (groupSizeTarget -&gt; +groupSizeTargetEmpty) and a
 * parent WITHOUT one (lateTimeForLowerGroups -&gt; lateTimeTopGroups + lateTimeBottomGroups).
 */
@SpringBootTest
class ConstraintWeightFanOutTest {

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
    private ConstraintWeightService constraintWeightService;
    @Autowired
    private SolutionManager<GroupPlanSolution, HardMediumSoftLongScore> solutionManager;

    private String loadPlan() {
        TestDatasetLoader loader = new TestDatasetLoader(
                seasonPlanRepository, activityPlanRepository, personRepository, participantProfileRepository,
                playerAssignmentRepository, coachProfileRepository, coachTimeSlotRepository, timeSlotRepository,
                trainingBlockGenerationService, fieldDefinitionRepository, customFieldValueRepository, levelService,
                groupGenerator);
        return loader.load("small-10");
    }

    @Test
    void changingGroupSizeTargetWeightAlsoChangesItsEmptyGroupComplement() {
        String planId = loadPlan();
        constraintWeightService.applyOverrides(
                planId, List.of(new ConstraintWeightOverrideRequest(ConstraintKeys.GROUP_SIZE_TARGET, null, 77, null)));

        GroupPlanSolution solution = assembler.assemble(planId).solution();
        assertThat(solution.getConstraintWeightOverrides().getConstraintWeight(ConstraintKeys.GROUP_SIZE_TARGET))
                .isEqualTo(HardMediumSoftLongScore.ofSoft(77));
        assertThat(solution.getConstraintWeightOverrides().getConstraintWeight(ConstraintKeys.GROUP_SIZE_TARGET_EMPTY))
                .isEqualTo(HardMediumSoftLongScore.ofSoft(77));

        // ConstraintAnalysis.weight() is the DIRECTIONAL per-match factor (negative for a .penalize()
        // constraint, positive for .reward()) - both groupSizeTarget and its empty-group complement
        // are .penalize(), so -77 here (verified empirically); ConstraintWeightOverrides.
        // getConstraintWeight() above is the unsigned configured magnitude instead - two different,
        // both-legitimate representations of "the weight" in the Timefold API surface.
        ScoreAnalysis<HardMediumSoftLongScore> analysis = solutionManager.analyze(solution, ScoreAnalysisFetchPolicy.FETCH_SHALLOW);
        assertThat(weightOf(analysis, ConstraintKeys.GROUP_SIZE_TARGET)).isEqualTo(HardMediumSoftLongScore.ofSoft(-77));
        assertThat(weightOf(analysis, ConstraintKeys.GROUP_SIZE_TARGET_EMPTY)).isEqualTo(HardMediumSoftLongScore.ofSoft(-77));
    }

    @Test
    void changingGroupMinSizeSoftWeightAlsoChangesItsEmptyGroupComplement() {
        String planId = loadPlan();
        constraintWeightService.applyOverrides(
                planId, List.of(new ConstraintWeightOverrideRequest(ConstraintKeys.GROUP_MIN_SIZE_SOFT, null, 33, null)));

        GroupPlanSolution solution = assembler.assemble(planId).solution();
        assertThat(solution.getConstraintWeightOverrides().getConstraintWeight(ConstraintKeys.GROUP_MIN_SIZE_SOFT))
                .isEqualTo(HardMediumSoftLongScore.ofSoft(33));
        assertThat(solution.getConstraintWeightOverrides().getConstraintWeight(ConstraintKeys.GROUP_MIN_SIZE_EMPTY))
                .isEqualTo(HardMediumSoftLongScore.ofSoft(33));
    }

    @Test
    void changingLateTimeForLowerGroupsWeightChangesBothTopAndBottomDirections() {
        String planId = loadPlan();
        constraintWeightService.applyOverrides(
                planId, List.of(new ConstraintWeightOverrideRequest(ConstraintKeys.LATE_TIME_FOR_LOWER_GROUPS, null, 17, null)));

        GroupPlanSolution solution = assembler.assemble(planId).solution();
        // lateTimeForLowerGroups itself has no code key - only its two fan-out children resolve.
        assertThat(solution.getConstraintWeightOverrides().getConstraintWeight(ConstraintKeys.LATE_TIME_TOP_GROUPS))
                .isEqualTo(HardMediumSoftLongScore.ofSoft(17));
        assertThat(solution.getConstraintWeightOverrides().getConstraintWeight(ConstraintKeys.LATE_TIME_BOTTOM_GROUPS))
                .isEqualTo(HardMediumSoftLongScore.ofSoft(17));

        // Directional signs differ by design: lateTimeTopGroups penalizes (-17), lateTimeBottomGroups
        // rewards (+17) - both still driven by the SAME fanned-out configured magnitude (17).
        ScoreAnalysis<HardMediumSoftLongScore> analysis = solutionManager.analyze(solution, ScoreAnalysisFetchPolicy.FETCH_SHALLOW);
        assertThat(weightOf(analysis, ConstraintKeys.LATE_TIME_TOP_GROUPS)).isEqualTo(HardMediumSoftLongScore.ofSoft(-17));
        assertThat(weightOf(analysis, ConstraintKeys.LATE_TIME_BOTTOM_GROUPS)).isEqualTo(HardMediumSoftLongScore.ofSoft(17));
    }

    private static HardMediumSoftLongScore weightOf(ScoreAnalysis<HardMediumSoftLongScore> analysis, String constraintKey) {
        for (ConstraintAnalysis<HardMediumSoftLongScore> ca : analysis.constraintMap().values()) {
            if (constraintKey.equals(ca.constraintRef().constraintName())) {
                return ca.weight();
            }
        }
        throw new AssertionError("No constraint analysis entry for key " + constraintKey);
    }
}
