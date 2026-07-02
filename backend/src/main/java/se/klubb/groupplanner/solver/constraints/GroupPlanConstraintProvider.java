package se.klubb.groupplanner.solver.constraints;

import ai.timefold.solver.core.api.score.buildin.hardmediumsoftlong.HardMediumSoftLongScore;
import ai.timefold.solver.core.api.score.stream.Constraint;
import ai.timefold.solver.core.api.score.stream.ConstraintCollectors;
import ai.timefold.solver.core.api.score.stream.ConstraintFactory;
import ai.timefold.solver.core.api.score.stream.ConstraintProvider;
import ai.timefold.solver.core.api.score.stream.Joiners;
import ai.timefold.solver.core.api.score.stream.uni.UniConstraintStream;
import java.util.List;
import se.klubb.groupplanner.solver.constraints.Justifications.BlockDoubleBookedJustification;
import se.klubb.groupplanner.solver.constraints.Justifications.CoachDoubleBookedJustification;
import se.klubb.groupplanner.solver.constraints.Justifications.CoachLevelMismatchJustification;
import se.klubb.groupplanner.solver.constraints.Justifications.CoachOverloadedJustification;
import se.klubb.groupplanner.solver.constraints.Justifications.CoachPreferredTimeSlotJustification;
import se.klubb.groupplanner.solver.constraints.Justifications.CoachUnavailableJustification;
import se.klubb.groupplanner.solver.constraints.Justifications.CoachWishJustification;
import se.klubb.groupplanner.solver.constraints.Justifications.ContinuityJustification;
import se.klubb.groupplanner.solver.constraints.Justifications.GroupOrderInversionJustification;
import se.klubb.groupplanner.solver.constraints.Justifications.GroupOverMaxJustification;
import se.klubb.groupplanner.solver.constraints.Justifications.GroupSizeDeviationJustification;
import se.klubb.groupplanner.solver.constraints.Justifications.GroupUnderMinJustification;
import se.klubb.groupplanner.solver.constraints.Justifications.LateTimeJustification;
import se.klubb.groupplanner.solver.constraints.Justifications.LevelSpreadJustification;
import se.klubb.groupplanner.solver.constraints.Justifications.MissingCoachJustification;
import se.klubb.groupplanner.solver.constraints.Justifications.PairWishBrokenJustification;
import se.klubb.groupplanner.solver.constraints.Justifications.PairWishSoftJustification;
import se.klubb.groupplanner.solver.constraints.Justifications.PlayerDoubleBookedJustification;
import se.klubb.groupplanner.solver.constraints.Justifications.SavedPlanClashJustification;
import se.klubb.groupplanner.solver.constraints.Justifications.TimePreferenceMissedJustification;
import se.klubb.groupplanner.solver.constraints.Justifications.TimeUnavailableJustification;
import se.klubb.groupplanner.solver.constraints.Justifications.TrainAndCoachClashJustification;
import se.klubb.groupplanner.solver.constraints.Justifications.UnassignedPlayerJustification;
import se.klubb.groupplanner.solver.domain.CoachFact;
import se.klubb.groupplanner.solver.domain.CoachSlot;
import se.klubb.groupplanner.solver.domain.CoachWish;
import se.klubb.groupplanner.solver.domain.CoachWishType;
import se.klubb.groupplanner.solver.domain.Group;
import se.klubb.groupplanner.solver.domain.GroupSchedule;
import se.klubb.groupplanner.solver.domain.LateTimePolicy;
import se.klubb.groupplanner.solver.domain.PersonPairWish;
import se.klubb.groupplanner.solver.domain.PlayerAssignment;
import se.klubb.groupplanner.solver.domain.SavedPlanResourceUsage;
import se.klubb.groupplanner.solver.domain.UsageType;
import se.klubb.groupplanner.solver.domain.WishType;

/**
 * Constraint Streams provider (docs/design/04-solver.md §4). Every HARD row of the design's
 * per-constraint table, {@code unassignedPlayer} (MEDIUM, the reserved waitlist penalty, §2), and
 * (M6b) every SOFT row: level balance, group ordering, continuity, time/coach/pair preferences,
 * coach level fit, late-time policy, and the M6b-new {@code coachPreferredTimeSlot}.
 *
 * <p>Two HARD rows have no Constraint Streams code because they are satisfied by construction
 * (§1.4/§5, see {@link ConstraintKeys#GROUP_REQUIRES_TRAINING_BLOCK}/{@link
 * ConstraintKeys#LOCKED_ASSIGNMENT_HARD}): {@code trainingBlock} is a non-unassignable planning
 * variable and {@code GroupGenerator} guarantees a legal value always exists, and {@code
 * @PlanningPin} on all three entity classes makes a solver move onto a pinned entity structurally
 * impossible. Both are covered by model-level tests instead of {@code ConstraintVerifier}.
 *
 * <p><b>Empty-group blindness (documented per docs/design/05-solver-verification.md minor finding,
 * "Accept and document" option):</b> {@link #groupOrderByLevel} and {@link #coachLevelFit} both key
 * off {@link #groupLevelStats}, a {@code groupBy(PlayerAssignment::getGroup, ...)} aggregation which
 * — like every {@code groupBy} over {@code forEach(PlayerAssignment.class)} — never produces a tuple
 * for a group with zero (non-waitlisted) members. An empty group is therefore invisible to both
 * constraints: {@code groupOrderByLevel}'s adjacent-pair chain silently skips any inversion
 * involving it, and a coach assigned to an empty group is never checked against a level band at all.
 * This is accepted, not fixed, for two reasons: (1) an empty group is already under continuous soft
 * pressure to fill from {@link #groupMinSizeSoft}/{@link #groupSizeTarget}'s empty-group complements
 * ({@link #groupMinSizeEmpty}/{@link #groupSizeTargetEmpty}), which exist precisely because grouped
 * stats go blind at zero members; (2) substituting the group's seeded level band midpoint (the
 * verifier's alternative fix) would make ordering/coach-fit react to a *pre-solve estimate* instead
 * of the solver's own placements, which is more surprising, not less. Explanations built on these
 * constraints (M7) must not claim ordering/coach-fit was checked for an empty group.
 */
public class GroupPlanConstraintProvider implements ConstraintProvider {

    @Override
    public Constraint[] defineConstraints(ConstraintFactory factory) {
        return new Constraint[] {
            trainingBlockCapacity(factory),
            groupMaxSizeHard(factory),
            timeAvailabilityHard(factory),
            sameGroupHard(factory),
            differentGroupHard(factory),
            coachNoOverlap(factory),
            playerNoOverlap(factory),
            coachCannotTrainAndCoachSameTime(factory),
            coachAvailabilityHard(factory),
            coachRequirementHard(factory),
            coachMaxGroups(factory),
            coachWishRequired(factory),
            coachWishForbidden(factory),
            savedPlanPersonBlocked(factory),
            savedPlanCoachBlocked(factory),
            savedPlanCourtBlocked(factory),
            unassignedPlayer(factory),
            groupSizeTarget(factory),
            groupSizeTargetEmpty(factory),
            groupMinSizeSoft(factory),
            groupMinSizeEmpty(factory),
            levelBalance(factory),
            groupOrderByLevel(factory),
            previousGroupContinuity(factory),
            timePreferenceSoft(factory),
            sameGroupSoft(factory),
            differentGroupSoft(factory),
            coachLevelFit(factory),
            coachPreferenceSoft(factory),
            lateTimeTopGroups(factory),
            lateTimeBottomGroups(factory),
            coachPreferredTimeSlot(factory),
        };
    }

    // ─────────────────────────────────────────────────────────────────────── §10.1

    Constraint trainingBlockCapacity(ConstraintFactory f) {
        return f.forEachUniquePair(GroupSchedule.class, Joiners.equal(GroupSchedule::getTrainingBlock))
                .penalize(HardMediumSoftLongScore.ofHard(1))
                .justifyWith((gs1, gs2, score) -> new BlockDoubleBookedJustification(
                        gs1.getTrainingBlock().id(), gs1.getGroup().id(), gs2.getGroup().id()))
                .asConstraint(ConstraintKeys.TRAINING_BLOCK_CAPACITY);
    }

    // ─────────────────────────────────────────────────────────────────────── §10.3

    Constraint groupMaxSizeHard(ConstraintFactory f) {
        return f.forEach(PlayerAssignment.class)
                .groupBy(PlayerAssignment::getGroup, ConstraintCollectors.count())
                .filter((group, count) -> count > group.maxSize())
                .penalize(HardMediumSoftLongScore.ofHard(1), (group, count) -> count - group.maxSize())
                .justifyWith((group, count, score) -> new GroupOverMaxJustification(group.id(), count, group.maxSize()))
                .asConstraint(ConstraintKeys.GROUP_MAX_SIZE_HARD);
    }

    // ─────────────────────────────────────────────────────────────────────── §10.9

    Constraint timeAvailabilityHard(ConstraintFactory f) {
        return f.forEach(PlayerAssignment.class)
                .join(GroupSchedule.class, Joiners.equal(PlayerAssignment::getGroup, GroupSchedule::getGroup))
                .filter((pa, gs) -> !pa.canAttend(gs.getTrainingBlock().timeSlotId()))
                .penalize(HardMediumSoftLongScore.ofHard(1))
                .justifyWith((pa, gs, score) -> new TimeUnavailableJustification(
                        pa.getId(), gs.getGroup().id(), gs.getTrainingBlock().timeSlotId(), gs.getTrainingBlock().label()))
                .asConstraint(ConstraintKeys.TIME_AVAILABILITY_HARD);
    }

    // ─────────────────────────────────────────────────────────────────────── §10.11 / §10.13

    Constraint sameGroupHard(ConstraintFactory f) {
        return f.forEach(PersonPairWish.class)
                .filter(w -> w.type() == WishType.MUST_SAME)
                .join(PlayerAssignment.class, Joiners.equal(PersonPairWish::aParticipantProfileId, PlayerAssignment::getId))
                .join(PlayerAssignment.class, Joiners.equal((w, a) -> w.bParticipantProfileId(), PlayerAssignment::getId))
                .filter((w, a, b) -> a.getGroup() != b.getGroup())
                .penalize(HardMediumSoftLongScore.ofHard(1))
                .justifyWith((w, a, b, score) -> new PairWishBrokenJustification(
                        w.fieldDefinitionId(), a.getId(), b.getId(), "MUST_SAME"))
                .asConstraint(ConstraintKeys.SAME_GROUP_HARD);
    }

    Constraint differentGroupHard(ConstraintFactory f) {
        return f.forEach(PersonPairWish.class)
                .filter(w -> w.type() == WishType.MUST_DIFFERENT)
                .join(PlayerAssignment.class, Joiners.equal(PersonPairWish::aParticipantProfileId, PlayerAssignment::getId))
                .join(PlayerAssignment.class, Joiners.equal((w, a) -> w.bParticipantProfileId(), PlayerAssignment::getId))
                .filter((w, a, b) -> a.getGroup() == b.getGroup())
                .penalize(HardMediumSoftLongScore.ofHard(1))
                .justifyWith((w, a, b, score) -> new PairWishBrokenJustification(
                        w.fieldDefinitionId(), a.getId(), b.getId(), "MUST_DIFFERENT"))
                .asConstraint(ConstraintKeys.DIFFERENT_GROUP_HARD);
    }

    // ─────────────────────────────────────────────────────────────────────── §10.15

    Constraint coachNoOverlap(ConstraintFactory f) {
        return f.forEachUniquePair(CoachSlot.class, Joiners.equal(CoachSlot::getCoach))
                .join(GroupSchedule.class, Joiners.equal((c1, c2) -> c1.getGroup(), GroupSchedule::getGroup))
                .join(GroupSchedule.class, Joiners.equal((c1, c2, gs1) -> c2.getGroup(), GroupSchedule::getGroup))
                .filter((c1, c2, gs1, gs2) -> gs1.getTrainingBlock().timeKey().overlaps(gs2.getTrainingBlock().timeKey()))
                .penalize(HardMediumSoftLongScore.ofHard(1))
                .justifyWith((c1, c2, gs1, gs2, score) -> new CoachDoubleBookedJustification(
                        c1.getCoach().personId(), c1.getGroup().id(), c2.getGroup().id(), gs1.getTrainingBlock().label()))
                .asConstraint(ConstraintKeys.COACH_NO_OVERLAP);
    }

    // ─────────────────────────────────────────────────────────────────────── §10.16

    Constraint playerNoOverlap(ConstraintFactory f) {
        return f.forEachUniquePair(PlayerAssignment.class, Joiners.equal(PlayerAssignment::getPersonId))
                .join(GroupSchedule.class, Joiners.equal((a, b) -> a.getGroup(), GroupSchedule::getGroup))
                .join(GroupSchedule.class, Joiners.equal((a, b, gs1) -> b.getGroup(), GroupSchedule::getGroup))
                .filter((a, b, gs1, gs2) -> gs1.getTrainingBlock().timeKey().overlaps(gs2.getTrainingBlock().timeKey()))
                .penalize(HardMediumSoftLongScore.ofHard(1))
                .justifyWith((a, b, gs1, gs2, score) -> new PlayerDoubleBookedJustification(
                        a.getPersonId(), a.getGroup().id(), b.getGroup().id()))
                .asConstraint(ConstraintKeys.PLAYER_NO_OVERLAP);
    }

    // ─────────────────────────────────────────────────────────────────────── §10.17

    Constraint coachCannotTrainAndCoachSameTime(ConstraintFactory f) {
        return f.forEach(CoachSlot.class)
                .join(PlayerAssignment.class, Joiners.equal(cs -> cs.getCoach().personId(), PlayerAssignment::getPersonId))
                .join(GroupSchedule.class, Joiners.equal((cs, pa) -> cs.getGroup(), GroupSchedule::getGroup))
                .join(GroupSchedule.class, Joiners.equal((cs, pa, gs1) -> pa.getGroup(), GroupSchedule::getGroup))
                .filter((cs, pa, gs1, gs2) -> gs1.getTrainingBlock().timeKey().overlaps(gs2.getTrainingBlock().timeKey()))
                .penalize(HardMediumSoftLongScore.ofHard(1))
                .justifyWith((cs, pa, gs1, gs2, score) -> new TrainAndCoachClashJustification(
                        pa.getPersonId(), cs.getGroup().id(), pa.getGroup().id(), gs1.getTrainingBlock().label()))
                .asConstraint(ConstraintKeys.COACH_CANNOT_TRAIN_AND_COACH_SAME_TIME);
    }

    // ─────────────────────────────────────────────────────────────────────── §10.18

    Constraint coachAvailabilityHard(ConstraintFactory f) {
        return f.forEach(CoachSlot.class)
                .join(GroupSchedule.class, Joiners.equal(CoachSlot::getGroup, GroupSchedule::getGroup))
                .filter((cs, gs) -> !cs.getCoach().availableAt(gs.getTrainingBlock().timeSlotId()))
                .penalize(HardMediumSoftLongScore.ofHard(1))
                .justifyWith((cs, gs, score) -> new CoachUnavailableJustification(
                        cs.getCoach().personId(), cs.getGroup().id(), gs.getTrainingBlock().timeSlotId()))
                .asConstraint(ConstraintKeys.COACH_AVAILABILITY_HARD);
    }

    // ─────────────────────────────────────────────────────────────────────── §10.19

    Constraint coachRequirementHard(ConstraintFactory f) {
        return f.forEachIncludingUnassigned(CoachSlot.class)
                .filter(cs -> cs.getCoach() == null)
                .penalize(HardMediumSoftLongScore.ofHard(1))
                .justifyWith((cs, score) -> new MissingCoachJustification(cs.getGroup().id(), cs.getSlotIndex()))
                .asConstraint(ConstraintKeys.COACH_REQUIREMENT_HARD);
    }

    // ─────────────────────────────────────────────────────────────────────── coachMaxGroups (spec §13.1)

    Constraint coachMaxGroups(ConstraintFactory f) {
        return f.forEach(CoachSlot.class)
                .groupBy(CoachSlot::getCoach, ConstraintCollectors.count())
                .filter((coach, n) -> n > coach.maxGroups())
                .penalize(HardMediumSoftLongScore.ofHard(1), (coach, n) -> n - coach.maxGroups())
                .justifyWith((coach, n, score) -> new CoachOverloadedJustification(coach.personId(), n, coach.maxGroups()))
                .asConstraint(ConstraintKeys.COACH_MAX_GROUPS);
    }

    // ─────────────────────────────────────────────────────────────────────── §10.21b / §10.21c

    Constraint coachWishRequired(ConstraintFactory f) {
        return f.forEach(CoachWish.class)
                .filter(w -> w.type() == CoachWishType.MUST)
                .join(PlayerAssignment.class, Joiners.equal(CoachWish::participantProfileId, PlayerAssignment::getId))
                .ifNotExists(
                        CoachSlot.class,
                        Joiners.equal((w, pa) -> pa.getGroup(), CoachSlot::getGroup),
                        Joiners.filtering((w, pa, cs) -> cs.getCoach() != null && cs.getCoach().personId() == w.coachPersonId()))
                .penalize(HardMediumSoftLongScore.ofHard(1))
                .justifyWith((w, pa, score) -> new CoachWishJustification(pa.getId(), w.coachPersonId(), "MUST", false))
                .asConstraint(ConstraintKeys.COACH_WISH_REQUIRED);
    }

    Constraint coachWishForbidden(ConstraintFactory f) {
        return f.forEach(CoachWish.class)
                .filter(w -> w.type() == CoachWishType.CANNOT)
                .join(PlayerAssignment.class, Joiners.equal(CoachWish::participantProfileId, PlayerAssignment::getId))
                .ifExists(
                        CoachSlot.class,
                        Joiners.equal((w, pa) -> pa.getGroup(), CoachSlot::getGroup),
                        Joiners.filtering((w, pa, cs) -> cs.getCoach() != null && cs.getCoach().personId() == w.coachPersonId()))
                .penalize(HardMediumSoftLongScore.ofHard(1))
                .justifyWith((w, pa, score) -> new CoachWishJustification(pa.getId(), w.coachPersonId(), "CANNOT", true))
                .asConstraint(ConstraintKeys.COACH_WISH_FORBIDDEN);
    }

    // ─────────────────────────────────────────────────────────────────────── §10.24a/b/c
    // Always zero matches in M6a: SolverInputAssembler always supplies an empty
    // savedPlanResourceUsages list (see SavedPlanResourceUsage javadoc). Implemented and unit-tested
    // now so M6b/M8 only need to wire real facts, not new constraint code.

    Constraint savedPlanPersonBlocked(ConstraintFactory f) {
        return f.forEach(PlayerAssignment.class)
                .join(GroupSchedule.class, Joiners.equal(PlayerAssignment::getGroup, GroupSchedule::getGroup))
                .join(
                        SavedPlanResourceUsage.class,
                        Joiners.equal((pa, gs) -> pa.getPersonId(), SavedPlanResourceUsage::personId),
                        Joiners.filtering((pa, gs, u) ->
                                u.type() == UsageType.PERSON && u.timeKey().overlaps(gs.getTrainingBlock().timeKey())))
                .penalize(HardMediumSoftLongScore.ofHard(1))
                .justifyWith((pa, gs, u, score) -> new SavedPlanClashJustification(
                        gs.getGroup().id(), pa.getPersonId(), u.sourcePlanName(), gs.getTrainingBlock().label(), "PLAYER"))
                .asConstraint(ConstraintKeys.SAVED_PLAN_PERSON_BLOCKED);
    }

    Constraint savedPlanCoachBlocked(ConstraintFactory f) {
        return f.forEach(CoachSlot.class)
                .join(GroupSchedule.class, Joiners.equal(CoachSlot::getGroup, GroupSchedule::getGroup))
                .join(
                        SavedPlanResourceUsage.class,
                        Joiners.equal((cs, gs) -> cs.getCoach().personId(), SavedPlanResourceUsage::personId),
                        Joiners.filtering((cs, gs, u) ->
                                u.type() == UsageType.PERSON && u.timeKey().overlaps(gs.getTrainingBlock().timeKey())))
                .penalize(HardMediumSoftLongScore.ofHard(1))
                .justifyWith((cs, gs, u, score) -> new SavedPlanClashJustification(
                        gs.getGroup().id(), cs.getCoach().personId(), u.sourcePlanName(), gs.getTrainingBlock().label(), "COACH"))
                .asConstraint(ConstraintKeys.SAVED_PLAN_COACH_BLOCKED);
    }

    Constraint savedPlanCourtBlocked(ConstraintFactory f) {
        return f.forEach(GroupSchedule.class)
                .join(
                        SavedPlanResourceUsage.class,
                        Joiners.equal(gs -> gs.getTrainingBlock().courtId(), SavedPlanResourceUsage::courtId),
                        Joiners.filtering((gs, u) ->
                                u.type() == UsageType.COURT && u.timeKey().overlaps(gs.getTrainingBlock().timeKey())))
                .penalize(HardMediumSoftLongScore.ofHard(1))
                .justifyWith((gs, u, score) -> new SavedPlanClashJustification(
                        gs.getGroup().id(), gs.getTrainingBlock().courtId(), u.sourcePlanName(), gs.getTrainingBlock().label(), "COURT"))
                .asConstraint(ConstraintKeys.SAVED_PLAN_COURT_BLOCKED);
    }

    // ─────────────────────────────────────────────────────────────────────── unassignedPlayer (§2.1)

    Constraint unassignedPlayer(ConstraintFactory f) {
        return f.forEachIncludingUnassigned(PlayerAssignment.class)
                .filter(pa -> pa.getGroup() == null)
                .penalize(HardMediumSoftLongScore.ofMedium(100), PlayerAssignment::getPriority)
                .justifyWith((pa, score) -> new UnassignedPlayerJustification(pa.getId(), pa.getDisplayName(), pa.getPriority()))
                .indictWith(pa -> List.of(pa))
                .asConstraint(ConstraintKeys.UNASSIGNED_PLAYER);
    }

    // ═══════════════════════════════════════════════════════════════════════ M6b: SOFT constraints

    /** Group-level (sum, count) tuples over non-waitlisted members — shared helper for {@link
     * #groupOrderByLevel} and {@link #coachLevelFit} (design §4's own {@code groupLevelStats}
     * sketch). Blind to empty groups by construction — see the class javadoc's "Empty-group
     * blindness" note. */
    private UniConstraintStream<GroupLevelStat> groupLevelStats(ConstraintFactory f) {
        return f.forEach(PlayerAssignment.class)
                .groupBy(
                        PlayerAssignment::getGroup,
                        ConstraintCollectors.sumLong(pa -> (long) pa.getLevelScaled()),
                        ConstraintCollectors.count())
                .map(GroupLevelStat::new);
    }

    /** Package-private helper record for {@link #groupLevelStats}: a group's summed scaled level and
     * (non-waitlisted) member count, from which mean/spread are derived without floating point. */
    record GroupLevelStat(Group group, long sumScaled, int count) {
    }

    // ─────────────────────────────────────────────────────────────────────── §10.4

    Constraint groupSizeTarget(ConstraintFactory f) {
        return f.forEach(PlayerAssignment.class)
                .groupBy(PlayerAssignment::getGroup, ConstraintCollectors.count())
                .penalize(HardMediumSoftLongScore.ofSoft(50), (group, count) -> Math.abs(count - group.targetSize()))
                .justifyWith((group, count, score) -> new GroupSizeDeviationJustification(group.id(), count, group.targetSize()))
                .asConstraint(ConstraintKeys.GROUP_SIZE_TARGET);
    }

    /** Empty-group complement of {@link #groupSizeTarget} (design §4 row 10.4 / verifier minor
     * finding): a group with zero non-waitlisted members never appears in the {@code groupBy} above,
     * so it would otherwise be invisible to this constraint despite deviating from targetSize by the
     * full amount. Fans from the SAME db weight row as its parent — see {@link
     * ConstraintKeys#COMPLEMENTS_OF}. */
    Constraint groupSizeTargetEmpty(ConstraintFactory f) {
        return f.forEach(Group.class)
                .ifNotExists(PlayerAssignment.class, Joiners.equal(group -> group, PlayerAssignment::getGroup))
                .penalize(HardMediumSoftLongScore.ofSoft(50), Group::targetSize)
                .justifyWith((group, score) -> new GroupSizeDeviationJustification(group.id(), 0, group.targetSize()))
                .asConstraint(ConstraintKeys.GROUP_SIZE_TARGET_EMPTY);
    }

    // ─────────────────────────────────────────────────────────────────────── §10.5

    Constraint groupMinSizeSoft(ConstraintFactory f) {
        return f.forEach(PlayerAssignment.class)
                .groupBy(PlayerAssignment::getGroup, ConstraintCollectors.count())
                .filter((group, count) -> count < group.minSize())
                .penalize(HardMediumSoftLongScore.ofSoft(50), (group, count) -> group.minSize() - count)
                .justifyWith((group, count, score) -> new GroupUnderMinJustification(group.id(), count, group.minSize()))
                .asConstraint(ConstraintKeys.GROUP_MIN_SIZE_SOFT);
    }

    /** Empty-group complement of {@link #groupMinSizeSoft} — see {@link #groupSizeTargetEmpty}'s
     * javadoc for the same reasoning; fans from the SAME db weight row as its parent. */
    Constraint groupMinSizeEmpty(ConstraintFactory f) {
        return f.forEach(Group.class)
                .ifNotExists(PlayerAssignment.class, Joiners.equal(group -> group, PlayerAssignment::getGroup))
                .filter(group -> group.minSize() > 0)
                .penalize(HardMediumSoftLongScore.ofSoft(50), Group::minSize)
                .justifyWith((group, score) -> new GroupUnderMinJustification(group.id(), 0, group.minSize()))
                .asConstraint(ConstraintKeys.GROUP_MIN_SIZE_EMPTY);
    }

    // ─────────────────────────────────────────────────────────────────────── §10.6

    Constraint levelBalance(ConstraintFactory f) {
        return f.forEach(PlayerAssignment.class)
                .groupBy(PlayerAssignment::getGroup, ConstraintCollectors.toList(pa -> pa))
                .penalize(HardMediumSoftLongScore.ofSoft(100), (group, members) -> sadPointsOf(members))
                .justifyWith((group, members, score) -> new LevelSpreadJustification(
                        group.id(), sadPointsOf(members), LevelMath.floorMean(sumScaledOf(members), members.size())))
                .asConstraint(ConstraintKeys.LEVEL_BALANCE);
    }

    private static int sadPointsOf(List<PlayerAssignment> members) {
        int[] levels = new int[members.size()];
        for (int i = 0; i < members.size(); i++) {
            levels[i] = members.get(i).getLevelScaled();
        }
        return LevelMath.sadPoints(levels);
    }

    private static long sumScaledOf(List<PlayerAssignment> members) {
        long sum = 0L;
        for (PlayerAssignment pa : members) {
            sum += pa.getLevelScaled();
        }
        return sum;
    }

    // ─────────────────────────────────────────────────────────────────────── §10.7

    /**
     * Adjacent-groups-only cross-multiplied mean comparison (design §4 row 10.7's own sketch, verified
     * expressible per docs/design/05-solver-verification.md's API checks): for every pair of groups
     * whose {@code groupOrder} differ by exactly 1 ({@code hi} = the better/lower-numbered group,
     * {@code lo} = the next one down), penalize when {@code hi}'s mean level is NOT above {@code
     * lo}'s — an inversion of the "higher groups have higher level" expectation (spec §10.7). No
     * division except the final {@code floorDiv} back to level points; the comparison itself is pure
     * cross-multiplication (a·d {@literal <} c·b instead of a/b {@literal <} c/d).
     */
    Constraint groupOrderByLevel(ConstraintFactory f) {
        return groupLevelStats(f)
                .join(
                        groupLevelStats(f),
                        Joiners.equal(s -> s.group().groupOrder() + 1, s -> s.group().groupOrder()))
                .filter((hi, lo) -> hi.sumScaled() * lo.count() < lo.sumScaled() * hi.count())
                .penalize(HardMediumSoftLongScore.ofSoft(50), (hi, lo) -> meanDiffPoints(hi, lo))
                .justifyWith((hi, lo, score) -> new GroupOrderInversionJustification(
                        hi.group().id(), lo.group().id(), meanDiffPoints(hi, lo)))
                .asConstraint(ConstraintKeys.GROUP_ORDER_BY_LEVEL);
    }

    private static int meanDiffPoints(GroupLevelStat hi, GroupLevelStat lo) {
        long numerator = lo.sumScaled() * hi.count() - hi.sumScaled() * lo.count();
        long denominator = (long) hi.count() * lo.count() * 100;
        return Math.toIntExact(Math.floorDiv(numerator, denominator)) + 1;
    }

    // ─────────────────────────────────────────────────────────────────────── §10.8

    Constraint previousGroupContinuity(ConstraintFactory f) {
        return f.forEach(PlayerAssignment.class)
                .filter(pa -> pa.getPreviousGroupOrder() != null)
                .penalize(
                        HardMediumSoftLongScore.ofSoft(30),
                        pa -> Math.abs(pa.getGroup().groupOrder() - pa.getPreviousGroupOrder()))
                .justifyWith((pa, score) -> new ContinuityJustification(
                        pa.getId(), pa.getPreviousGroupOrder(), pa.getGroup().groupOrder()))
                .asConstraint(ConstraintKeys.PREVIOUS_GROUP_CONTINUITY);
    }

    // ─────────────────────────────────────────────────────────────────────── §10.10
    // Single monotone penalty (design §4 row 10.10's explicit choice, not a mixed reward/penalty):
    // penalizing every allowed-but-unpreferred placement is equivalent in trade-off shape to
    // "rewarding preferred / penalizing unpreferred" for a player who expressed ANY preference at
    // all, while keeping the score direction uniformly monotone (soft quality never turns a
    // preference into a bonus that could outweigh other soft constraints in confusing ways).

    Constraint timePreferenceSoft(ConstraintFactory f) {
        return f.forEach(PlayerAssignment.class)
                .join(GroupSchedule.class, Joiners.equal(PlayerAssignment::getGroup, GroupSchedule::getGroup))
                .filter((pa, gs) -> pa.hasPreferences() && !pa.prefers(gs.getTrainingBlock().timeSlotId()))
                .penalize(HardMediumSoftLongScore.ofSoft(40))
                .justifyWith((pa, gs, score) -> new TimePreferenceMissedJustification(
                        pa.getId(), gs.getGroup().id(), gs.getTrainingBlock().timeSlotId()))
                .asConstraint(ConstraintKeys.TIME_PREFERENCE_SOFT);
    }

    // ─────────────────────────────────────────────────────────────────────── §10.12 / §10.14

    Constraint sameGroupSoft(ConstraintFactory f) {
        return f.forEach(PersonPairWish.class)
                .filter(w -> w.type() == WishType.WANT_SAME)
                .join(PlayerAssignment.class, Joiners.equal(PersonPairWish::aParticipantProfileId, PlayerAssignment::getId))
                .join(PlayerAssignment.class, Joiners.equal((w, a) -> w.bParticipantProfileId(), PlayerAssignment::getId))
                .filter((w, a, b) -> a.getGroup() != b.getGroup())
                .penalize(HardMediumSoftLongScore.ofSoft(80))
                .justifyWith((w, a, b, score) -> new PairWishSoftJustification(
                        w.fieldDefinitionId(), a.getId(), b.getId(), "WANT_SAME"))
                .asConstraint(ConstraintKeys.SAME_GROUP_SOFT);
    }

    Constraint differentGroupSoft(ConstraintFactory f) {
        return f.forEach(PersonPairWish.class)
                .filter(w -> w.type() == WishType.WANT_DIFFERENT)
                .join(PlayerAssignment.class, Joiners.equal(PersonPairWish::aParticipantProfileId, PlayerAssignment::getId))
                .join(PlayerAssignment.class, Joiners.equal((w, a) -> w.bParticipantProfileId(), PlayerAssignment::getId))
                .filter((w, a, b) -> a.getGroup() == b.getGroup())
                .penalize(HardMediumSoftLongScore.ofSoft(60))
                .justifyWith((w, a, b, score) -> new PairWishSoftJustification(
                        w.fieldDefinitionId(), a.getId(), b.getId(), "WANT_DIFFERENT"))
                .asConstraint(ConstraintKeys.DIFFERENT_GROUP_SOFT);
    }

    // ─────────────────────────────────────────────────────────────────────── §10.20

    Constraint coachLevelFit(ConstraintFactory f) {
        return f.forEach(CoachSlot.class)
                .join(groupLevelStats(f), Joiners.equal(CoachSlot::getGroup, GroupLevelStat::group))
                .filter((cs, stat) -> outsideCoachBand(cs.getCoach(), stat))
                .penalize(HardMediumSoftLongScore.ofSoft(50), (cs, stat) -> coachDistancePoints(cs.getCoach(), stat))
                .justifyWith((cs, stat, score) -> new CoachLevelMismatchJustification(
                        cs.getCoach().personId(),
                        stat.group().id(),
                        LevelMath.floorMean(stat.sumScaled(), stat.count()),
                        cs.getCoach().canCoachMinScaled(),
                        cs.getCoach().canCoachMaxScaled()))
                .asConstraint(ConstraintKeys.COACH_LEVEL_FIT);
    }

    private static boolean outsideCoachBand(CoachFact coach, GroupLevelStat stat) {
        long sum = stat.sumScaled();
        long count = stat.count();
        return sum < (long) coach.canCoachMinScaled() * count || sum > (long) coach.canCoachMaxScaled() * count;
    }

    private static int coachDistancePoints(CoachFact coach, GroupLevelStat stat) {
        long sum = stat.sumScaled();
        long count = stat.count();
        long boundScaled = sum < (long) coach.canCoachMinScaled() * count ? coach.canCoachMinScaled() : coach.canCoachMaxScaled();
        long diff = Math.abs(boundScaled * count - sum);
        return Math.toIntExact(Math.floorDiv(diff, count * 100));
    }

    // ─────────────────────────────────────────────────────────────────────── §10.21a

    Constraint coachPreferenceSoft(ConstraintFactory f) {
        return f.forEach(CoachWish.class)
                .filter(w -> w.type() == CoachWishType.WANT)
                .join(PlayerAssignment.class, Joiners.equal(CoachWish::participantProfileId, PlayerAssignment::getId))
                .ifExists(
                        CoachSlot.class,
                        Joiners.equal((w, pa) -> pa.getGroup(), CoachSlot::getGroup),
                        Joiners.filtering((w, pa, cs) -> cs.getCoach() != null && cs.getCoach().personId() == w.coachPersonId()))
                .reward(HardMediumSoftLongScore.ofSoft(50))
                .justifyWith((w, pa, score) -> new CoachWishJustification(pa.getId(), w.coachPersonId(), "WANT", true))
                .asConstraint(ConstraintKeys.COACH_PREFERENCE_SOFT);
    }

    // ─────────────────────────────────────────────────────────────────────── §10.22a/b
    //
    // Design §4 splits this into two rows (lateTimeTopGroups SOFT 20 penalize / lateTimeBottomGroups
    // SOFT 10 reward), but only ONE constraint_definition row (lateTimeForLowerGroups) was ever
    // seeded (V2). A first attempt implemented both directions as ONE constraint with a SIGNED
    // matchWeight (+1 penalize / -1 reward) sharing the row's single weight — arithmetically sound
    // (score contribution = -(weight x matchWeight) either way) but Timefold's runtime rejects it:
    // AbstractConstraint.assertCorrectImpact throws IllegalStateException("Negative match weight...
    // Check constraint provider implementation") for ANY negative matchWeight passed to .penalize(),
    // verified empirically. So instead: two separate constraints, both fed from the SAME
    // constraint_definition row's weight via ConstraintKeys.COMPLEMENTS_OF's fan-out mechanism
    // (SolverInputAssembler.buildConstraintWeightOverrides) — lateTimeForLowerGroups itself is NOT a
    // code key (not in ConstraintKeys.IMPLEMENTED), only its two children are. Both directions still
    // vanish together when the policy is disabled or the row's weight is 0/disabled, matching the
    // policy being a single fact-driven toggle in the design's own sketch (`p.enabled()` gates both).

    Constraint lateTimeTopGroups(ConstraintFactory f) {
        return f.forEach(GroupSchedule.class)
                .join(LateTimePolicy.class)
                .filter((gs, policy) -> policy.enabled() && isLateTopGroup(gs, policy))
                .penalize(HardMediumSoftLongScore.ofSoft(30))
                .justifyWith((gs, policy, score) -> new LateTimeJustification(
                        gs.getGroup().id(), gs.getGroup().groupOrder(), gs.getTrainingBlock().label(), "TOP_LATE_PENALIZED"))
                .asConstraint(ConstraintKeys.LATE_TIME_TOP_GROUPS);
    }

    Constraint lateTimeBottomGroups(ConstraintFactory f) {
        return f.forEach(GroupSchedule.class)
                .join(LateTimePolicy.class)
                .filter((gs, policy) -> policy.enabled() && isLateBottomGroup(gs, policy))
                .reward(HardMediumSoftLongScore.ofSoft(30))
                .justifyWith((gs, policy, score) -> new LateTimeJustification(
                        gs.getGroup().id(), gs.getGroup().groupOrder(), gs.getTrainingBlock().label(), "BOTTOM_LATE_REWARDED"))
                .asConstraint(ConstraintKeys.LATE_TIME_BOTTOM_GROUPS);
    }

    private static boolean isLate(GroupSchedule gs, LateTimePolicy policy) {
        return gs.getTrainingBlock().timeKey().startMinuteOfDay() >= policy.lateFromMinuteOfDay();
    }

    private static boolean isLateTopGroup(GroupSchedule gs, LateTimePolicy policy) {
        return isLate(gs, policy) && gs.getGroup().groupOrder() <= policy.topGroupOrderMax();
    }

    private static boolean isLateBottomGroup(GroupSchedule gs, LateTimePolicy policy) {
        return isLate(gs, policy) && gs.getGroup().groupOrder() >= policy.bottomGroupOrderMin();
    }

    // ─────────────────────────────────────────────────────────────────────── coachPreferredTimeSlot
    // New in M6b (V6 migration adds the constraint_definition row): the SOFT reward counterpart of
    // coachAvailabilityHard, consuming the M5 tri-state's PREFERRED kind (CoachFact.prefersTimeSlot).

    Constraint coachPreferredTimeSlot(ConstraintFactory f) {
        return f.forEach(CoachSlot.class)
                .join(GroupSchedule.class, Joiners.equal(CoachSlot::getGroup, GroupSchedule::getGroup))
                .filter((cs, gs) -> cs.getCoach().prefersTimeSlot(gs.getTrainingBlock().timeSlotId()))
                .reward(HardMediumSoftLongScore.ofSoft(20))
                .justifyWith((cs, gs, score) -> new CoachPreferredTimeSlotJustification(
                        cs.getCoach().personId(), gs.getGroup().id(), gs.getTrainingBlock().timeSlotId()))
                .asConstraint(ConstraintKeys.COACH_PREFERRED_TIME_SLOT);
    }
}
