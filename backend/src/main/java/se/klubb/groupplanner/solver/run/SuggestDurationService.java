package se.klubb.groupplanner.solver.run;

import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import org.springframework.stereotype.Service;
import se.klubb.groupplanner.api.error.NotFoundException;
import se.klubb.groupplanner.domain.CustomFieldValue;
import se.klubb.groupplanner.domain.FieldDefinition;
import se.klubb.groupplanner.domain.ParticipantProfile;
import se.klubb.groupplanner.fields.ConstraintTypes;
import se.klubb.groupplanner.repo.ActivityPlanRepository;
import se.klubb.groupplanner.repo.CoachProfileRepository;
import se.klubb.groupplanner.repo.CustomFieldValueRepository;
import se.klubb.groupplanner.repo.FieldDefinitionRepository;
import se.klubb.groupplanner.repo.ParticipantProfileRepository;
import se.klubb.groupplanner.repo.TrainingBlockRepository;
import se.klubb.groupplanner.repo.TrainingGroupRepository;

/**
 * {@code POST /api/plans/{planId}/solve/suggest-duration} (v0.2.0, SUGGESTED OPTIMIZATION TIME):
 * proposes a wall-clock solve duration instead of making the user pick FAST/NORMAL/THOROUGH blind —
 * combines a one-time {@link SolveBenchmarkService} hardware measurement with this plan's own
 * problem size. See backend/docs/v020-notes.md for the full design rationale (formula choice,
 * calibration numbers, worked examples).
 *
 * <h2>The formula</h2>
 *
 * <pre>
 * rawSeconds = BASE_SECONDS
 *            + participants * groups * SIZE_FACTOR   // the interaction term: bigger group matrix
 *            + activeBlocks * BLOCK_FACTOR
 *            + coaches * COACH_FACTOR
 *            + wishes * WISH_FACTOR
 *            + customFieldConstraints * FIELD_FACTOR
 * adjustedSeconds = rawSeconds / machineSpeedFactor   // faster machine -> shorter suggestion
 * suggestedSeconds = nearestFriendlyStep(clamp(adjustedSeconds, 15, 600))
 * </pre>
 *
 * Every term is non-negative and monotone in its own input, and division by {@code
 * machineSpeedFactor} (itself always &gt; 0) is monotonically decreasing in the factor — so the
 * formula is monotone by construction: a strictly bigger problem (any dimension held constant or
 * increased) never produces a SMALLER suggestion, and a faster machine (bigger factor) never produces
 * a BIGGER one. Nearest-neighbour rounding onto the sorted "friendly steps" ladder preserves that
 * monotonicity (a non-decreasing step function of a non-decreasing input).
 *
 * <p>Constants are tuned so the task's own worked example lands almost exactly on its stated
 * "180 sekunder" answer (130 participants, 12 groups, 45 wishes, speed factor 1.2 -&gt; ~184-195s
 * raw, which snaps to the 180 step) — see backend/docs/v020-notes.md for the arithmetic.
 *
 * <h2>Idle-machine assumption (accepted trade-off)</h2>
 *
 * The hardware measurement behind {@code machineSpeedFactor} assumes an otherwise-idle machine — a
 * benchmark that happens to run while something else saturates the CPU reads as "slow hardware" and
 * inflates every suggestion for the rest of the session (the result is session-cached, {@link
 * SolveBenchmarkService}). The per-plan 409 guard in {@link #suggest} prevents the one in-app
 * contention source a user can create against the SAME plan (a benchmark racing that plan's own
 * solve), but deliberately does NOT serialize against solves on OTHER plans or against
 * out-of-process load: this is a single-user desktop tool, so cross-plan contention requires the
 * one user to deliberately race themselves, and a global "anything solving anywhere blocks every
 * suggestion" lock would be a worse trade than an occasionally conservative (longer) suggestion.
 * Documented as accepted, not defended against.
 */
@Service
public class SuggestDurationService {

    private static final double BASE_SECONDS = 20.0;
    private static final double SIZE_FACTOR = 0.10; // participants * groups
    private static final double BLOCK_FACTOR = 0.5; // activeBlocks
    private static final double COACH_FACTOR = 0.5; // coaches
    private static final double WISH_FACTOR = 1.0; // wishes
    private static final double FIELD_FACTOR = 0.5; // customFieldConstraints

    private static final int MIN_SECONDS = 15;
    private static final int MAX_SECONDS = 600;
    private static final int[] FRIENDLY_STEPS_SECONDS = {15, 30, 60, 90, 120, 180, 240, 300, 420, 600};

    /** {@code custom_field_value}-bearing constraint types counted as "wishes" (data VOLUME — how
     * many wish declarations exist), distinct from {@code customFieldConstraints} (model COMPLEXITY —
     * how many constraint-affecting fields are configured at all, regardless of how many
     * participants actually used them). */
    private static final Set<String> WISH_CONSTRAINT_TYPES = Set.of(
            ConstraintTypes.SAME_GROUP, ConstraintTypes.DIFFERENT_GROUP,
            ConstraintTypes.COACH_PREFERENCE, ConstraintTypes.COACH_FORBIDDEN);

    private final ActivityPlanRepository activityPlanRepository;
    private final ParticipantProfileRepository participantProfileRepository;
    private final TrainingGroupRepository trainingGroupRepository;
    private final TrainingBlockRepository trainingBlockRepository;
    private final CoachProfileRepository coachProfileRepository;
    private final FieldDefinitionRepository fieldDefinitionRepository;
    private final CustomFieldValueRepository customFieldValueRepository;
    private final SolveCoordinator solveCoordinator;
    private final SolveBenchmarkService benchmarkService;

    public SuggestDurationService(
            ActivityPlanRepository activityPlanRepository,
            ParticipantProfileRepository participantProfileRepository,
            TrainingGroupRepository trainingGroupRepository,
            TrainingBlockRepository trainingBlockRepository,
            CoachProfileRepository coachProfileRepository,
            FieldDefinitionRepository fieldDefinitionRepository,
            CustomFieldValueRepository customFieldValueRepository,
            SolveCoordinator solveCoordinator,
            SolveBenchmarkService benchmarkService) {
        this.activityPlanRepository = activityPlanRepository;
        this.participantProfileRepository = participantProfileRepository;
        this.trainingGroupRepository = trainingGroupRepository;
        this.trainingBlockRepository = trainingBlockRepository;
        this.coachProfileRepository = coachProfileRepository;
        this.fieldDefinitionRepository = fieldDefinitionRepository;
        this.customFieldValueRepository = customFieldValueRepository;
        this.solveCoordinator = solveCoordinator;
        this.benchmarkService = benchmarkService;
    }

    public record ProblemSize(
            int participants, int groups, int activeBlocks, int coaches, int wishes, int customFieldConstraints) {
    }

    public record SuggestDurationResponse(
            int suggestedSeconds, double machineSpeedFactor, long benchmarkMs, ProblemSize problemSize, String rationaleSv) {
    }

    public SuggestDurationResponse suggest(String activityPlanId) {
        if (activityPlanRepository.findById(activityPlanId).isEmpty()) {
            throw new NotFoundException("Activity plan not found: " + activityPlanId);
        }
        // Concurrency guard (task spec): the benchmark competes for CPU with an active solve, and a
        // problem-size snapshot mid-solve is meaningless anyway - same per-plan check every other
        // CPU/consistency-sensitive endpoint in this codebase uses (AssignmentLockController et al.).
        solveCoordinator.assertNoActiveSolve(activityPlanId);

        ProblemSize size = computeProblemSize(activityPlanId);
        SolveBenchmarkService.BenchmarkResult benchmark = benchmarkService.benchmark();

        int suggestedSeconds = computeSuggestedSeconds(size, benchmark.machineSpeedFactor());

        String rationaleSv = String.format(
                Locale.ROOT,
                "Baserat på %d spelare, %d grupper, %d önskemål och din dators hastighet (%.1f× referens) föreslås %d sekunder.",
                size.participants(), size.groups(), size.wishes(), benchmark.machineSpeedFactor(), suggestedSeconds);

        return new SuggestDurationResponse(suggestedSeconds, benchmark.machineSpeedFactor(), benchmark.benchmarkMs(), size, rationaleSv);
    }

    /**
     * The pure formula (class javadoc), factored out as its own package-visible static method so its
     * monotonicity properties (bigger problem -&gt; suggestion never shrinks; faster machine -&gt;
     * suggestion never grows) are directly unit-testable without needing to fake hardware or a real
     * DB-backed plan — see {@code SuggestDurationServiceTest}.
     */
    static int computeSuggestedSeconds(ProblemSize size, double machineSpeedFactor) {
        double rawSeconds = BASE_SECONDS
                + (double) size.participants() * size.groups() * SIZE_FACTOR
                + size.activeBlocks() * BLOCK_FACTOR
                + size.coaches() * COACH_FACTOR
                + size.wishes() * WISH_FACTOR
                + size.customFieldConstraints() * FIELD_FACTOR;
        double adjustedSeconds = rawSeconds / machineSpeedFactor;
        return snapToFriendlyStep(adjustedSeconds);
    }

    /** Cheap, independent COUNT-shaped queries (never the full {@code SolverInputAssembler}, which
     * would 400 on an ungenerated-groups plan and is far more expensive than a size estimate needs to
     * be) — recomputed on EVERY call (only the hardware benchmark itself is cached, per the task
     * spec). */
    private ProblemSize computeProblemSize(String activityPlanId) {
        List<ParticipantProfile> participants = participantProfileRepository.findByActivityPlanId(activityPlanId);
        int groups = trainingGroupRepository.countByActivityPlanId(activityPlanId);
        int activeBlocks = trainingBlockRepository.countActiveByActivityPlanId(activityPlanId);
        int coaches = coachProfileRepository.findByActivityPlanId(activityPlanId).size();

        List<FieldDefinition> fields = fieldDefinitionRepository.findVisibleToPlan(activityPlanId);
        Map<String, FieldDefinition> fieldById = new HashMap<>();
        fields.forEach(f -> fieldById.put(f.id(), f));
        int customFieldConstraints = (int) fields.stream().filter(f -> !ConstraintTypes.NONE.equals(f.constraintType())).count();

        int wishes = 0;
        for (ParticipantProfile p : participants) {
            for (CustomFieldValue cfv : customFieldValueRepository.findByEntity(CustomFieldValue.ENTITY_TYPE_PARTICIPANT, p.id())) {
                FieldDefinition field = fieldById.get(cfv.fieldDefinitionId());
                if (field != null && WISH_CONSTRAINT_TYPES.contains(field.constraintType())) {
                    wishes++;
                }
            }
        }

        return new ProblemSize(participants.size(), groups, activeBlocks, coaches, wishes, customFieldConstraints);
    }

    private static int snapToFriendlyStep(double seconds) {
        double clamped = Math.max(MIN_SECONDS, Math.min(MAX_SECONDS, seconds));
        int best = FRIENDLY_STEPS_SECONDS[0];
        double bestDistance = Double.MAX_VALUE;
        for (int step : FRIENDLY_STEPS_SECONDS) {
            double distance = Math.abs(step - clamped);
            if (distance < bestDistance) {
                bestDistance = distance;
                best = step;
            }
        }
        return best;
    }
}
