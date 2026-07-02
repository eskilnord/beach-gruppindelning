package se.klubb.groupplanner.solver.assemble;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import se.klubb.groupplanner.api.error.ConflictException;
import se.klubb.groupplanner.api.error.NotFoundException;
import se.klubb.groupplanner.domain.ActivityPlan;
import se.klubb.groupplanner.domain.CoachAssignment;
import se.klubb.groupplanner.domain.ParticipantProfile;
import se.klubb.groupplanner.domain.PlayerAssignment;
import se.klubb.groupplanner.domain.TrainingGroup;
import se.klubb.groupplanner.repo.ActivityPlanRepository;
import se.klubb.groupplanner.repo.CoachAssignmentRepository;
import se.klubb.groupplanner.repo.ParticipantProfileRepository;
import se.klubb.groupplanner.repo.PlayerAssignmentRepository;
import se.klubb.groupplanner.repo.TrainingBlockRepository;
import se.klubb.groupplanner.repo.TrainingGroupRepository;
import se.klubb.groupplanner.util.Uuid7;

/**
 * Deterministic group generation (docs/design/04-solver.md §7) — a plain service outside the
 * solver, run when the council creates/refreshes an activity plan's groups.
 *
 * <pre>
 * P = active participant count, T = defaultGroupTargetSize, B = active TrainingBlock count
 * groupCount = clamp(ceil(P / T), 1, B)
 * </pre>
 *
 * <p><b>Re-generation policy (simplified from §7's full spec for M6a):</b> if any existing group or
 * any assignment referencing this plan's groups is locked, regeneration is refused with a 409 (the
 * "Återställ grupper" explicit-confirmation UX itself is a frontend concern, out of backend scope).
 * Otherwise groups whose {@code groupOrder} still falls within the new count are updated in place
 * (preserving their id, hence any explanations/history keyed on it); tail groups are added or
 * removed to reach the new count. Deterministic: participants are sorted by {@code (estimatedLevel
 * DESC, id ASC)} before slicing into level bands, matching every other sort in this milestone's
 * determinism rules (ADR-007).
 */
@Service
public class GroupGenerator {

    private static final int FALLBACK_TARGET_SIZE = 10;
    private static final double FALLBACK_LEVEL = 500.0; // matches LevelService's own no-source midpoint.

    private final ActivityPlanRepository activityPlanRepository;
    private final ParticipantProfileRepository participantProfileRepository;
    private final TrainingBlockRepository trainingBlockRepository;
    private final TrainingGroupRepository trainingGroupRepository;
    private final PlayerAssignmentRepository playerAssignmentRepository;
    private final CoachAssignmentRepository coachAssignmentRepository;

    public GroupGenerator(
            ActivityPlanRepository activityPlanRepository,
            ParticipantProfileRepository participantProfileRepository,
            TrainingBlockRepository trainingBlockRepository,
            TrainingGroupRepository trainingGroupRepository,
            PlayerAssignmentRepository playerAssignmentRepository,
            CoachAssignmentRepository coachAssignmentRepository) {
        this.activityPlanRepository = activityPlanRepository;
        this.participantProfileRepository = participantProfileRepository;
        this.trainingBlockRepository = trainingBlockRepository;
        this.trainingGroupRepository = trainingGroupRepository;
        this.playerAssignmentRepository = playerAssignmentRepository;
        this.coachAssignmentRepository = coachAssignmentRepository;
    }

    @Transactional
    public List<TrainingGroup> generate(String activityPlanId) {
        ActivityPlan plan = activityPlanRepository.findById(activityPlanId)
                .orElseThrow(() -> new NotFoundException("Activity plan not found: " + activityPlanId));

        List<TrainingGroup> existing = trainingGroupRepository.findByActivityPlanId(activityPlanId);
        if (!existing.isEmpty()) {
            requireSafeToRegenerate(activityPlanId, existing);
        }

        List<ParticipantProfile> participants = participantProfileRepository.findByActivityPlanId(activityPlanId);
        List<ParticipantProfile> active = participants.stream().filter(p -> !p.waitlisted()).toList();
        int activeBlockCount = trainingBlockRepository.countActiveByActivityPlanId(activityPlanId);

        int target = plan.defaultGroupTargetSize() != null ? plan.defaultGroupTargetSize() : FALLBACK_TARGET_SIZE;
        int max = plan.defaultGroupMaxSize() != null ? plan.defaultGroupMaxSize() : target + 2;
        int min = plan.defaultGroupMinSize() != null ? plan.defaultGroupMinSize() : Math.max(1, target - 2);

        int groupCount = clamp(ceilDiv(Math.max(active.size(), 1), Math.max(target, 1)), 1, Math.max(activeBlockCount, 1));

        String prefix = (plan.category() != null && !plan.category().isBlank()) ? plan.category() : plan.name();
        List<double[]> bands = computeLevelBands(active, groupCount); // [min, max] per group, 1-indexed by list order

        Map<Integer, TrainingGroup> existingByOrder = new HashMap<>();
        for (TrainingGroup g : existing) {
            if (g.groupOrder() != null) {
                existingByOrder.put(g.groupOrder(), g);
            }
        }

        List<TrainingGroup> result = new ArrayList<>();
        for (int n = 1; n <= groupCount; n++) {
            String name = prefix + " " + n;
            double[] band = bands.get(n - 1);
            Double levelMin = active.isEmpty() ? null : band[0];
            Double levelMax = active.isEmpty() ? null : band[1];
            TrainingGroup existingGroup = existingByOrder.get(n);
            TrainingGroup group = (existingGroup != null)
                    ? new TrainingGroup(
                            existingGroup.id(), activityPlanId, name, n, plan.category(), min, target, max,
                            existingGroup.requiredCoachCount(), levelMin, levelMax,
                            existingGroup.assignedTrainingBlockId(), existingGroup.locked())
                    : new TrainingGroup(
                            Uuid7.generate(), activityPlanId, name, n, plan.category(), min, target, max,
                            1, levelMin, levelMax, null, false);
            if (existingGroup != null) {
                trainingGroupRepository.update(group);
            } else {
                trainingGroupRepository.insert(group);
            }
            result.add(group);
        }

        for (TrainingGroup g : existing) {
            if (g.groupOrder() == null || g.groupOrder() > groupCount) {
                trainingGroupRepository.deleteById(g.id());
            }
        }

        return result;
    }

    private void requireSafeToRegenerate(String activityPlanId, List<TrainingGroup> existing) {
        for (TrainingGroup g : existing) {
            if (g.locked()) {
                throw new ConflictException(
                        "Grupper är låsta - kan inte återskapa grupper utan uttrycklig bekräftelse (grupp: " + g.name() + ")");
            }
        }
        boolean anyLockedPlayer = playerAssignmentRepository.findByActivityPlanId(activityPlanId).stream()
                .anyMatch(PlayerAssignment::locked);
        if (anyLockedPlayer) {
            throw new ConflictException(
                    "Det finns låsta spelarplaceringar - kan inte återskapa grupper utan uttrycklig bekräftelse");
        }
        boolean anyLockedCoach = coachAssignmentRepository.findByActivityPlanId(activityPlanId).stream()
                .anyMatch(CoachAssignment::locked);
        if (anyLockedCoach) {
            throw new ConflictException(
                    "Det finns låsta tränarplaceringar - kan inte återskapa grupper utan uttrycklig bekräftelse");
        }
    }

    /**
     * Sorts active participants by {@code (estimatedLevel DESC, id ASC)} and slices into {@code
     * groupCount} contiguous, as-even-as-possible chunks (sizes differ by at most 1); the top slice
     * (highest levels) is index 0 (group 1). Returns {@code [min, max]} per slice; if there are no
     * active participants every band is {@code [FALLBACK_LEVEL, FALLBACK_LEVEL]} (unused - callers
     * treat empty-participant plans as null bands).
     */
    private static List<double[]> computeLevelBands(List<ParticipantProfile> active, int groupCount) {
        List<double[]> bands = new ArrayList<>(groupCount);
        if (active.isEmpty()) {
            for (int i = 0; i < groupCount; i++) {
                bands.add(new double[] {FALLBACK_LEVEL, FALLBACK_LEVEL});
            }
            return bands;
        }
        List<ParticipantProfile> sorted = active.stream()
                .sorted(Comparator
                        .comparingDouble((ParticipantProfile p) -> p.estimatedLevel() != null ? p.estimatedLevel() : FALLBACK_LEVEL)
                        .reversed()
                        .thenComparing(ParticipantProfile::id))
                .toList();
        int n = sorted.size();
        int base = n / groupCount;
        int remainder = n % groupCount;
        int index = 0;
        for (int g = 0; g < groupCount; g++) {
            int sliceSize = base + (g < remainder ? 1 : 0);
            if (sliceSize == 0) {
                bands.add(new double[] {FALLBACK_LEVEL, FALLBACK_LEVEL});
                continue;
            }
            double max = sorted.get(index).estimatedLevel() != null ? sorted.get(index).estimatedLevel() : FALLBACK_LEVEL;
            double min = max;
            for (int i = index; i < index + sliceSize; i++) {
                double level = sorted.get(i).estimatedLevel() != null ? sorted.get(i).estimatedLevel() : FALLBACK_LEVEL;
                max = Math.max(max, level);
                min = Math.min(min, level);
            }
            bands.add(new double[] {min, max});
            index += sliceSize;
        }
        return bands;
    }

    private static int ceilDiv(int a, int b) {
        return (a + b - 1) / b;
    }

    private static int clamp(int value, int lo, int hi) {
        return Math.max(lo, Math.min(hi, value));
    }
}
