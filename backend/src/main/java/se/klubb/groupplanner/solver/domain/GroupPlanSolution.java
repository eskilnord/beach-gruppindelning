package se.klubb.groupplanner.solver.domain;

import ai.timefold.solver.core.api.domain.solution.ConstraintWeightOverrides;
import ai.timefold.solver.core.api.domain.solution.PlanningEntityCollectionProperty;
import ai.timefold.solver.core.api.domain.solution.PlanningScore;
import ai.timefold.solver.core.api.domain.solution.PlanningSolution;
import ai.timefold.solver.core.api.domain.solution.ProblemFactCollectionProperty;
import ai.timefold.solver.core.api.domain.solution.ProblemFactProperty;
import ai.timefold.solver.core.api.domain.valuerange.ValueRangeProvider;
import ai.timefold.solver.core.api.score.buildin.hardmediumsoftlong.HardMediumSoftLongScore;
import java.util.List;

/**
 * Planning solution root (docs/design/04-solver.md §1.3). Assembled fresh for every solve/what-if by
 * {@code SolverInputAssembler} — never reused as mutable shared state across requests.
 *
 * <p>No shadow variables (§1.3): group size, level sums etc. are computed inside constraints via
 * {@code groupBy} and, post-solve, in plain Java for display.
 */
@PlanningSolution
public class GroupPlanSolution {

    /**
     * The owning {@code activity_plan}'s DB id — kept as {@code String} (its actual TEXT UUIDv7
     * type) rather than this design doc's assumed {@code long}, since it is purely informational
     * (never a {@code @PlanningId}/value-range member the solver reasons about) and forcing it
     * through the same long-id mapping as every other fact would buy nothing. See {@code
     * SolverInputAssembler}'s id-mapping note for the full rationale.
     */
    private String activityPlanId;

    @PlanningEntityCollectionProperty
    private List<PlayerAssignment> playerAssignments;

    @PlanningEntityCollectionProperty
    private List<GroupSchedule> groupSchedules;

    @PlanningEntityCollectionProperty
    private List<CoachSlot> coachSlots;

    @ProblemFactCollectionProperty
    @ValueRangeProvider(id = "groupRange")
    private List<Group> groups;

    @ProblemFactCollectionProperty
    @ValueRangeProvider(id = "trainingBlockRange")
    private List<TrainingBlock> trainingBlocks;

    @ProblemFactCollectionProperty
    @ValueRangeProvider(id = "coachRange")
    private List<CoachFact> coaches;

    @ProblemFactCollectionProperty
    private List<PersonPairWish> personPairWishes;

    @ProblemFactCollectionProperty
    private List<CoachWish> coachWishes;

    @ProblemFactCollectionProperty
    private List<SavedPlanResourceUsage> savedPlanResourceUsages;

    @ProblemFactProperty
    private LateTimePolicy lateTimePolicy;

    private ConstraintWeightOverrides<HardMediumSoftLongScore> constraintWeightOverrides =
            ConstraintWeightOverrides.none();

    @PlanningScore
    private HardMediumSoftLongScore score;

    /** No-arg constructor required by Timefold's reflective domain access. */
    public GroupPlanSolution() {
    }

    public GroupPlanSolution(
            String activityPlanId,
            List<PlayerAssignment> playerAssignments,
            List<GroupSchedule> groupSchedules,
            List<CoachSlot> coachSlots,
            List<Group> groups,
            List<TrainingBlock> trainingBlocks,
            List<CoachFact> coaches,
            List<PersonPairWish> personPairWishes,
            List<CoachWish> coachWishes,
            List<SavedPlanResourceUsage> savedPlanResourceUsages,
            LateTimePolicy lateTimePolicy,
            ConstraintWeightOverrides<HardMediumSoftLongScore> constraintWeightOverrides) {
        this.activityPlanId = activityPlanId;
        this.playerAssignments = playerAssignments;
        this.groupSchedules = groupSchedules;
        this.coachSlots = coachSlots;
        this.groups = groups;
        this.trainingBlocks = trainingBlocks;
        this.coaches = coaches;
        this.personPairWishes = personPairWishes;
        this.coachWishes = coachWishes;
        this.savedPlanResourceUsages = savedPlanResourceUsages;
        this.lateTimePolicy = lateTimePolicy;
        this.constraintWeightOverrides =
                constraintWeightOverrides == null ? ConstraintWeightOverrides.none() : constraintWeightOverrides;
    }

    public String getActivityPlanId() {
        return activityPlanId;
    }

    public void setActivityPlanId(String activityPlanId) {
        this.activityPlanId = activityPlanId;
    }

    public List<PlayerAssignment> getPlayerAssignments() {
        return playerAssignments;
    }

    public void setPlayerAssignments(List<PlayerAssignment> playerAssignments) {
        this.playerAssignments = playerAssignments;
    }

    public List<GroupSchedule> getGroupSchedules() {
        return groupSchedules;
    }

    public void setGroupSchedules(List<GroupSchedule> groupSchedules) {
        this.groupSchedules = groupSchedules;
    }

    public List<CoachSlot> getCoachSlots() {
        return coachSlots;
    }

    public void setCoachSlots(List<CoachSlot> coachSlots) {
        this.coachSlots = coachSlots;
    }

    public List<Group> getGroups() {
        return groups;
    }

    public void setGroups(List<Group> groups) {
        this.groups = groups;
    }

    public List<TrainingBlock> getTrainingBlocks() {
        return trainingBlocks;
    }

    public void setTrainingBlocks(List<TrainingBlock> trainingBlocks) {
        this.trainingBlocks = trainingBlocks;
    }

    public List<CoachFact> getCoaches() {
        return coaches;
    }

    public void setCoaches(List<CoachFact> coaches) {
        this.coaches = coaches;
    }

    public List<PersonPairWish> getPersonPairWishes() {
        return personPairWishes;
    }

    public void setPersonPairWishes(List<PersonPairWish> personPairWishes) {
        this.personPairWishes = personPairWishes;
    }

    public List<CoachWish> getCoachWishes() {
        return coachWishes;
    }

    public void setCoachWishes(List<CoachWish> coachWishes) {
        this.coachWishes = coachWishes;
    }

    public List<SavedPlanResourceUsage> getSavedPlanResourceUsages() {
        return savedPlanResourceUsages;
    }

    public void setSavedPlanResourceUsages(List<SavedPlanResourceUsage> savedPlanResourceUsages) {
        this.savedPlanResourceUsages = savedPlanResourceUsages;
    }

    public LateTimePolicy getLateTimePolicy() {
        return lateTimePolicy;
    }

    public void setLateTimePolicy(LateTimePolicy lateTimePolicy) {
        this.lateTimePolicy = lateTimePolicy;
    }

    public ConstraintWeightOverrides<HardMediumSoftLongScore> getConstraintWeightOverrides() {
        return constraintWeightOverrides;
    }

    public void setConstraintWeightOverrides(ConstraintWeightOverrides<HardMediumSoftLongScore> constraintWeightOverrides) {
        this.constraintWeightOverrides = constraintWeightOverrides;
    }

    public HardMediumSoftLongScore getScore() {
        return score;
    }

    public void setScore(HardMediumSoftLongScore score) {
        this.score = score;
    }
}
