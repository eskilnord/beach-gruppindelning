package se.klubb.groupplanner.solver.assemble;

import ai.timefold.solver.core.api.domain.solution.ConstraintWeightOverrides;
import ai.timefold.solver.core.api.score.buildin.hardmediumsoftlong.HardMediumSoftLongScore;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
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
import se.klubb.groupplanner.domain.SavedPlan;
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
import se.klubb.groupplanner.repo.SavedPlanRepository;
import se.klubb.groupplanner.repo.SavedPlanResourceUsageRepository;
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
import se.klubb.groupplanner.solver.domain.UsageType;
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
 *   <li>{@code canTimes} (the whitelist standard field, WI-A) IS consumed: a NON-EMPTY array means
 *       "only these time slots are OK for this player" - its complement (every OTHER plan time slot)
 *       is folded into {@code unavailableTimeSlotIds}, unioned with whatever {@code cannotTimes}
 *       (blacklist) already contributed. An empty or absent {@code canTimes} array means "no
 *       restriction at all" and must never add anything - the model is still fundamentally
 *       blacklist-only ({@code PlayerAssignment.canAttend}'s single {@code unavailableTimeSlotIds}
 *       array), {@code canTimes} is just a second, whitelist-shaped SOURCE that gets translated into
 *       that same blacklist before the solver ever sees it.</li>
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
    private final SavedPlanRepository savedPlanRepository;
    private final SavedPlanResourceUsageRepository savedPlanResourceUsageRepository;
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
            SavedPlanRepository savedPlanRepository,
            SavedPlanResourceUsageRepository savedPlanResourceUsageRepository,
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
        this.savedPlanRepository = savedPlanRepository;
        this.savedPlanResourceUsageRepository = savedPlanResourceUsageRepository;
        this.objectMapper = objectMapper;
    }

    /** Convenience overload: full optimize (nothing class-pinned beyond individual locks) and no
     * cross-plan blocking — M6a's exact behavior, still used by tests and any caller that doesn't
     * need §15.5/§14.4. */
    public AssembledProblem assemble(String activityPlanId) {
        return assemble(activityPlanId, OptimizeSelection.ALL, BlockingOptions.NONE);
    }

    public AssembledProblem assemble(String activityPlanId, OptimizeSelection optimize, BlockingOptions blocking) {
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
            List<CoachTimeSlot> coachTimeSlots = coachTimeSlotRepository.findByCoachProfileId(cp.id());
            long[] unavailable = coachTimeSlots.stream()
                    .filter(ct -> CoachTimeSlot.UNAVAILABLE.equals(ct.kind()))
                    .map(CoachTimeSlot::timeSlotId)
                    .filter(timeSlotIdx::contains)
                    .mapToLong(timeSlotIdx::id)
                    .distinct()
                    .sorted()
                    .toArray();
            // M6b: backs coachPreferredTimeSlot (the M5 tri-state's PREFERRED kind, its first solver
            // consumer).
            long[] preferred = coachTimeSlots.stream()
                    .filter(ct -> CoachTimeSlot.PREFERRED.equals(ct.kind()))
                    .map(CoachTimeSlot::timeSlotId)
                    .filter(timeSlotIdx::contains)
                    .mapToLong(timeSlotIdx::id)
                    .distinct()
                    .sorted()
                    .toArray();
            // WI-B: backs coachUnknownTimeSlot - the union of explicit AVAILABLE/PREFERRED rows,
            // i.e. every slot the coach actually expressed an opinion about (as opposed to leaving
            // it "Okänd"/unlisted). PREFERRED is included because a PREFERRED row IS an explicit
            // AVAILABLE-or-better statement (spec §7.3 tri-state), not a fourth independent kind.
            long[] available = coachTimeSlots.stream()
                    .filter(ct -> CoachTimeSlot.AVAILABLE.equals(ct.kind()) || CoachTimeSlot.PREFERRED.equals(ct.kind()))
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
                    maxGroups,
                    preferred,
                    available);
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
            // Non-null only when this participant has a NON-EMPTY canTimes array (see class javadoc
            // "canTimes" bullet) - an empty/absent array must impose no restriction, so it is never
            // recorded here at all, and the complement-computation below is skipped entirely.
            List<String> canTimesSlotIds = null;
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
                        if ("canTimes".equals(field.key())) { // whitelist field (see class javadoc)
                            // Ids unknown to the plan (deleted slots, legacy free-text values) carry
                            // no information; a whitelist that filters down to empty must mean "no
                            // restriction", never "unavailable everywhere".
                            List<String> ids = parseIdArray(cfv.valueJson()).stream()
                                    .filter(timeSlotIdx::contains)
                                    .toList();
                            if (!ids.isEmpty()) {
                                canTimesSlotIds = ids;
                            }
                        } else {
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
            if (canTimesSlotIds != null) {
                // Whitelist -> blacklist translation: every plan time slot NOT in canTimes becomes
                // unavailable, unioned (via sortedDistinct below) with any cannotTimes-derived ids
                // already collected above.
                java.util.Set<String> allowed = new java.util.HashSet<>(canTimesSlotIds);
                for (TimeSlot slot : timeSlots) {
                    if (!allowed.contains(slot.id())) {
                        unavailable.add(timeSlotIdx.id(slot.id()));
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
        // v0.2.0 (COACH-OPTIONAL SOLVING, user feedback from the first field test): a plan with ZERO
        // coach_profiles must solve end-to-end with no coach-related hard violations. Before this
        // change, GroupGenerator's newly-created groups default to requiredCoachCount=1 (see its own
        // javadoc/constructor call - unrelated to this feature, a pre-existing default), so a
        // coach-less plan still got one CoachSlot per group; every slot then had NO CoachFact to
        // resolve to (coachFacts is empty whenever coachProfiles is empty), stayed unassigned, and
        // coachRequirementHard (HARD) penalized every single one - confirmed by the M7 E2E transcript
        // (backend/docs/m7-notes.md: "Hard=-12 is coachRequirementHard (no coaches seeded)") and
        // reproduced/documented in backend/docs/v020-notes.md. Fix: skip CoachSlot construction
        // entirely (regardless of any group's requiredCoachCount) whenever the PLAN has no coach
        // profiles at all - not a per-group requiredCoachCount=0 special case (that already worked;
        // this is the "no coaches registered anywhere" case). With zero CoachSlot entities, every
        // coach-related constraint (coachRequirementHard/coachNoOverlap/coachAvailabilityHard/
        // coachMaxGroups/coachLevelFit/coachPreferenceSoft/coachWish*/coachCannotTrainAndCoachSameTime/
        // savedPlanCoachBlocked/coachPreferredTimeSlot) is inert by construction (each iterates
        // CoachSlot.class, which is now empty) - verified in
        // solver.constraints.CoachlessConstraintInertnessTest.
        List<CoachSlot> coachSlots = new ArrayList<>();
        if (!coachProfiles.isEmpty()) {
            Map<String, List<CoachAssignment>> coachAssignmentsByGroupId = new HashMap<>();
            for (CoachAssignment ca : coachAssignments) {
                coachAssignmentsByGroupId.computeIfAbsent(ca.groupId(), k -> new ArrayList<>()).add(ca);
            }
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
        }

        applyOptimizeSelection(optimize, solverPlayerAssignments, groupSchedules, coachSlots);

        List<SavedPlanResourceUsage> savedPlanResourceUsages =
                collectSavedPlanUsages(plan, blocking, personIdx, courtIdx);

        ConstraintWeightOverrides<HardMediumSoftLongScore> overrides = buildConstraintWeightOverrides(activityPlanId, blocking);

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
                savedPlanResourceUsages,
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

    /**
     * Builds the per-solve {@code ConstraintWeightOverrides}, including the verifier-mandated
     * complement-key fan-out ({@link ConstraintKeys#COMPLEMENTS_OF}: a parent constraint's weight
     * also drives its empty-group complement's weight, since the complement has no {@code
     * constraint_definition}/{@code constraint_weight_config} row of its own) and the §14.4 "visa
     * konflikter men tillåt ändå" downgrade (the three {@code savedPlan*} HARD constraints become
     * {@code ofSoft(1)} for THIS solve only when {@code blocking.conflictsAsWarnings()} - never
     * written back to the DB, so it never leaks into another solve or into {@code
     * ConstraintWeightController}'s view of the plan's persisted weights).
     */
    private ConstraintWeightOverrides<HardMediumSoftLongScore> buildConstraintWeightOverrides(
            String activityPlanId, BlockingOptions blocking) {
        Map<String, HardMediumSoftLongScore> weights = new HashMap<>();
        for (ConstraintDefinition def : constraintDefinitionRepository.findAll()) {
            boolean directlyImplemented = ConstraintKeys.IMPLEMENTED.contains(def.key());
            java.util.Set<String> complements = ConstraintKeys.complementsOf(def.key());
            if (!directlyImplemented && complements.isEmpty()) {
                continue; // not a code key AND not a fan-out parent - nothing to apply.
            }
            if (ConstraintKeys.UNASSIGNED_PLAYER.equals(def.key())) {
                // Symmetric counterpart of the per-plan override guardrail below (M6b review fix
                // F3): the DEFINITION row is equally reachable by a rogue migration or manual SQL
                // edit, and a corrupted seed (disabled, reclassified off MEDIUM, or zero-weighted)
                // would silently destroy the §2 waitlist invariant for EVERY plan at once. Same
                // fail-closed policy: refuse to solve rather than solve wrong.
                if (!def.enabled() || !HardOrSoft.MEDIUM.equals(def.hardOrSoft()) || def.defaultWeight() < 1) {
                    throw new IllegalStateException(
                            "unassignedPlayer constraint_definition row violates the reserved-MEDIUM guardrail "
                                    + "(ADR-006) - refusing to solve");
                }
            }
            HardMediumSoftLongScore score = scoreFor(def.hardOrSoft(), def.enabled() ? def.defaultWeight() : 0);
            if (directlyImplemented) {
                weights.put(def.key(), score);
            }
            for (String complement : complements) {
                weights.put(complement, score);
            }
        }
        for (ConstraintWeightConfig cfg : constraintWeightConfigRepository.findByActivityPlanId(activityPlanId)) {
            boolean directlyImplemented = ConstraintKeys.IMPLEMENTED.contains(cfg.constraintKey());
            java.util.Set<String> complements = ConstraintKeys.complementsOf(cfg.constraintKey());
            if (!directlyImplemented && complements.isEmpty()) {
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
            HardMediumSoftLongScore score = scoreFor(cfg.hardOrSoft(), cfg.enabled() ? cfg.weight() : 0);
            if (directlyImplemented) {
                weights.put(cfg.constraintKey(), score);
            }
            for (String complement : complements) {
                weights.put(complement, score);
            }
        }
        if (blocking.conflictsAsWarnings()) {
            HardMediumSoftLongScore warning = HardMediumSoftLongScore.ofSoft(1);
            weights.put(ConstraintKeys.SAVED_PLAN_PERSON_BLOCKED, warning);
            weights.put(ConstraintKeys.SAVED_PLAN_COACH_BLOCKED, warning);
            weights.put(ConstraintKeys.SAVED_PLAN_COURT_BLOCKED, warning);
        }
        return ConstraintWeightOverrides.of(weights);
    }

    /**
     * §15.5 "optimera endast X" (design §5 note, spec §15.5): pins every entity of a class whose
     * {@code OptimizeSelection} field is {@code false}, regardless of individual {@code locked}
     * flags. 400s if the class isn't fully assigned yet - a class-level pin only makes sense on a
     * previously-solved plan (see {@link OptimizeSelection}'s javadoc).
     */
    private void applyOptimizeSelection(
            OptimizeSelection optimize,
            List<se.klubb.groupplanner.solver.domain.PlayerAssignment> players,
            List<GroupSchedule> schedules,
            List<CoachSlot> coachSlots) {
        if (!optimize.players()) {
            for (se.klubb.groupplanner.solver.domain.PlayerAssignment pa : players) {
                if (pa.getGroup() == null) {
                    throw new BadRequestException(
                            "Cannot pin all players (optimize.players=false): participant " + pa.getId()
                                    + " has no assigned group yet - run a full solve first");
                }
            }
            players.forEach(pa -> pa.setPinned(true));
        }
        if (!optimize.schedule()) {
            for (GroupSchedule gs : schedules) {
                if (gs.getTrainingBlock() == null) {
                    throw new BadRequestException(
                            "Cannot pin all group schedules (optimize.schedule=false): group '" + gs.getGroup().name()
                                    + "' has no assigned training block yet - run a full solve first");
                }
            }
            schedules.forEach(gs -> gs.setPinned(true));
        }
        if (!optimize.coaches()) {
            for (CoachSlot cs : coachSlots) {
                if (cs.getCoach() == null) {
                    throw new BadRequestException(
                            "Cannot pin all coach slots (optimize.coaches=false): group '" + cs.getGroup().name()
                                    + "' slot " + cs.getSlotIndex() + " has no assigned coach yet - run a full solve first");
                }
            }
            coachSlots.forEach(cs -> cs.setPinned(true));
        }
    }

    /**
     * Cross-plan blocking assembly (design §6.2, spec §14.3/§14.4): loads every LOCKED {@code
     * saved_plan} in the same season as {@code plan}, excluding {@code plan} itself, and turns their
     * {@code saved_plan_resource_usage} rows into {@link SavedPlanResourceUsage} facts per the
     * §14.4 checkboxes. A usage whose person/court is not even present in THIS plan's solver id
     * indexes is skipped - it can never match any constraint anyway (no {@code PlayerAssignment}/
     * {@code CoachSlot}/{@code TrainingBlock} in this solve could reference it), so including it
     * would be pure overhead.
     *
     * <p><b>Deduplication (M8 review fix, finding 2):</b> facts are deduped on the canonical key
     * {@code (usageType, personId, courtId, TimeKey)}. Since M8 made every save a NEW versioned
     * {@code saved_plan} row, TWO locked snapshots of the same plan (lock v1, re-save, lock v2)
     * would otherwise each contribute an identical fact for the same real-world commitment —
     * doubling the hard-score magnitude of a single genuine clash and duplicating the corresponding
     * explanation justifications. The same key also collapses the (legitimate but redundant) case
     * of two DIFFERENT plans committing the same person/court at the same time: one blocking fact
     * fully expresses "this resource is taken at this time". First occurrence wins, which is
     * deterministic (snapshots iterate in {@code ORDER BY id} = creation order; rows within a
     * snapshot likewise), so {@code sourcePlanName} deterministically names the OLDEST locked
     * snapshot's plan.
     */
    private List<SavedPlanResourceUsage> collectSavedPlanUsages(
            ActivityPlan plan, BlockingOptions blocking, SolverIdIndex personIdx, SolverIdIndex courtIdx) {
        if (!blocking.anyBlockingEnabled()) {
            return List.of();
        }
        Map<String, SavedPlanResourceUsage> usagesByKey = new java.util.LinkedHashMap<>();
        for (SavedPlan savedPlan : savedPlanRepository.findLockedInSeasonExcludingPlan(plan.seasonPlanId(), plan.id())) {
            for (se.klubb.groupplanner.domain.SavedPlanResourceUsage row
                    : savedPlanResourceUsageRepository.findBySavedPlanId(savedPlan.id())) {
                TimeKey timeKey = TimeKey.of(row.dayOfWeek(), row.date(), row.startTime(), row.endTime());
                boolean isPlayer = se.klubb.groupplanner.domain.SavedPlanResourceUsage.ROLE_PLAYER.equals(row.role());
                boolean isCoach = se.klubb.groupplanner.domain.SavedPlanResourceUsage.ROLE_COACH.equals(row.role());
                boolean personBlockingApplies = (isPlayer && blocking.blockPlayers()) || (isCoach && blocking.blockCoaches());
                if (personBlockingApplies && row.personId() != null && personIdx.contains(row.personId())) {
                    SavedPlanResourceUsage fact = new SavedPlanResourceUsage(
                            UsageType.PERSON, personIdx.id(row.personId()), -1L, timeKey, savedPlan.name(), row.role());
                    usagesByKey.putIfAbsent(dedupKey(fact), fact);
                }
                if (blocking.blockCourts() && row.courtId() != null && courtIdx.contains(row.courtId())) {
                    SavedPlanResourceUsage fact = new SavedPlanResourceUsage(
                            UsageType.COURT, -1L, courtIdx.id(row.courtId()), timeKey, savedPlan.name(), row.role());
                    usagesByKey.putIfAbsent(dedupKey(fact), fact);
                }
            }
        }
        List<SavedPlanResourceUsage> usages = new ArrayList<>(usagesByKey.values());
        // Determinism (ADR-007): built from already-ordered repository queries (both ORDER BY id),
        // but an explicit final sort makes the invariant self-evident regardless of upstream order.
        usages.sort(java.util.Comparator
                .comparing((SavedPlanResourceUsage u) -> u.type().name())
                .thenComparingLong(SavedPlanResourceUsage::personId)
                .thenComparingLong(SavedPlanResourceUsage::courtId)
                .thenComparingInt(u -> u.timeKey().epochDay())
                .thenComparingInt(u -> u.timeKey().dayOfWeek())
                .thenComparingInt(u -> u.timeKey().startMinuteOfDay())
                .thenComparing(SavedPlanResourceUsage::sourcePlanName));
        return usages;
    }

    /** Canonical identity of one blocking fact: everything EXCEPT the provenance fields
     * ({@code sourcePlanName}/{@code sourceDetail}) — two facts agreeing on this key block the
     * exact same (resource, time) and are therefore one real-world commitment. */
    private static String dedupKey(SavedPlanResourceUsage u) {
        return u.type().name() + '|' + u.personId() + '|' + u.courtId() + '|' + u.timeKey();
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
        return TimeKey.of(slot.dayOfWeek(), slot.date(), slot.startTime(), slot.endTime());
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
