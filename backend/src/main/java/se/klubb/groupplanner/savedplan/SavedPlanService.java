package se.klubb.groupplanner.savedplan;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import se.klubb.groupplanner.api.error.BadRequestException;
import se.klubb.groupplanner.api.error.ConflictException;
import se.klubb.groupplanner.api.error.NotFoundException;
import se.klubb.groupplanner.domain.ActivityPlan;
import se.klubb.groupplanner.domain.CoachAssignment;
import se.klubb.groupplanner.domain.CoachProfile;
import se.klubb.groupplanner.domain.Court;
import se.klubb.groupplanner.domain.CustomFieldValue;
import se.klubb.groupplanner.domain.FieldDefinition;
import se.klubb.groupplanner.domain.OptimizationRun;
import se.klubb.groupplanner.domain.ParticipantProfile;
import se.klubb.groupplanner.domain.Person;
import se.klubb.groupplanner.domain.PlayerAssignment;
import se.klubb.groupplanner.domain.SavedPlan;
import se.klubb.groupplanner.domain.SavedPlanResourceUsage;
import se.klubb.groupplanner.domain.TimeSlot;
import se.klubb.groupplanner.domain.TrainingBlock;
import se.klubb.groupplanner.domain.TrainingGroup;
import se.klubb.groupplanner.fields.ConstraintWeightService;
import se.klubb.groupplanner.fields.ConstraintWeightView;
import se.klubb.groupplanner.repo.ActivityPlanRepository;
import se.klubb.groupplanner.repo.CoachAssignmentRepository;
import se.klubb.groupplanner.repo.CoachProfileRepository;
import se.klubb.groupplanner.repo.CourtRepository;
import se.klubb.groupplanner.repo.CustomFieldValueRepository;
import se.klubb.groupplanner.repo.FieldDefinitionRepository;
import se.klubb.groupplanner.repo.OptimizationRunRepository;
import se.klubb.groupplanner.repo.ParticipantProfileRepository;
import se.klubb.groupplanner.repo.PersonRepository;
import se.klubb.groupplanner.repo.PlayerAssignmentRepository;
import se.klubb.groupplanner.repo.SavedPlanRepository;
import se.klubb.groupplanner.repo.SavedPlanResourceUsageRepository;
import se.klubb.groupplanner.repo.TimeSlotRepository;
import se.klubb.groupplanner.repo.TrainingBlockRepository;
import se.klubb.groupplanner.repo.TrainingGroupRepository;
import se.klubb.groupplanner.util.Uuid7;

/**
 * The "spara plan" lifecycle (spec §14.1-§14.4, docs/design/02-product-data-ui.md §1) — productionized
 * in M8 from the M6b-minimal version documented in backend/docs/m6b-notes.md ("{@code snapshot_json}/
 * {@code score}/{@code optimization_run_id} are left {@code null} here; M8 fills them in").
 *
 * <p><b>{@link #materialize} does two things every time it is called, on ANY status (M8 task item 1,
 * "red-team correction"):</b>
 * <ol>
 *   <li>Builds the FULL {@code snapshot_json} (spec §14.1: groups, players per group, coaches per
 *       group, time/court per group, constraint weights, locks, score, the optimization run) from
 *       the activity plan's CURRENT persisted state — {@code importedComment}/{@code internalNote}
 *       are never read by any of the private {@code *Json} builders below, matching CLAUDE.md's
 *       confidentiality rule (covered by {@code SavedPlanSnapshotLeakTest}).</li>
 *   <li>Materializes {@code saved_plan_resource_usage} rows (person/coach/court × TimeKey), exactly
 *       as the M6b version did — the resource-usage side was ALREADY correct per the red-team
 *       correction ("materialize on save, not only lock"): every call, regardless of {@code status},
 *       already wrote usage rows; M8 keeps that behavior unchanged.</li>
 * </ol>
 *
 * <p>Every call creates a brand-new {@code saved_plan} row (never updates an existing one in place)
 * — "spara plan" is a versioning action: each save is its own immutable-in-spirit snapshot, and
 * {@code GET .../saved-plans} lists them in creation order. "Refresh on re-save" (the task's own
 * phrasing) falls out of this for free: calling {@link #materialize} again for the same activity plan
 * builds an entirely fresh snapshot + usage-row set from whatever is CURRENTLY persisted, with no
 * stale leftovers to reconcile.
 *
 * <p>Status transitions ({@code PATCH}) go through {@link #transitionStatus}, validated by {@link
 * SavedPlanLifecycle}. Deletion ({@link #delete}) is restricted to {@code draft}/{@code saved} rows
 * (see {@link SavedPlanLifecycle#isDeletable}) — {@code saved_plan_resource_usage} rows cascade-delete
 * automatically ({@code ON DELETE CASCADE} in V6, {@code PRAGMA foreign_keys=ON}).
 */
@Service
public class SavedPlanService {

    private final ActivityPlanRepository activityPlanRepository;
    private final TrainingGroupRepository trainingGroupRepository;
    private final PlayerAssignmentRepository playerAssignmentRepository;
    private final CoachAssignmentRepository coachAssignmentRepository;
    private final ParticipantProfileRepository participantProfileRepository;
    private final CoachProfileRepository coachProfileRepository;
    private final TrainingBlockRepository trainingBlockRepository;
    private final TimeSlotRepository timeSlotRepository;
    private final CourtRepository courtRepository;
    private final PersonRepository personRepository;
    private final CustomFieldValueRepository customFieldValueRepository;
    private final FieldDefinitionRepository fieldDefinitionRepository;
    private final ConstraintWeightService constraintWeightService;
    private final OptimizationRunRepository optimizationRunRepository;
    private final SavedPlanRepository savedPlanRepository;
    private final SavedPlanResourceUsageRepository savedPlanResourceUsageRepository;
    private final ObjectMapper objectMapper;

    public SavedPlanService(
            ActivityPlanRepository activityPlanRepository,
            TrainingGroupRepository trainingGroupRepository,
            PlayerAssignmentRepository playerAssignmentRepository,
            CoachAssignmentRepository coachAssignmentRepository,
            ParticipantProfileRepository participantProfileRepository,
            CoachProfileRepository coachProfileRepository,
            TrainingBlockRepository trainingBlockRepository,
            TimeSlotRepository timeSlotRepository,
            CourtRepository courtRepository,
            PersonRepository personRepository,
            CustomFieldValueRepository customFieldValueRepository,
            FieldDefinitionRepository fieldDefinitionRepository,
            ConstraintWeightService constraintWeightService,
            OptimizationRunRepository optimizationRunRepository,
            SavedPlanRepository savedPlanRepository,
            SavedPlanResourceUsageRepository savedPlanResourceUsageRepository,
            ObjectMapper objectMapper) {
        this.activityPlanRepository = activityPlanRepository;
        this.trainingGroupRepository = trainingGroupRepository;
        this.playerAssignmentRepository = playerAssignmentRepository;
        this.coachAssignmentRepository = coachAssignmentRepository;
        this.participantProfileRepository = participantProfileRepository;
        this.coachProfileRepository = coachProfileRepository;
        this.trainingBlockRepository = trainingBlockRepository;
        this.timeSlotRepository = timeSlotRepository;
        this.courtRepository = courtRepository;
        this.personRepository = personRepository;
        this.customFieldValueRepository = customFieldValueRepository;
        this.fieldDefinitionRepository = fieldDefinitionRepository;
        this.constraintWeightService = constraintWeightService;
        this.optimizationRunRepository = optimizationRunRepository;
        this.savedPlanRepository = savedPlanRepository;
        this.savedPlanResourceUsageRepository = savedPlanResourceUsageRepository;
        this.objectMapper = objectMapper;
    }

    public List<SavedPlan> list(String activityPlanId) {
        requireActivityPlan(activityPlanId);
        return savedPlanRepository.findByActivityPlanId(activityPlanId);
    }

    public SavedPlanDetailView findOne(String activityPlanId, String savedPlanId) {
        SavedPlan saved = requireOwned(activityPlanId, savedPlanId);
        return toDetail(saved);
    }

    /** {@code POST /api/plans/{planId}/saved-plans {name}} — always creates status {@link
     * SavedPlan#STATUS_SAVED} ("spara plan" IS the save action). */
    @Transactional
    public SavedPlanDetailView save(String activityPlanId, String name) {
        if (name == null || name.isBlank()) {
            throw new BadRequestException("name is required");
        }
        SavedPlan saved = materialize(activityPlanId, name, SavedPlan.STATUS_SAVED);
        return toDetail(saved);
    }

    /**
     * Materializes a {@link SavedPlan} row (status defaults to {@link SavedPlan#STATUS_SAVED} when
     * {@code status} is blank) from {@code activityPlanId}'s current assignments: the full snapshot
     * (spec §14.1) plus {@code saved_plan_resource_usage} rows for cross-plan blocking (§10.24) and
     * season conflict reporting. Kept as its own method (not folded into {@link #save}) because
     * {@code solver.assemble.SavedPlanUsageAssemblyTest}/{@code solver.CrossPlanBlockingTest}-adjacent
     * test infrastructure calls it directly with an explicit status (e.g. {@code STATUS_LOCKED}) to
     * set up cross-plan-blocking fixtures without going through the full PATCH transition flow.
     */
    @Transactional
    public SavedPlan materialize(String activityPlanId, String name, String status) {
        ActivityPlan plan = requireActivityPlan(activityPlanId);
        String resolvedStatus = (status != null && !status.isBlank()) ? status : SavedPlan.STATUS_SAVED;

        List<TrainingGroup> groups = trainingGroupRepository.findByActivityPlanId(activityPlanId);
        List<ParticipantProfile> participants = participantProfileRepository.findByActivityPlanId(activityPlanId);
        List<PlayerAssignment> playerAssignments = playerAssignmentRepository.findByActivityPlanId(activityPlanId);
        List<CoachProfile> coachProfiles = coachProfileRepository.findByActivityPlanId(activityPlanId);
        List<CoachAssignment> coachAssignments = coachAssignmentRepository.findByActivityPlanId(activityPlanId);

        Map<String, TrainingGroup> groupById = indexBy(groups, TrainingGroup::id);
        Map<String, ParticipantProfile> participantById = indexBy(participants, ParticipantProfile::id);
        Map<String, CoachProfile> coachProfileById = indexBy(coachProfiles, CoachProfile::id);
        Map<String, Person> personCache = new HashMap<>();
        Map<String, TrainingBlock> blockCache = new HashMap<>();
        Map<String, TimeSlot> slotCache = new HashMap<>();
        Map<String, Court> courtCache = new HashMap<>();

        Optional<OptimizationRun> latestRun = optimizationRunRepository.findLatestByActivityPlanId(activityPlanId);

        Instant now = Instant.now();
        SavedPlan saved = new SavedPlan(
                Uuid7.generate(),
                activityPlanId,
                (name != null && !name.isBlank()) ? name : plan.name(),
                resolvedStatus,
                writeJson(buildSnapshot(
                        plan, groups, participants, playerAssignments, coachAssignments,
                        participantById, coachProfileById, personCache, blockCache, slotCache, courtCache,
                        latestRun.orElse(null))),
                latestRun.map(OptimizationRun::score).orElse(null),
                latestRun.map(OptimizationRun::id).orElse(null),
                now.toString(),
                now.toString());
        savedPlanRepository.insert(saved);

        materializeUsageRows(saved.id(), playerAssignments, coachAssignments, groupById, participantById, coachProfileById, blockCache, slotCache);

        return saved;
    }

    /** Status transition (spec §14.2, PATCH). Same-status requests are an idempotent no-op (200);
     * unknown target statuses are 400; known-but-illegal transitions are 409 (see {@link
     * SavedPlanLifecycle}). */
    @Transactional
    public SavedPlanDetailView transitionStatus(String activityPlanId, String savedPlanId, String targetStatus) {
        SavedPlan existing = requireOwned(activityPlanId, savedPlanId);
        if (targetStatus == null || targetStatus.isBlank()) {
            throw new BadRequestException("status is required");
        }
        if (!SavedPlanLifecycle.isKnownStatus(targetStatus)) {
            throw new BadRequestException("Unknown saved plan status: " + targetStatus);
        }
        if (existing.status().equals(targetStatus)) {
            return toDetail(existing); // idempotent no-op.
        }
        if (!SavedPlanLifecycle.isLegalTransition(existing.status(), targetStatus)) {
            throw new ConflictException(
                    "Illegal saved plan status transition: " + existing.status() + " -> " + targetStatus);
        }
        savedPlanRepository.updateStatus(savedPlanId, targetStatus, Instant.now().toString());
        return toDetail(savedPlanRepository.findById(savedPlanId).orElseThrow());
    }

    /** {@code DELETE} — only {@code draft}/{@code saved} rows (see {@link
     * SavedPlanLifecycle#isDeletable}); {@code saved_plan_resource_usage} rows cascade-delete via the
     * schema's {@code ON DELETE CASCADE}. */
    @Transactional
    public void delete(String activityPlanId, String savedPlanId) {
        SavedPlan existing = requireOwned(activityPlanId, savedPlanId);
        if (!SavedPlanLifecycle.isDeletable(existing.status())) {
            throw new ConflictException(
                    "Saved plan '" + existing.name() + "' cannot be deleted while status is '" + existing.status()
                            + "' - only draft/saved plans can be deleted (archive it instead)");
        }
        savedPlanRepository.deleteById(savedPlanId);
    }

    // --- usage-row materialization (unchanged M6b behavior: every status, not only locked) ---

    private void materializeUsageRows(
            String savedPlanId,
            List<PlayerAssignment> playerAssignments,
            List<CoachAssignment> coachAssignments,
            Map<String, TrainingGroup> groupById,
            Map<String, ParticipantProfile> participantById,
            Map<String, CoachProfile> coachProfileById,
            Map<String, TrainingBlock> blockCache,
            Map<String, TimeSlot> slotCache) {
        for (PlayerAssignment pa : playerAssignments) {
            if (pa.groupId() == null) {
                continue; // waitlisted: no time/court occupied, nothing to block.
            }
            resolveSchedule(groupById.get(pa.groupId()), blockCache, slotCache).ifPresent(schedule -> {
                ParticipantProfile participant = participantById.get(pa.participantProfileId());
                if (participant != null) {
                    insertUsage(savedPlanId, participant.personId(), SavedPlanResourceUsage.ROLE_PLAYER, schedule);
                }
            });
        }
        for (CoachAssignment ca : coachAssignments) {
            resolveSchedule(groupById.get(ca.groupId()), blockCache, slotCache).ifPresent(schedule -> {
                CoachProfile coach = coachProfileById.get(ca.coachProfileId());
                if (coach != null) {
                    insertUsage(savedPlanId, coach.personId(), SavedPlanResourceUsage.ROLE_COACH, schedule);
                }
            });
        }
    }

    private Optional<ResolvedSchedule> resolveSchedule(
            TrainingGroup group, Map<String, TrainingBlock> blockCache, Map<String, TimeSlot> slotCache) {
        if (group == null || group.assignedTrainingBlockId() == null) {
            return Optional.empty();
        }
        TrainingBlock block = blockCache.computeIfAbsent(
                group.assignedTrainingBlockId(), id -> trainingBlockRepository.findById(id).orElse(null));
        if (block == null) {
            return Optional.empty();
        }
        TimeSlot slot = slotCache.computeIfAbsent(block.timeSlotId(), id -> timeSlotRepository.findById(id).orElse(null));
        if (slot == null) {
            return Optional.empty();
        }
        return Optional.of(new ResolvedSchedule(block, slot));
    }

    private void insertUsage(String savedPlanId, String personId, String role, ResolvedSchedule schedule) {
        TimeSlot slot = schedule.slot();
        TrainingBlock block = schedule.block();
        savedPlanResourceUsageRepository.insert(new SavedPlanResourceUsage(
                Uuid7.generate(), savedPlanId, personId, role, slot.dayOfWeek(), slot.date(), slot.startTime(),
                slot.endTime(), block.courtId()));
    }

    // --- snapshot_json building (spec §14.1) - NEVER reads importedComment/internalNote ---

    private Map<String, Object> buildSnapshot(
            ActivityPlan plan,
            List<TrainingGroup> groups,
            List<ParticipantProfile> participants,
            List<PlayerAssignment> playerAssignments,
            List<CoachAssignment> coachAssignments,
            Map<String, ParticipantProfile> participantById,
            Map<String, CoachProfile> coachProfileById,
            Map<String, Person> personCache,
            Map<String, TrainingBlock> blockCache,
            Map<String, TimeSlot> slotCache,
            Map<String, Court> courtCache,
            OptimizationRun latestRun) {
        Map<String, List<PlayerAssignment>> playersByGroup = new HashMap<>();
        for (PlayerAssignment pa : playerAssignments) {
            if (pa.groupId() != null) {
                playersByGroup.computeIfAbsent(pa.groupId(), k -> new ArrayList<>()).add(pa);
            }
        }
        Map<String, List<CoachAssignment>> coachesByGroup = new HashMap<>();
        for (CoachAssignment ca : coachAssignments) {
            coachesByGroup.computeIfAbsent(ca.groupId(), k -> new ArrayList<>()).add(ca);
        }
        Map<String, PlayerAssignment> playerAssignmentByParticipant = indexBy(playerAssignments, PlayerAssignment::participantProfileId);

        List<Map<String, Object>> groupsJson = new ArrayList<>();
        for (TrainingGroup g : groups) {
            groupsJson.add(groupJson(
                    g, playersByGroup.getOrDefault(g.id(), List.of()), coachesByGroup.getOrDefault(g.id(), List.of()),
                    participantById, coachProfileById, personCache, blockCache, slotCache, courtCache));
        }

        List<Map<String, Object>> waitlistJson = new ArrayList<>();
        for (ParticipantProfile p : participants) {
            PlayerAssignment pa = playerAssignmentByParticipant.get(p.id());
            if (pa != null && pa.groupId() != null) {
                continue; // placed - already covered by its group's "players" array above.
            }
            waitlistJson.add(waitlistEntryJson(p, personCache));
        }

        List<Map<String, Object>> weightsJson = new ArrayList<>();
        for (ConstraintWeightView v : constraintWeightService.listForPlan(plan.id())) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("key", v.key());
            m.put("hardOrSoft", v.hardOrSoft());
            m.put("weight", v.weight());
            m.put("enabled", v.enabled());
            m.put("overridden", v.overridden());
            weightsJson.add(m);
        }

        Map<String, Object> snapshot = new TreeMap<>();
        snapshot.put("activityPlanId", plan.id());
        snapshot.put("activityPlanName", plan.name());
        snapshot.put("category", plan.category());
        snapshot.put("score", latestRun != null ? latestRun.score() : null);
        snapshot.put("optimizationRunId", latestRun != null ? latestRun.id() : null);
        snapshot.put("groups", groupsJson);
        snapshot.put("waitlist", waitlistJson);
        snapshot.put("constraintWeights", weightsJson);
        return snapshot;
    }

    private Map<String, Object> groupJson(
            TrainingGroup g,
            List<PlayerAssignment> playersInGroup,
            List<CoachAssignment> coachesInGroup,
            Map<String, ParticipantProfile> participantById,
            Map<String, CoachProfile> coachProfileById,
            Map<String, Person> personCache,
            Map<String, TrainingBlock> blockCache,
            Map<String, TimeSlot> slotCache,
            Map<String, Court> courtCache) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", g.id());
        m.put("name", g.name());
        m.put("groupOrder", g.groupOrder());
        m.put("category", g.category());
        m.put("minSize", g.minSize());
        m.put("targetSize", g.targetSize());
        m.put("maxSize", g.maxSize());
        m.put("requiredCoachCount", g.requiredCoachCount());
        m.put("levelMin", g.levelMin());
        m.put("levelMax", g.levelMax());
        m.put("locked", g.locked());

        TrainingBlock block = g.assignedTrainingBlockId() != null
                ? blockCache.computeIfAbsent(g.assignedTrainingBlockId(), id -> trainingBlockRepository.findById(id).orElse(null))
                : null;
        TimeSlot slot = block != null ? slotCache.computeIfAbsent(block.timeSlotId(), id -> timeSlotRepository.findById(id).orElse(null)) : null;
        Court court = block != null && block.courtId() != null
                ? courtCache.computeIfAbsent(block.courtId(), id -> courtRepository.findById(id).orElse(null))
                : null;
        m.put("trainingBlockId", block != null ? block.id() : null);
        m.put("timeSlotLabel", slot != null ? slot.label() : null);
        m.put("courtName", court != null ? court.name() : null);

        List<Map<String, Object>> players = new ArrayList<>();
        for (PlayerAssignment pa : playersInGroup) {
            ParticipantProfile participant = participantById.get(pa.participantProfileId());
            if (participant != null) {
                players.add(playerJson(participant, pa, personCache));
            }
        }
        m.put("players", players);

        List<Map<String, Object>> coaches = new ArrayList<>();
        for (CoachAssignment ca : coachesInGroup) {
            CoachProfile coach = coachProfileById.get(ca.coachProfileId());
            if (coach != null) {
                coaches.add(coachJson(coach, ca, personCache));
            }
        }
        m.put("coaches", coaches);
        return m;
    }

    /** {@code participant.importedComment()}/{@code internalNote()} are NEVER read here (CLAUDE.md:
     * "Comments never reach ... *_json snapshots"). Only ranking/level/provenance fields go in. */
    private Map<String, Object> playerJson(ParticipantProfile p, PlayerAssignment pa, Map<String, Person> personCache) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("participantProfileId", p.id());
        m.put("personId", p.personId());
        m.put("displayName", displayNameOf(person(p.personId(), personCache)));
        m.put("rankingPoints", p.rankingPoints());
        m.put("estimatedLevel", p.estimatedLevel());
        m.put("previousGroupName", p.previousGroupName());
        m.put("waitlisted", p.waitlisted());
        m.put("locked", pa.locked());
        m.put("source", pa.source());
        return m;
    }

    private Map<String, Object> waitlistEntryJson(ParticipantProfile p, Map<String, Person> personCache) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("participantProfileId", p.id());
        m.put("personId", p.personId());
        m.put("displayName", displayNameOf(person(p.personId(), personCache)));
        m.put("rankingPoints", p.rankingPoints());
        m.put("estimatedLevel", p.estimatedLevel());
        m.put("priority", priorityOf(p.id()));
        return m;
    }

    /** {@code coach.notes()} is NEVER read here either — same conservative stance as {@link
     * #playerJson}, even though CLAUDE.md's explicit rule only names the two participant-side
     * columns; a coach's free-text notes have no legitimate reason to be in a snapshot either. */
    private Map<String, Object> coachJson(CoachProfile c, CoachAssignment ca, Map<String, Person> personCache) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("coachProfileId", c.id());
        m.put("personId", c.personId());
        m.put("displayName", displayNameOf(person(c.personId(), personCache)));
        m.put("locked", ca.locked());
        m.put("source", ca.source());
        return m;
    }

    private Person person(String personId, Map<String, Person> personCache) {
        return personCache.computeIfAbsent(personId, id -> personRepository.findById(id).orElse(null));
    }

    /** Reads the seeded global {@code priority} custom field for one participant (same JSON shape
     * {@code solver.assemble.SolverInputAssembler#parsePriority} consumes) - {@code null} if unset,
     * unlike the solver's own default-substituting parse (this is informational display, not solver
     * input). */
    private Integer priorityOf(String participantProfileId) {
        Optional<FieldDefinition> priorityField = fieldDefinitionRepository.findGlobalByKey("priority");
        if (priorityField.isEmpty()) {
            return null;
        }
        for (CustomFieldValue cfv : customFieldValueRepository.findByEntity(
                CustomFieldValue.ENTITY_TYPE_PARTICIPANT, participantProfileId)) {
            if (cfv.fieldDefinitionId().equals(priorityField.get().id()) && cfv.valueJson() != null) {
                JsonNode node = readTree(cfv.valueJson());
                return node.isNumber() ? node.asInt() : null;
            }
        }
        return null;
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

    private SavedPlanDetailView toDetail(SavedPlan saved) {
        Object parsedSnapshot = saved.snapshotJson() != null ? parseJsonToObject(saved.snapshotJson()) : null;
        return new SavedPlanDetailView(
                saved.id(), saved.activityPlanId(), saved.name(), saved.status(), saved.score(),
                saved.optimizationRunId(), saved.createdAt(), saved.updatedAt(), parsedSnapshot);
    }

    private ActivityPlan requireActivityPlan(String activityPlanId) {
        return activityPlanRepository.findById(activityPlanId)
                .orElseThrow(() -> new NotFoundException("Activity plan not found: " + activityPlanId));
    }

    private SavedPlan requireOwned(String activityPlanId, String savedPlanId) {
        SavedPlan saved = savedPlanRepository.findById(savedPlanId)
                .orElseThrow(() -> new NotFoundException("Saved plan not found: " + savedPlanId));
        if (!saved.activityPlanId().equals(activityPlanId)) {
            throw new NotFoundException("Saved plan not found in plan " + activityPlanId + ": " + savedPlanId);
        }
        return saved;
    }

    private static <T> Map<String, T> indexBy(List<T> items, java.util.function.Function<T, String> idOf) {
        Map<String, T> map = new HashMap<>();
        items.forEach(item -> map.put(idOf.apply(item), item));
        return map;
    }

    private String writeJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
            throw new IllegalStateException("Failed to serialize saved_plan.snapshot_json", e);
        }
    }

    private Object parseJsonToObject(String json) {
        try {
            return objectMapper.readValue(json, Object.class);
        } catch (java.io.IOException e) {
            throw new IllegalStateException("Corrupt saved_plan.snapshot_json", e);
        }
    }

    private JsonNode readTree(String json) {
        try {
            return objectMapper.readTree(json);
        } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
            throw new IllegalStateException("Corrupt custom_field_value JSON: " + json, e);
        }
    }

    private record ResolvedSchedule(TrainingBlock block, TimeSlot slot) {
    }
}
