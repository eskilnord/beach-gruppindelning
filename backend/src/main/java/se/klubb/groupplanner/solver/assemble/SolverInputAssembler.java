package se.klubb.groupplanner.solver.assemble;

import ai.timefold.solver.core.api.domain.solution.ConstraintWeightOverrides;
import ai.timefold.solver.core.api.score.buildin.hardmediumsoftlong.HardMediumSoftLongScore;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.springframework.stereotype.Component;
import se.klubb.groupplanner.api.error.BadRequestException;
import se.klubb.groupplanner.api.error.NotFoundException;
import se.klubb.groupplanner.common.time.TimeKey;
import se.klubb.groupplanner.domain.ActivityPlan;
import se.klubb.groupplanner.domain.CoachAssignment;
import se.klubb.groupplanner.domain.CoachProfile;
import se.klubb.groupplanner.domain.CoachTimeSlot;
import se.klubb.groupplanner.domain.ConstraintDefinition;
import se.klubb.groupplanner.domain.ConstraintWeightConfig;
import se.klubb.groupplanner.domain.Court;
import se.klubb.groupplanner.domain.CustomFieldValue;
import se.klubb.groupplanner.domain.FieldDefinition;
import se.klubb.groupplanner.domain.ParticipantProfile;
import se.klubb.groupplanner.domain.Person;
import se.klubb.groupplanner.domain.PlayerAssignment;
import se.klubb.groupplanner.domain.TimeSlot;
import se.klubb.groupplanner.domain.TrainingGroup;
import se.klubb.groupplanner.fields.ConstraintTypes;
import se.klubb.groupplanner.fields.HardOrSoft;
import se.klubb.groupplanner.repo.ActivityPlanRepository;
import se.klubb.groupplanner.repo.CoachAssignmentRepository;
import se.klubb.groupplanner.repo.CoachProfileRepository;
import se.klubb.groupplanner.repo.CoachTimeSlotRepository;
import se.klubb.groupplanner.repo.ConstraintDefinitionRepository;
import se.klubb.groupplanner.repo.ConstraintWeightConfigRepository;
import se.klubb.groupplanner.repo.CourtRepository;
import se.klubb.groupplanner.repo.CustomFieldValueRepository;
import se.klubb.groupplanner.repo.FieldDefinitionRepository;
import se.klubb.groupplanner.repo.ParticipantProfileRepository;
import se.klubb.groupplanner.repo.PersonRepository;
import se.klubb.groupplanner.repo.PlayerAssignmentRepository;
import se.klubb.groupplanner.repo.TimeSlotRepository;
import se.klubb.groupplanner.repo.TrainingBlockRepository;
import se.klubb.groupplanner.repo.TrainingGroupRepository;
import se.klubb.groupplanner.solver.constraints.ConstraintKeys;
import se.klubb.groupplanner.solver.domain.CoachFact;
import se.klubb.groupplanner.solver.domain.CoachSlot;
import se.klubb.groupplanner.solver.domain.CoachWish;
import se.klubb.groupplanner.solver.domain.CoachWishType;
import se.klubb.groupplanner.solver.domain.Group;
import se.klubb.groupplanner.solver.domain.GroupPlanSolution;
import se.klubb.groupplanner.solver.domain.GroupSchedule;
import se.klubb.groupplanner.solver.domain.LateTimePolicy;
import se.klubb.groupplanner.solver.domain.PersonPairWish;
import se.klubb.groupplanner.solver.domain.SavedPlanResourceUsage;
import se.klubb.groupplanner.solver.domain.WishType;

/**
 * DB -&gt; {@link GroupPlanSolution} assembly (docs/design/04-solver.md §1/§9.3). Deterministic:
 * every list is sorted by a stable DB id before being turned into solver-domain {@code long} ids
 * (see {@link SolverIdIndex} for why every id here is a deterministic derived {@code long}, not the
 * DB's own TEXT UUIDv7 primary key — a deviation from this design doc's "all ids are long DB ids"
 * assumption forced by ADR-004's schema choice).
 *
 * <p><b>M6a scope notes</b> (backend/docs/m6a-notes.md has the full rationale for each):
 * <ul>
 *   <li>{@code savedPlanResourceUsages} is always empty — {@code saved_plan}/{@code
 *       saved_plan_resource_usage} tables don't exist until M8.</li>
 *   <li>{@code lateTimePolicy} is always {@link LateTimePolicy#DISABLED} — no M6a constraint
 *       consumes it.</li>
 *   <li>{@code canTimes} (the whitelist standard field) is not consumed — only {@code cannotTimes}
 *       (blacklist -&gt; {@code unavailableTimeSlotIds}) and {@code preferTimes} feed the model,
 *       matching this design doc's own {@code PlayerAssignment.canAttend} sketch (blacklist-only).</li>
 *   <li>Any custom field with {@code constraintType == TIME_AVAILABILITY} other than the seeded
 *       {@code canTimes} is treated as a blacklist source (conservative default — an M4-era field
 *       builder gap: nothing distinguishes a council-created whitelist vs blacklist time field by
 *       constraintType alone).</li>
 *   <li>Constraint weight overrides are filtered to {@link ConstraintKeys#IMPLEMENTED} so an
 *       unimplemented/reserved key can never reach {@code ConstraintWeightOverrides} and fail solver
 *       construction with an unknown constraint name.</li>
 * </ul>
 */
@Component
public class SolverInputAssembler {

    private static final Pattern TRAILING_INT = Pattern.compile("(\\d+)\\s*$");
    private static final int DEFAULT_PRIORITY = 3;
    private static final int MIN_PRIORITY = 1;
    private static final int MAX_PRIORITY = 5;
    private static final int MAX_REQUIRED_COACH_COUNT = 99;

    private final ActivityPlanRepository activityPlanRepository;
    private final ParticipantProfileRepository participantProfileRepository;
    private final PersonRepository personRepository;
    private final TrainingGroupRepository trainingGroupRepository;
    private final TimeSlotRepository timeSlotRepository;
    private final TrainingBlockRepository trainingBlockRepository;
    private final CoachProfileRepository coachProfileRepository;
    private final CoachTimeSlotRepository coachTimeSlotRepository;
    private final CourtRepository courtRepository;
    private final FieldDefinitionRepository fieldDefinitionRepository;
    private final CustomFieldValueRepository customFieldValueRepository;
    private final ConstraintDefinitionRepository constraintDefinitionRepository;
    private final ConstraintWeightConfigRepository constraintWeightConfigRepository;
    private final PlayerAssignmentRepository playerAssignmentRepository;
    private final CoachAssignmentRepository coachAssignmentRepository;
    private final ObjectMapper objectMapper;

    public SolverInputAssembler(
            ActivityPlanRepository activityPlanRepository,
            ParticipantProfileRepository participantProfileRepository,
            PersonRepository personRepository,
            TrainingGroupRepository trainingGroupRepository,
            TimeSlotRepository timeSlotRepository,
            TrainingBlockRepository trainingBlockRepository,
            CoachProfileRepository coachProfileRepository,
            CoachTimeSlotRepository coachTimeSlotRepository,
            CourtRepository courtRepository,
            FieldDefinitionRepository fieldDefinitionRepository,
            CustomFieldValueRepository customFieldValueRepository,
            ConstraintDefinitionRepository constraintDefinitionRepository,
            ConstraintWeightConfigRepository constraintWeightConfigRepository,
            PlayerAssignmentRepository playerAssignmentRepository,
            CoachAssignmentRepository coachAssignmentRepository,
            ObjectMapper objectMapper) {
        this.activityPlanRepository = activityPlanRepository;
        this.participantProfileRepository = participantProfileRepository;
        this.personRepository = personRepository;
        this.trainingGroupRepository = trainingGroupRepository;
        this.timeSlotRepository = timeSlotRepository;
        this.trainingBlockRepository = trainingBlockRepository;
        this.coachProfileRepository = coachProfileRepository;
        this.coachTimeSlotRepository = coachTimeSlotRepository;
        this.courtRepository = courtRepository;
        this.fieldDefinitionRepository = fieldDefinitionRepository;
        this.customFieldValueRepository = customFieldValueRepository;
        this.constraintDefinitionRepository = constraintDefinitionRepository;
        this.constraintWeightConfigRepository = constraintWeightConfigRepository;
        this.playerAssignmentRepository = playerAssignmentRepository;
        this.coachAssignmentRepository = coachAssignmentRepository;
        this.objectMapper = objectMapper;
    }

    public AssembledProblem assemble(String activityPlanId) {
        ActivityPlan plan = activityPlanRepository.findById(activityPlanId)
                .orElseThrow(() -> new NotFoundException("Activity plan not found: " + activityPlanId));

        List<ParticipantProfile> participants = sortedById(
                participantProfileRepository.findByActivityPlanId(activityPlanId), ParticipantProfile::id);
        List<TrainingGroup> trainingGroups = sortedById(
                trainingGroupRepository.findByActivityPlanId(activityPlanId), TrainingGroup::id);
        List<TimeSlot> timeSlots = sortedById(timeSlotRepository.findByActivityPlanId(activityPlanId), TimeSlot::id);
        List<se.klubb.groupplanner.domain.TrainingBlock> activeBlocks = sortedById(
                trainingBlockRepository.findByActivityPlanId(activityPlanId).stream()
                        .filter(se.klubb.groupplanner.domain.TrainingBlock::active)
                        .toList(),
                se.klubb.groupplanner.domain.TrainingBlock::id);
        List<CoachProfile> coachProfiles = sortedById(
                coachProfileRepository.findByActivityPlanId(activityPlanId), CoachProfile::id);
        List<FieldDefinition> fields = sortedById(fieldDefinitionRepository.findVisibleToPlan(activityPlanId), FieldDefinition::id);
        List<PlayerAssignment> playerAssignments = playerAssignmentRepository.findByActivityPlanId(activityPlanId);
        List<CoachAssignment> coachAssignments = coachAssignmentRepository.findByActivityPlanId(activityPlanId);

        for (TrainingGroup g : trainingGroups) {
            if (g.groupOrder() == null) {
                throw new BadRequestException("Group '" + g.name() + "' has no groupOrder - run group generation first");
            }
            if (g.requiredCoachCount() > MAX_REQUIRED_COACH_COUNT) {
                throw new BadRequestException(
                        "Group '" + g.name() + "' requiredCoachCount " + g.requiredCoachCount() + " exceeds the solver's limit of "
                                + MAX_REQUIRED_COACH_COUNT);
            }
        }

        // --- id indexes (see class javadoc / SolverIdIndex) ---
        SolverIdIndex participantIdx = SolverIdIndex.of(participants.stream().map(ParticipantProfile::id).toList());
        SolverIdIndex timeSlotIdx = SolverIdIndex.of(timeSlots.stream().map(TimeSlot::id).toList());
        SolverIdIndex blockIdx = SolverIdIndex.of(activeBlocks.stream().map(se.klubb.groupplanner.domain.TrainingBlock::id).toList());
        SolverIdIndex coachIdx = SolverIdIndex.of(coachProfiles.stream().map(CoachProfile::id).toList());
        SolverIdIndex fieldIdx = SolverIdIndex.of(fields.stream().map(FieldDefinition::id).toList());
        List<String> courtIds = activeBlocks.stream().map(se.klubb.groupplanner.domain.TrainingBlock::courtId).distinct().sorted().toList();
        SolverIdIndex courtIdx = SolverIdIndex.of(courtIds);
        List<String> personIds = new ArrayList<>();
        participants.forEach(p -> personIds.add(p.personId()));
        coachProfiles.forEach(c -> personIds.add(c.personId()));
        SolverIdIndex personIdx = SolverIdIndex.of(personIds.stream().distinct().sorted().toList());

        Map<String, TimeSlot> timeSlotByDbId = new HashMap<>();
        timeSlots.forEach(s -> timeSlotByDbId.put(s.id(), s));

        // --- Group facts (id = groupOrder, see Group javadoc) ---
        Map<String, Group> groupByDbId = new HashMap<>();
        List<Group> groups = new ArrayList<>();
        for (TrainingGroup tg : trainingGroups) {
            Group g = new Group(
                    tg.groupOrder(),
                    tg.name(),
                    tg.groupOrder(),
                    tg.minSize() != null ? tg.minSize() : 0,
                    tg.targetSize() != null ? tg.targetSize() : 0,
                    tg.maxSize() != null ? tg.maxSize() : Integer.MAX_VALUE,
                    tg.requiredCoachCount(),
                    tg.levelMin() != null ? scaled(tg.levelMin()) : 0,
                    tg.levelMax() != null ? scaled(tg.levelMax()) : 0);
            groups.add(g);
            groupByDbId.put(tg.id(), g);
        }

        // --- TrainingBlock facts ---
        List<se.klubb.groupplanner.solver.domain.TrainingBlock> trainingBlocks = new ArrayList<>();
        Map<String, se.klubb.groupplanner.solver.domain.TrainingBlock> blockByDbId = new HashMap<>();
        Map<String, String> courtNameById = new HashMap<>();
        for (String courtId : courtIds) {
            courtRepository.findById(courtId).map(Court::name).ifPresent(name -> courtNameById.put(courtId, name));
        }
        for (se.klubb.groupplanner.domain.TrainingBlock b : activeBlocks) {
            TimeSlot slot = timeSlotByDbId.get(b.timeSlotId());
            if (slot == null) {
                continue; // defensive: orphaned block, should not happen under FK constraints.
            }
            TimeKey timeKey = timeKeyOf(slot);
            String courtName = courtNameById.getOrDefault(b.courtId(), b.courtId());
            String label = (slot.label() != null ? slot.label() : timeKey.toString()) + ", " + courtName;
            se.klubb.groupplanner.solver.domain.TrainingBlock fact = new se.klubb.groupplanner.solver.domain.TrainingBlock(
                    blockIdx.id(b.id()), courtIdx.id(b.courtId()), courtName, timeKey, label, timeSlotIdx.id(slot.id()));
            trainingBlocks.add(fact);
            blockByDbId.put(b.id(), fact);
        }

        // --- CoachFact list + coachProfileId -> CoachFact / personId lookups ---
        List<CoachFact> coachFacts = new ArrayList<>();
        Map<String, CoachFact> coachFactByDbId = new HashMap<>();
        Map<String, Long> coachPersonLongIdByProfileId = new HashMap<>();
        Map<String, Person> personById = new HashMap<>();
        for (CoachProfile cp : coachProfiles) {
            Person person = personRepository.findById(cp.personId()).orElse(null);
            personById.put(cp.personId(), person);
            String displayName = displayNameOf(person);
            long[] unavailable = coachTimeSlotRepository.findByCoachProfileId(cp.id()).stream()
                    .filter(ct -> CoachTimeSlot.UNAVAILABLE.equals(ct.kind()))
                    .map(CoachTimeSlot::timeSlotId)
                    .filter(timeSlotIdx::contains)
                    .mapToLong(timeSlotIdx::id)
                    .distinct()
                    .sorted()
                    .toArray();
            int maxGroups = coalesceMaxGroups(cp);
            CoachFact fact = new CoachFact(
                    coachIdx.id(cp.id()),
                    personIdx.id(cp.personId()),
                    displayName,
                    cp.coachLevel() != null ? scaled(cp.coachLevel()) : 0,
                    cp.canCoachMinLevel() != null ? scaled(cp.canCoachMinLevel()) : 0,
                    cp.canCoachMaxLevel() != null ? scaled(cp.canCoachMaxLevel()) : 100_000,
                    unavailable,
                    maxGroups);
            coachFacts.add(fact);
            coachFactByDbId.put(cp.id(), fact);
            coachPersonLongIdByProfileId.put(cp.id(), fact.personId());
        }

        // --- wish facts + priority + per-player time constraints, all from custom_field_value ---
        Map<String, FieldDefinition> fieldsById = new HashMap<>();
        fields.forEach(f -> fieldsById.put(f.id(), f));

        List<PersonPairWish> personPairWishes = new ArrayList<>();
        List<CoachWish> coachWishes = new ArrayList<>();
        Map<String, Integer> priorityByParticipantId = new HashMap<>();
        Map<String, long[]> unavailableByParticipantId = new HashMap<>();
        Map<String, long[]> preferredByParticipantId = new HashMap<>();

        for (ParticipantProfile p : participants) {
            List<Long> unavailable = new ArrayList<>();
            List<Long> preferred = new ArrayList<>();
            for (CustomFieldValue cfv : customFieldValueRepository.findByEntity(CustomFieldValue.ENTITY_TYPE_PARTICIPANT, p.id())) {
                FieldDefinition field = fieldsById.get(cfv.fieldDefinitionId());
                if (field == null || cfv.valueJson() == null) {
                    continue;
                }
                switch (field.constraintType()) {
                    case ConstraintTypes.PRIORITY -> priorityByParticipantId.put(p.id(), parsePriority(cfv.valueJson()));
                    case ConstraintTypes.SAME_GROUP, ConstraintTypes.DIFFERENT_GROUP -> {
                        WishType type = wishType(field.constraintType(), field.hardOrSoft());
                        for (String targetId : parseIdArray(cfv.valueJson())) {
                            if (targetId.equals(p.id()) || !participantIdx.contains(targetId)) {
                                continue;
                            }
                            personPairWishes.add(new PersonPairWish(
                                    fieldIdx.id(field.id()), type, participantIdx.id(p.id()), participantIdx.id(targetId)));
                        }
                    }
                    case ConstraintTypes.COACH_PREFERENCE, ConstraintTypes.COACH_FORBIDDEN -> {
                        CoachWishType type = coachWishType(field.constraintType(), field.hardOrSoft());
                        if (type == null) {
                            break;
                        }
                        for (String coachProfileId : parseIdArray(cfv.valueJson())) {
                            Long coachPersonLongId = coachPersonLongIdByProfileId.get(coachProfileId);
                            if (coachPersonLongId == null) {
                                continue;
                            }
                            coachWishes.add(new CoachWish(fieldIdx.id(field.id()), type, participantIdx.id(p.id()), coachPersonLongId));
                        }
                    }
                    case ConstraintTypes.TIME_AVAILABILITY -> {
                        if (!"canTimes".equals(field.key())) { // whitelist field: not consumed (see class javadoc)
                            for (String slotId : parseIdArray(cfv.valueJson())) {
                                if (timeSlotIdx.contains(slotId)) {
                                    unavailable.add(timeSlotIdx.id(slotId));
                                }
                            }
                        }
                    }
                    case ConstraintTypes.TIME_PREFERENCE -> {
                        for (String slotId : parseIdArray(cfv.valueJson())) {
                            if (timeSlotIdx.contains(slotId)) {
                                preferred.add(timeSlotIdx.id(slotId));
                            }
                        }
                    }
                    default -> {
                        // NONE or a type this milestone doesn't consume - ignored.
                    }
                }
            }
            unavailableByParticipantId.put(p.id(), sortedDistinct(unavailable));
            preferredByParticipantId.put(p.id(), sortedDistinct(preferred));
        }

        // Determinism (§9.3): wish facts sorted by (fieldDefinitionId, aId, bId) - built above in
        // participant-then-custom_field_value-row order, which per-participant is already id-sorted
        // (participants list, and CustomFieldValueRepository.findByEntity's own ORDER BY id) but a
        // final explicit sort makes the ordering invariant self-evident and immune to any future
        // change in either upstream order.
        personPairWishes.sort(java.util.Comparator
                .comparingLong(PersonPairWish::fieldDefinitionId)
                .thenComparingLong(PersonPairWish::aParticipantProfileId)
                .thenComparingLong(PersonPairWish::bParticipantProfileId));
        coachWishes.sort(java.util.Comparator
                .comparingLong(CoachWish::fieldDefinitionId)
                .thenComparingLong(CoachWish::participantProfileId)
                .thenComparingLong(CoachWish::coachPersonId));

        // --- PlayerAssignment entities ---
        Map<String, PlayerAssignment> playerAssignmentByParticipantId = new HashMap<>();
        playerAssignments.forEach(pa -> playerAssignmentByParticipantId.put(pa.participantProfileId(), pa));

        List<se.klubb.groupplanner.solver.domain.PlayerAssignment> solverPlayerAssignments = new ArrayList<>();
        for (ParticipantProfile p : participants) {
            PlayerAssignment dbAssignment = playerAssignmentByParticipantId.get(p.id());
            boolean pinned = dbAssignment != null && dbAssignment.locked();
            Group initialGroup = null;
            if (dbAssignment != null && dbAssignment.groupId() != null) {
                initialGroup = groupByDbId.get(dbAssignment.groupId());
                if (pinned && initialGroup == null) {
                    throw new BadRequestException(
                            "Participant " + p.id() + " is locked to a group that no longer exists - fix before solving");
                }
            }
            double estimatedLevel = p.estimatedLevel() != null ? p.estimatedLevel() : 500.0;
            Person person = personRepository.findById(p.personId()).orElse(null);
            se.klubb.groupplanner.solver.domain.PlayerAssignment entity = new se.klubb.groupplanner.solver.domain.PlayerAssignment(
                    participantIdx.id(p.id()),
                    personIdx.id(p.personId()),
                    displayNameOf(person),
                    scaled(estimatedLevel),
                    priorityByParticipantId.getOrDefault(p.id(), DEFAULT_PRIORITY),
                    previousGroupOrderOf(p),
                    unavailableByParticipantId.get(p.id()),
                    preferredByParticipantId.get(p.id()),
                    initialGroup,
                    pinned);
            solverPlayerAssignments.add(entity);
        }

        // --- GroupSchedule entities ---
        List<GroupSchedule> groupSchedules = new ArrayList<>();
        for (TrainingGroup tg : trainingGroups) {
            Group fact = groupByDbId.get(tg.id());
            boolean pinned = tg.locked();
            se.klubb.groupplanner.solver.domain.TrainingBlock initialBlock = null;
            if (tg.assignedTrainingBlockId() != null) {
                initialBlock = blockByDbId.get(tg.assignedTrainingBlockId());
            }
            if (pinned && initialBlock == null) {
                // Design §5's pre-solve pin validation (review fix 6): GroupSchedule.trainingBlock is
                // NOT unassignable (§1.4), so a pinned schedule at null (never scheduled, or its block
                // was deactivated/deleted) is a structurally invalid pin - @PlanningPin would freeze
                // the null and the Construction Heuristic could never initialize the variable.
                throw new BadRequestException(tg.assignedTrainingBlockId() == null
                        ? "Group '" + tg.name() + "' is locked but has no assigned training block - assign one or unlock before solving"
                        : "Group '" + tg.name() + "' is locked to a training block that is no longer active - fix before solving");
            }
            groupSchedules.add(new GroupSchedule((long) fact.id(), fact, initialBlock, pinned));
        }

        // --- CoachSlot entities ---
        Map<String, List<CoachAssignment>> coachAssignmentsByGroupId = new HashMap<>();
        for (CoachAssignment ca : coachAssignments) {
            coachAssignmentsByGroupId.computeIfAbsent(ca.groupId(), k -> new ArrayList<>()).add(ca);
        }
        List<CoachSlot> coachSlots = new ArrayList<>();
        for (TrainingGroup tg : trainingGroups) {
            Group fact = groupByDbId.get(tg.id());
            List<CoachAssignment> existingForGroup = coachAssignmentsByGroupId.getOrDefault(tg.id(), List.of()).stream()
                    .sorted((a, b) -> a.id().compareTo(b.id()))
                    .toList();
            for (int slotIndex = 0; slotIndex < tg.requiredCoachCount(); slotIndex++) {
                CoachFact initialCoach = null;
                boolean pinned = false;
                if (slotIndex < existingForGroup.size()) {
                    CoachAssignment ca = existingForGroup.get(slotIndex);
                    initialCoach = coachFactByDbId.get(ca.coachProfileId());
                    pinned = ca.locked();
                    if (pinned && initialCoach == null) {
                        throw new BadRequestException(
                                "Group '" + tg.name() + "' slot " + slotIndex + " is locked to a coach outside this plan - fix before solving");
                    }
                }
                long id = CoachSlot.syntheticId(fact.id(), slotIndex);
                coachSlots.add(new CoachSlot(id, fact, slotIndex, initialCoach, pinned));
            }
        }

        ConstraintWeightOverrides<HardMediumSoftLongScore> overrides = buildConstraintWeightOverrides(activityPlanId);

        GroupPlanSolution solution = new GroupPlanSolution(
                activityPlanId,
                solverPlayerAssignments,
                groupSchedules,
                coachSlots,
                groups,
                trainingBlocks,
                coachFacts,
                personPairWishes,
                coachWishes,
                List.<SavedPlanResourceUsage>of(),
                LateTimePolicy.DISABLED,
                overrides);

        // Reverse (long solver id -> DB TEXT id) lookups for SolveCoordinator's post-solve writeback
        // (see AssembledProblem javadoc: the async final-solution consumer runs on a different
        // thread/request than this call, so these maps must travel with the solution).
        Map<Long, String> participantDbIdByLongId = new HashMap<>();
        participants.forEach(p -> participantDbIdByLongId.put(participantIdx.id(p.id()), p.id()));
        Map<Long, String> coachDbIdByLongId = new HashMap<>();
        coachProfiles.forEach(c -> coachDbIdByLongId.put(coachIdx.id(c.id()), c.id()));
        Map<Long, String> groupDbIdByLongId = new HashMap<>();
        trainingGroups.forEach(tg -> groupDbIdByLongId.put((long) tg.groupOrder(), tg.id()));
        Map<Long, String> blockDbIdByLongId = new HashMap<>();
        activeBlocks.forEach(b -> blockDbIdByLongId.put(blockIdx.id(b.id()), b.id()));

        return new AssembledProblem(solution, participantDbIdByLongId, coachDbIdByLongId, groupDbIdByLongId, blockDbIdByLongId);
    }

    private ConstraintWeightOverrides<HardMediumSoftLongScore> buildConstraintWeightOverrides(String activityPlanId) {
        Map<String, HardMediumSoftLongScore> weights = new HashMap<>();
        for (ConstraintDefinition def : constraintDefinitionRepository.findAll()) {
            if (!ConstraintKeys.IMPLEMENTED.contains(def.key())) {
                continue;
            }
            weights.put(def.key(), scoreFor(def.hardOrSoft(), def.enabled() ? def.defaultWeight() : 0));
        }
        for (ConstraintWeightConfig cfg : constraintWeightConfigRepository.findByActivityPlanId(activityPlanId)) {
            if (!ConstraintKeys.IMPLEMENTED.contains(cfg.constraintKey())) {
                continue;
            }
            if (ConstraintKeys.UNASSIGNED_PLAYER.equals(cfg.constraintKey())) {
                // Defensive re-verification of the M4 guardrail (ConstraintWeightService already
                // rejects writes that would violate this at the API layer) - the solver path must
                // never trust that a stored row is still valid without checking again.
                if (!cfg.enabled() || !HardOrSoft.MEDIUM.equals(cfg.hardOrSoft()) || cfg.weight() < 1) {
                    throw new IllegalStateException(
                            "unassignedPlayer constraint_weight_config row violates the reserved-MEDIUM guardrail "
                                    + "(ADR-006) for plan " + activityPlanId + " - refusing to solve");
                }
            }
            weights.put(cfg.constraintKey(), scoreFor(cfg.hardOrSoft(), cfg.enabled() ? cfg.weight() : 0));
        }
        return ConstraintWeightOverrides.of(weights);
    }

    private static HardMediumSoftLongScore scoreFor(String hardOrSoft, int weight) {
        return switch (hardOrSoft) {
            case HardOrSoft.HARD -> HardMediumSoftLongScore.ofHard(weight);
            case HardOrSoft.MEDIUM -> HardMediumSoftLongScore.ofMedium(weight);
            case HardOrSoft.SOFT -> HardMediumSoftLongScore.ofSoft(weight);
            default -> throw new IllegalStateException("Unknown hardOrSoft: " + hardOrSoft);
        };
    }

    private static WishType wishType(String constraintType, String hardOrSoft) {
        boolean hard = HardOrSoft.HARD.equals(hardOrSoft);
        if (ConstraintTypes.SAME_GROUP.equals(constraintType)) {
            return hard ? WishType.MUST_SAME : WishType.WANT_SAME;
        }
        return hard ? WishType.MUST_DIFFERENT : WishType.WANT_DIFFERENT;
    }

    /** Returns null for a combination with no M6a-relevant meaning (defensive; should not occur for
     * the seeded/validated field shapes). */
    private static CoachWishType coachWishType(String constraintType, String hardOrSoft) {
        boolean hard = HardOrSoft.HARD.equals(hardOrSoft);
        if (ConstraintTypes.COACH_FORBIDDEN.equals(constraintType)) {
            return CoachWishType.CANNOT;
        }
        return hard ? CoachWishType.MUST : CoachWishType.WANT;
    }

    private int coalesceMaxGroups(CoachProfile cp) {
        if (cp.maxGroupsPerWeek() != null) {
            return cp.maxGroupsPerWeek();
        }
        if (cp.maxGroupsPerDay() != null) {
            return cp.maxGroupsPerDay();
        }
        return Integer.MAX_VALUE;
    }

    private Integer previousGroupOrderOf(ParticipantProfile p) {
        if (p.previousGroupName() == null) {
            return null;
        }
        Matcher m = TRAILING_INT.matcher(p.previousGroupName().strip());
        if (!m.find()) {
            return null;
        }
        return Integer.parseInt(m.group(1));
    }

    private int parsePriority(String valueJson) {
        JsonNode node = readTree(valueJson);
        int value = node.isNumber() ? node.asInt() : DEFAULT_PRIORITY;
        return Math.max(MIN_PRIORITY, Math.min(MAX_PRIORITY, value));
    }

    private List<String> parseIdArray(String valueJson) {
        JsonNode node = readTree(valueJson);
        if (!node.isArray()) {
            return List.of();
        }
        List<String> ids = new ArrayList<>();
        node.forEach(n -> ids.add(n.asText()));
        return ids;
    }

    private JsonNode readTree(String json) {
        try {
            return objectMapper.readTree(json);
        } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
            throw new IllegalStateException("Corrupt custom_field_value JSON: " + json, e);
        }
    }

    private static long[] sortedDistinct(List<Long> values) {
        return values.stream().mapToLong(Long::longValue).distinct().sorted().toArray();
    }

    private static TimeKey timeKeyOf(TimeSlot slot) {
        int epochDay = TimeKey.NO_DATE;
        int dayOfWeek;
        if (slot.date() != null) {
            LocalDate date = LocalDate.parse(slot.date());
            epochDay = (int) date.toEpochDay();
            dayOfWeek = date.getDayOfWeek().getValue();
        } else {
            dayOfWeek = DayOfWeek.valueOf(slot.dayOfWeek()).getValue();
        }
        return new TimeKey(epochDay, dayOfWeek, minuteOfDay(slot.startTime()), minuteOfDay(slot.endTime()));
    }

    private static int minuteOfDay(String hhmm) {
        LocalTime t = LocalTime.parse(hhmm);
        return t.getHour() * 60 + t.getMinute();
    }

    private static String displayNameOf(Person person) {
        if (person == null) {
            return "";
        }
        if (person.displayName() != null && !person.displayName().isBlank()) {
            return person.displayName();
        }
        return (person.firstName() + " " + person.lastName()).strip();
    }

    private static int scaled(double level) {
        return (int) Math.round(level * 100.0);
    }

    private static <T> List<T> sortedById(List<T> items, java.util.function.Function<T, String> idOf) {
        return items.stream().sorted(java.util.Comparator.comparing(idOf)).toList();
    }
}
