package se.klubb.groupplanner.solver.constraints;

import ai.timefold.solver.core.api.score.buildin.hardmediumsoftlong.HardMediumSoftLongScore;
import ai.timefold.solver.core.api.score.stream.Constraint;
import ai.timefold.solver.core.api.score.stream.ConstraintCollectors;
import ai.timefold.solver.core.api.score.stream.ConstraintFactory;
import ai.timefold.solver.core.api.score.stream.ConstraintProvider;
import ai.timefold.solver.core.api.score.stream.Joiners;
import java.util.List;
import se.klubb.groupplanner.solver.constraints.Justifications.BlockDoubleBookedJustification;
import se.klubb.groupplanner.solver.constraints.Justifications.CoachDoubleBookedJustification;
import se.klubb.groupplanner.solver.constraints.Justifications.CoachOverloadedJustification;
import se.klubb.groupplanner.solver.constraints.Justifications.CoachUnavailableJustification;
import se.klubb.groupplanner.solver.constraints.Justifications.CoachWishJustification;
import se.klubb.groupplanner.solver.constraints.Justifications.GroupOverMaxJustification;
import se.klubb.groupplanner.solver.constraints.Justifications.MissingCoachJustification;
import se.klubb.groupplanner.solver.constraints.Justifications.PairWishBrokenJustification;
import se.klubb.groupplanner.solver.constraints.Justifications.PlayerDoubleBookedJustification;
import se.klubb.groupplanner.solver.constraints.Justifications.SavedPlanClashJustification;
import se.klubb.groupplanner.solver.constraints.Justifications.TimeUnavailableJustification;
import se.klubb.groupplanner.solver.constraints.Justifications.TrainAndCoachClashJustification;
import se.klubb.groupplanner.solver.constraints.Justifications.UnassignedPlayerJustification;
import se.klubb.groupplanner.solver.domain.CoachSlot;
import se.klubb.groupplanner.solver.domain.CoachWish;
import se.klubb.groupplanner.solver.domain.CoachWishType;
import se.klubb.groupplanner.solver.domain.GroupSchedule;
import se.klubb.groupplanner.solver.domain.PersonPairWish;
import se.klubb.groupplanner.solver.domain.PlayerAssignment;
import se.klubb.groupplanner.solver.domain.SavedPlanResourceUsage;
import se.klubb.groupplanner.solver.domain.UsageType;
import se.klubb.groupplanner.solver.domain.WishType;

/**
 * Constraint Streams provider (docs/design/04-solver.md §4). M6a scope: every HARD row of the
 * design's per-constraint table, plus {@code unassignedPlayer} (MEDIUM, the reserved waitlist
 * penalty, §2). SOFT constraints (level balance, group ordering, continuity, preferences, coach
 * fit/wishes-WANT, late-time policy) are M6b scope and are not implemented here.
 *
 * <p>Two HARD rows have no Constraint Streams code because they are satisfied by construction
 * (§1.4/§5, see {@link ConstraintKeys#GROUP_REQUIRES_TRAINING_BLOCK}/{@link
 * ConstraintKeys#LOCKED_ASSIGNMENT_HARD}): {@code trainingBlock} is a non-unassignable planning
 * variable and {@code GroupGenerator} guarantees a legal value always exists, and {@code
 * @PlanningPin} on all three entity classes makes a solver move onto a pinned entity structurally
 * impossible. Both are covered by model-level tests instead of {@code ConstraintVerifier}.
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
}
