package se.klubb.groupplanner.explain;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import se.klubb.groupplanner.domain.ActivityPlan;
import se.klubb.groupplanner.domain.CustomFieldValue;
import se.klubb.groupplanner.domain.OptimizationRun;
import se.klubb.groupplanner.domain.ParticipantProfile;
import se.klubb.groupplanner.domain.Person;
import se.klubb.groupplanner.domain.PlayerAssignment;
import se.klubb.groupplanner.domain.SeasonPlan;
import se.klubb.groupplanner.domain.TimeSlot;
import se.klubb.groupplanner.domain.TrainingGroup;
import se.klubb.groupplanner.repo.ActivityPlanRepository;
import se.klubb.groupplanner.repo.CustomFieldValueRepository;
import se.klubb.groupplanner.repo.FieldDefinitionRepository;
import se.klubb.groupplanner.repo.OptimizationRunRepository;
import se.klubb.groupplanner.repo.ParticipantProfileRepository;
import se.klubb.groupplanner.repo.PersonRepository;
import se.klubb.groupplanner.repo.PlayerAssignmentRepository;
import se.klubb.groupplanner.repo.SeasonPlanRepository;
import se.klubb.groupplanner.repo.TimeSlotRepository;
import se.klubb.groupplanner.repo.TrainingGroupRepository;
import se.klubb.groupplanner.resources.TrainingBlockGenerationService;
import se.klubb.groupplanner.resources.TrainingBlockView;
import se.klubb.groupplanner.util.Uuid7;

/**
 * Hand-crafted fixture builder for M7 explainability/what-if tests — deliberately NOT the CSV-driven
 * {@code se.klubb.groupplanner.solver.regression.TestDatasetLoader} (that loader is for the golden-
 * score regression suite, where the exact final placement is whatever the solver converges to). These
 * tests need EXACT, precisely-controlled final placements (e.g. "group C is full at maxSize with
 * every member's priority &gt;= Kalle's") to exercise specific branches of {@code ExplanationService}/
 * {@code WhatIfService} deterministically — so this fixture writes {@code player_assignment} rows
 * directly to the desired final state and inserts a plain {@code FINISHED} {@code optimization_run}
 * row, instead of running an actual (nondeterministic-to-steer) Timefold solve. See
 * backend/docs/m7-notes.md for the full reasoning; {@code ExplanationService}/{@code WhatIfService}
 * only ever read CURRENT persisted state via {@code SolverInputAssembler.assemble}, so this is
 * exactly as "real" a fixture as a genuine solve from the read side both exercise.
 */
public final class ExplanationTestFixture {

    private final ActivityPlanRepository activityPlanRepository;
    private final PersonRepository personRepository;
    private final ParticipantProfileRepository participantProfileRepository;
    private final PlayerAssignmentRepository playerAssignmentRepository;
    private final TrainingGroupRepository trainingGroupRepository;
    private final TimeSlotRepository timeSlotRepository;
    private final TrainingBlockGenerationService trainingBlockGenerationService;
    private final FieldDefinitionRepository fieldDefinitionRepository;
    private final CustomFieldValueRepository customFieldValueRepository;
    private final OptimizationRunRepository optimizationRunRepository;

    public final String planId;

    private final Map<String, List<String>> wishAccumulator = new HashMap<>();

    public ExplanationTestFixture(
            SeasonPlanRepository seasonPlanRepository,
            ActivityPlanRepository activityPlanRepository,
            PersonRepository personRepository,
            ParticipantProfileRepository participantProfileRepository,
            PlayerAssignmentRepository playerAssignmentRepository,
            TrainingGroupRepository trainingGroupRepository,
            TimeSlotRepository timeSlotRepository,
            TrainingBlockGenerationService trainingBlockGenerationService,
            FieldDefinitionRepository fieldDefinitionRepository,
            CustomFieldValueRepository customFieldValueRepository,
            OptimizationRunRepository optimizationRunRepository) {
        this.activityPlanRepository = activityPlanRepository;
        this.personRepository = personRepository;
        this.participantProfileRepository = participantProfileRepository;
        this.playerAssignmentRepository = playerAssignmentRepository;
        this.trainingGroupRepository = trainingGroupRepository;
        this.timeSlotRepository = timeSlotRepository;
        this.trainingBlockGenerationService = trainingBlockGenerationService;
        this.fieldDefinitionRepository = fieldDefinitionRepository;
        this.customFieldValueRepository = customFieldValueRepository;
        this.optimizationRunRepository = optimizationRunRepository;

        Instant now = Instant.now();
        SeasonPlan season = seasonPlanRepository.insert(new SeasonPlan(Uuid7.generate(), "VT-explain-test", null, null, "active", now, now));
        ActivityPlan plan = activityPlanRepository.insert(
                new ActivityPlan(Uuid7.generate(), season.id(), "Explain Test", "beach", "draft", 5, 1, 5, null, now, now));
        this.planId = plan.id();
    }

    /** One time slot with {@code courts} 1:1 blocks; returns the created block ids in court order. */
    public List<String> addTimeSlotWithBlocks(String label, int courts) {
        TimeSlot slot = timeSlotRepository.insert(
                new TimeSlot(Uuid7.generate(), planId, "THURSDAY", null, "18:00", "19:30", 90, label));
        List<TrainingBlockView> blocks = trainingBlockGenerationService.generateForSlot(slot, courts);
        return blocks.stream().map(TrainingBlockView::id).toList();
    }

    public String addGroup(String name, int groupOrder, int minSize, int targetSize, int maxSize, String assignedBlockId) {
        return addGroup(name, groupOrder, minSize, targetSize, maxSize, assignedBlockId, null, null);
    }

    /** Band-capable overload ({@code levelMin}/{@code levelMax} on the 0-1000 scale, nullable) —
     * used by the M1-review level-band truthfulness tests. */
    public String addGroup(
            String name, int groupOrder, int minSize, int targetSize, int maxSize, String assignedBlockId,
            Double levelMin, Double levelMax) {
        TrainingGroup group = trainingGroupRepository.insert(new TrainingGroup(
                Uuid7.generate(), planId, name, groupOrder, "beach", minSize, targetSize, maxSize, 0,
                levelMin, levelMax, assignedBlockId, false));
        return group.id();
    }

    /** Creates a person+participant, an {@code imported} player_assignment row, and (if {@code
     * priority != null}) a {@code priority} field value. Returns the participant_profile id. */
    public String addParticipant(String firstName, String lastName, Double estimatedLevel, Integer priority) {
        Instant now = Instant.now();
        Person person = personRepository.insert(new Person(
                Uuid7.generate(), firstName, lastName, null, null, null, null, true, false, null, now, now));
        ParticipantProfile profile = participantProfileRepository.insert(new ParticipantProfile(
                Uuid7.generate(), person.id(), planId, null, null, null, null, estimatedLevel, estimatedLevel != null ? 1.0 : null,
                null, null, null, false, false));
        playerAssignmentRepository.insertImportedIfAbsent(profile.id());
        if (priority != null) {
            String priorityFieldId = requireGlobalField("priority").id();
            customFieldValueRepository.upsert(priorityFieldId, CustomFieldValue.ENTITY_TYPE_PARTICIPANT, profile.id(), String.valueOf(priority));
        }
        return profile.id();
    }

    public void place(String participantProfileId, String groupIdOrNull) {
        playerAssignmentRepository.updateGroupAndSource(participantProfileId, groupIdOrNull, PlayerAssignment.SOURCE_SOLVER);
    }

    /** Adds a directed personRelation wish (e.g. {@code fieldKey = "playWith"}) from {@code
     * fromParticipantId} to {@code toParticipantId}, accumulating multiple targets for the same
     * (participant, field) pair into one JSON array — mirroring how the real field-value API stores
     * multi-select relation values. */
    public void wish(String fromParticipantId, String toParticipantId, String fieldKey) {
        String fieldId = requireGlobalField(fieldKey).id();
        String accKey = fieldId + "|" + fromParticipantId;
        List<String> targets = wishAccumulator.computeIfAbsent(accKey, k -> new ArrayList<>());
        targets.add(toParticipantId);
        customFieldValueRepository.upsert(fieldId, CustomFieldValue.ENTITY_TYPE_PARTICIPANT, fromParticipantId, toJsonArray(targets));
    }

    /** Inserts a plain {@code FINISHED} {@code optimization_run} row stamped with the plan's CURRENT
     * revision (see class javadoc — {@code ExplanationService}/{@code WhatIfService} only ever read
     * live persisted state, so this row exists purely to be a valid {@code runId} for the URL/request
     * shape and the staleness envelope's {@code basedOnRevision}). */
    public String insertFinishedRun() {
        Instant now = Instant.now();
        int revision = activityPlanRepository.getPlanRevision(planId);
        OptimizationRun run = new OptimizationRun(
                Uuid7.generate(), planId, "{}", "{}", "0hard/0medium/0soft", OptimizationRun.STATUS_FINISHED,
                now.toString(), now.toString(), 0, "{}", revision);
        return optimizationRunRepository.insert(run).id();
    }

    public int currentRevision() {
        return activityPlanRepository.getPlanRevision(planId);
    }

    public int bumpRevision() {
        return activityPlanRepository.bumpRevision(planId);
    }

    private se.klubb.groupplanner.domain.FieldDefinition requireGlobalField(String key) {
        return fieldDefinitionRepository.findGlobalByKey(key)
                .orElseThrow(() -> new IllegalStateException("Missing seeded standard field: " + key));
    }

    private static String toJsonArray(List<String> values) {
        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < values.size(); i++) {
            if (i > 0) {
                sb.append(',');
            }
            sb.append('"').append(values.get(i)).append('"');
        }
        return sb.append(']').toString();
    }
}
