package se.klubb.groupplanner.solver.assemble;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
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

        ExpectedGroupPlan expected = computeExpectedGroupPlan(plan, active, activeBlockCount);
        int groupCount = expected.groupCount();
        int min = expected.sizes().min();
        int target = expected.sizes().target();
        int max = expected.sizes().max();

        String prefix = (plan.category() != null && !plan.category().isBlank()) ? plan.category() : plan.name();
        List<double[]> bands = expected.levelBands(); // [min, max] per group, 1-indexed by list order

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
     * The sizing math {@link #generate} WOULD apply right now, without writing anything - the
     * single source of truth for {@code groupCount}/{@link EffectiveSizes}/level bands, shared by
     * {@link #generate} itself and {@link #checkSyncStatus} (WI-C, user feedback v0.4 #4: nothing
     * told the council that editing the plan's group-generation defaults left the already-generated
     * {@code training_group} rows out of sync). Keeping this logic in exactly one place means the
     * staleness dry-run can never drift from what an actual "Generera om grupper" would produce.
     */
    private ExpectedGroupPlan computeExpectedGroupPlan(ActivityPlan plan, List<ParticipantProfile> active, int activeBlockCount) {
        EffectiveSizes sizes = effectiveSizes(
                plan.defaultGroupTargetSize(), plan.defaultGroupMinSize(), plan.defaultGroupMaxSize());
        int groupCount = clamp(ceilDiv(Math.max(active.size(), 1), Math.max(sizes.target(), 1)), 1, Math.max(activeBlockCount, 1));
        List<double[]> bands = computeLevelBands(active, groupCount);
        applyLevelMinDefault(bands, plan.defaultLevelMin(), active.isEmpty());
        return new ExpectedGroupPlan(groupCount, sizes, bands, active.isEmpty());
    }

    /** {@link #computeExpectedGroupPlan}'s result: what {@link #generate} would write if run now. */
    private record ExpectedGroupPlan(int groupCount, EffectiveSizes sizes, List<double[]> levelBands, boolean noActiveParticipants) {
    }

    /**
     * WI-C ("re-run doesn't feel like it re-runs" root cause A, user feedback v0.4 #4): a read-only,
     * cheap staleness check for {@code GET /api/plans/{planId}/groups/sync-status} - DRY-RUN of
     * {@link #computeExpectedGroupPlan} (never writes to {@code training_group}, never touches the
     * solver) compared against the rows actually on disk. Reports one Swedish reason per difference
     * class found, in a fixed order: how many groups exist vs. how many the current participant
     * count/target would produce, whether the stored min/target/max still match the plan's current
     * defaults, and (only once the group count itself matches - a count mismatch already makes any
     * level comparison meaningless) whether the stored level bands still match what a fresh
     * generation would compute from the current participants/min-nivå. No groups yet and no
     * participants yet is NOT stale (there is nothing to generate); no groups yet with participants
     * present IS stale (the one prerequisite step - "Generera grupper" - was simply never run).
     */
    public SyncStatus checkSyncStatus(String activityPlanId) {
        ActivityPlan plan = activityPlanRepository.findById(activityPlanId)
                .orElseThrow(() -> new NotFoundException("Activity plan not found: " + activityPlanId));
        List<TrainingGroup> existing = trainingGroupRepository.findByActivityPlanId(activityPlanId);
        List<ParticipantProfile> participants = participantProfileRepository.findByActivityPlanId(activityPlanId);

        if (existing.isEmpty()) {
            return participants.isEmpty()
                    ? new SyncStatus(false, List.of())
                    : new SyncStatus(true, List.of("Inga grupper är genererade ännu."));
        }

        List<ParticipantProfile> active = participants.stream().filter(p -> !p.waitlisted()).toList();
        int activeBlockCount = trainingBlockRepository.countActiveByActivityPlanId(activityPlanId);
        ExpectedGroupPlan expected = computeExpectedGroupPlan(plan, active, activeBlockCount);

        List<String> reasons = new ArrayList<>();
        boolean countMatches = expected.groupCount() == existing.size();
        if (!countMatches) {
            reasons.add("Antalet deltagare motsvarar " + expected.groupCount() + " grupper men " + existing.size()
                    + " är genererade.");
        }

        TrainingGroup sample = existing.get(0); // generate() writes the SAME min/target/max to every group.
        if (!Objects.equals(sample.targetSize(), expected.sizes().target())
                || !Objects.equals(sample.minSize(), expected.sizes().min())
                || !Objects.equals(sample.maxSize(), expected.sizes().max())) {
            reasons.add("Gruppstorlekarna i planens inställningar (mål " + expected.sizes().target()
                    + ") matchar inte de genererade grupperna (mål " + sample.targetSize() + ").");
        }

        if (countMatches && levelBandsDiffer(existing, expected)) {
            reasons.add("Nivågränserna för grupperna matchar inte längre deltagarnas nivåer eller planens min-nivå.");
        }

        return new SyncStatus(!reasons.isEmpty(), reasons);
    }

    private static boolean levelBandsDiffer(List<TrainingGroup> existing, ExpectedGroupPlan expected) {
        Map<Integer, TrainingGroup> byOrder = new HashMap<>();
        for (TrainingGroup g : existing) {
            if (g.groupOrder() != null) {
                byOrder.put(g.groupOrder(), g);
            }
        }
        for (int n = 1; n <= expected.groupCount(); n++) {
            TrainingGroup g = byOrder.get(n);
            double[] band = expected.levelBands().get(n - 1);
            Double expectedMin = expected.noActiveParticipants() ? null : band[0];
            Double expectedMax = expected.noActiveParticipants() ? null : band[1];
            if (g == null || !Objects.equals(g.levelMin(), expectedMin) || !Objects.equals(g.levelMax(), expectedMax)) {
                return true;
            }
        }
        return false;
    }

    /** {@code GET /api/plans/{planId}/groups/sync-status} response shape - {@code reasons} is empty
     *  exactly when {@code stale} is {@code false}. */
    public record SyncStatus(boolean stale, List<String> reasons) {
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

    /** The post-fallback ("effective") group sizes, min/target/max. See {@link #effectiveSizes}. */
    public record EffectiveSizes(int min, int target, int max) {
    }

    /**
     * The group sizes generation will ACTUALLY use once fallbacks are applied to the plan's
     * (nullable) defaults: {@code target ?? 10}, {@code max ?? target+2}, {@code min ?? max(1,
     * target-2)}. Single source of truth for this formula - shared with {@code
     * ActivityPlanController#requireValidDefaults} (a partially-set triple like "only min=20" must
     * be rejected when it contradicts the fallback-derived values, v0.3.0 review fix) and mirrored
     * by {@code frontend/src/lib/planDefaults.ts#effectiveGroupSizeDefaults}.
     */
    public static EffectiveSizes effectiveSizes(Integer targetSize, Integer minSize, Integer maxSize) {
        int target = targetSize != null ? targetSize : FALLBACK_TARGET_SIZE;
        int max = maxSize != null ? maxSize : target + 2;
        int min = minSize != null ? minSize : Math.max(1, target - 2);
        return new EffectiveSizes(min, target, max);
    }

    /**
     * "Standard min-nivå" (v0.3.0 user feedback): a plan-level floor below which groups should not be
     * defined. {@code bands} is ordered highest-level-first (index 0 = group 1, per {@link
     * #computeLevelBands}), so the LOWEST group is always the last element. When {@code
     * defaultLevelMin} is set, that band's floor is raised to it in place - but never above the same
     * band's own ceiling, which would otherwise invert {@code levelMin > levelMax} if the configured
     * floor happens to sit above every participant in the lowest group. No-op when unset or when
     * there were no active participants (bands then holds the unused {@code FALLBACK_LEVEL} sentinel,
     * not a real band - see {@link #generate} which nulls out levelMin/levelMax in that case anyway).
     */
    private static void applyLevelMinDefault(List<double[]> bands, Double defaultLevelMin, boolean noActiveParticipants) {
        if (defaultLevelMin == null || noActiveParticipants || bands.isEmpty()) {
            return;
        }
        double[] lowestBand = bands.get(bands.size() - 1);
        lowestBand[0] = Math.min(Math.max(lowestBand[0], defaultLevelMin), lowestBand[1]);
    }

    private static int ceilDiv(int a, int b) {
        return (a + b - 1) / b;
    }

    private static int clamp(int value, int lo, int hi) {
        return Math.max(lo, Math.min(hi, value));
    }
}
