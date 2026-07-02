package se.klubb.groupplanner.solver.assemble;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.file.Path;
import java.time.Instant;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import se.klubb.groupplanner.api.error.BadRequestException;
import se.klubb.groupplanner.domain.ActivityPlan;
import se.klubb.groupplanner.domain.SeasonPlan;
import se.klubb.groupplanner.domain.TrainingGroup;
import se.klubb.groupplanner.repo.ActivityPlanRepository;
import se.klubb.groupplanner.repo.SeasonPlanRepository;
import se.klubb.groupplanner.repo.TrainingGroupRepository;
import se.klubb.groupplanner.util.Uuid7;

/**
 * Pre-solve pin validation in {@link SolverInputAssembler} (design §5: "every pinned entity must
 * have a value present in its value range... violations are rejected before solve, so the solver
 * never sees an impossible pin"). Review fix 6 covers the null case specifically: {@code
 * GroupSchedule.trainingBlock} is NOT unassignable (design §1.4), so a LOCKED group whose
 * {@code assignedTrainingBlockId} is null is a structurally invalid pin — {@code @PlanningPin}
 * would freeze the null and the Construction Heuristic could never initialize the variable.
 */
@SpringBootTest
class SolverInputAssemblerValidationTest {

    @TempDir
    static Path dataDir;

    @DynamicPropertySource
    static void appDataDir(DynamicPropertyRegistry registry) {
        registry.add("app.data-dir", () -> dataDir.toString());
    }

    @Autowired
    private SolverInputAssembler assembler;
    @Autowired
    private SeasonPlanRepository seasonPlanRepository;
    @Autowired
    private ActivityPlanRepository activityPlanRepository;
    @Autowired
    private TrainingGroupRepository trainingGroupRepository;

    private String createPlan() {
        Instant now = Instant.now();
        SeasonPlan season = seasonPlanRepository.insert(new SeasonPlan(Uuid7.generate(), "VT26", null, null, "active", now, now));
        ActivityPlan plan = activityPlanRepository.insert(
                new ActivityPlan(Uuid7.generate(), season.id(), "Herr", "beach", "draft", 10, 8, 12, now, now));
        return plan.id();
    }

    @Test
    void lockedGroupWithNoAssignedTrainingBlockIsRejectedBeforeSolve() {
        String planId = createPlan();
        trainingGroupRepository.insert(new TrainingGroup(
                Uuid7.generate(), planId, "Grupp 1", 1, "beach", 8, 10, 12, 0, null, null,
                /* assignedTrainingBlockId */ null, /* locked */ true));

        assertThatThrownBy(() -> assembler.assemble(planId))
                .isInstanceOf(BadRequestException.class)
                .hasMessageContaining("locked but has no assigned training block");
    }

    @Test
    void unlockedGroupWithNoAssignedTrainingBlockAssemblesFine() {
        String planId = createPlan();
        trainingGroupRepository.insert(new TrainingGroup(
                Uuid7.generate(), planId, "Grupp 1", 1, "beach", 8, 10, 12, 0, null, null, null, false));

        AssembledProblem assembled = assembler.assemble(planId);

        assertThat(assembled.solution().getGroupSchedules()).hasSize(1);
        assertThat(assembled.solution().getGroupSchedules().get(0).getTrainingBlock()).isNull();
        assertThat(assembled.solution().getGroupSchedules().get(0).isPinned()).isFalse();
    }
}
