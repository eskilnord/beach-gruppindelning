package se.klubb.groupplanner.solver.assemble;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Path;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import se.klubb.groupplanner.domain.ActivityPlan;
import se.klubb.groupplanner.domain.CustomFieldValue;
import se.klubb.groupplanner.domain.FieldDefinition;
import se.klubb.groupplanner.domain.ParticipantProfile;
import se.klubb.groupplanner.domain.Person;
import se.klubb.groupplanner.domain.SeasonPlan;
import se.klubb.groupplanner.domain.TimeSlot;
import se.klubb.groupplanner.repo.ActivityPlanRepository;
import se.klubb.groupplanner.repo.CustomFieldValueRepository;
import se.klubb.groupplanner.repo.FieldDefinitionRepository;
import se.klubb.groupplanner.repo.ParticipantProfileRepository;
import se.klubb.groupplanner.repo.PersonRepository;
import se.klubb.groupplanner.repo.PlayerAssignmentRepository;
import se.klubb.groupplanner.repo.SeasonPlanRepository;
import se.klubb.groupplanner.repo.TimeSlotRepository;
import se.klubb.groupplanner.resources.TrainingBlockGenerationService;
import se.klubb.groupplanner.solver.domain.GroupPlanSolution;
import se.klubb.groupplanner.solver.domain.PlayerAssignment;
import se.klubb.groupplanner.solver.domain.TrainingBlock;
import se.klubb.groupplanner.util.Uuid7;

/**
 * {@code canTimes} whitelist semantics (WI-A, see {@link SolverInputAssembler}'s class javadoc
 * "canTimes" bullet): a NON-EMPTY {@code canTimes} array means "only these time slots are OK" - its
 * complement (every OTHER plan time slot) is folded into the participant's {@code
 * unavailableTimeSlotIds}, unioned with whatever {@code cannotTimes} already contributed. An empty
 * or absent {@code canTimes} array must impose no restriction at all.
 */
@SpringBootTest
class SolverInputAssemblerCanTimesTest {

    @TempDir
    static Path dataDir;

    @DynamicPropertySource
    static void appDataDir(DynamicPropertyRegistry registry) {
        registry.add("app.data-dir", () -> dataDir.toString());
    }

    @Autowired
    private SolverInputAssembler assembler;
    @Autowired
    private SeasonPlanRepository seasonPlanRepository;
    @Autowired
    private ActivityPlanRepository activityPlanRepository;
    @Autowired
    private PersonRepository personRepository;
    @Autowired
    private ParticipantProfileRepository participantProfileRepository;
    @Autowired
    private PlayerAssignmentRepository playerAssignmentRepository;
    @Autowired
    private TimeSlotRepository timeSlotRepository;
    @Autowired
    private TrainingBlockGenerationService trainingBlockGenerationService;
    @Autowired
    private FieldDefinitionRepository fieldDefinitionRepository;
    @Autowired
    private CustomFieldValueRepository customFieldValueRepository;

    private String planId;
    private TimeSlot slotA;
    private TimeSlot slotB;
    private TimeSlot slotC;

    /** Fresh season/plan with three distinct time slots, each backed by one training block (so the
     *  assembled solution's {@link TrainingBlock} facts let {@link #slotLongIds} translate a DB
     *  {@code time_slot} id into its deterministic solver {@code long} id without hardcoding
     *  {@link SolverIdIndex}'s internal ordering). */
    private void setUpPlanWithThreeSlots() {
        Instant now = Instant.now();
        SeasonPlan season = seasonPlanRepository.insert(new SeasonPlan(Uuid7.generate(), "VT26", null, null, "active", now, now));
        ActivityPlan plan = activityPlanRepository.insert(
                new ActivityPlan(Uuid7.generate(), season.id(), "Herr", "beach", "draft", null, null, null, null, now, now));
        planId = plan.id();

        slotA = timeSlotRepository.insert(new TimeSlot(Uuid7.generate(), planId, "MONDAY", null, "18:00", "19:30", null, "SlotA"));
        slotB = timeSlotRepository.insert(new TimeSlot(Uuid7.generate(), planId, "TUESDAY", null, "18:00", "19:30", null, "SlotB"));
        slotC = timeSlotRepository.insert(new TimeSlot(Uuid7.generate(), planId, "WEDNESDAY", null, "18:00", "19:30", null, "SlotC"));
        trainingBlockGenerationService.generateForSlot(slotA, 1);
        trainingBlockGenerationService.generateForSlot(slotB, 1);
        trainingBlockGenerationService.generateForSlot(slotC, 1);
    }

    private String createParticipant(String firstName) {
        Instant now = Instant.now();
        Person person = personRepository.insert(new Person(
                Uuid7.generate(), firstName, "Testsson", null, null, null, null, true, false, null, now, now));
        ParticipantProfile profile = participantProfileRepository.insert(new ParticipantProfile(
                Uuid7.generate(), person.id(), planId, null, null, null, null, null, null, null, null, null, false, false));
        playerAssignmentRepository.insertImportedIfAbsent(profile.id());
        return profile.id();
    }

    private void writeTimeField(String key, String participantId, TimeSlot... slots) {
        FieldDefinition field = fieldDefinitionRepository.findGlobalByKey(key).orElseThrow();
        StringBuilder json = new StringBuilder("[");
        for (int i = 0; i < slots.length; i++) {
            if (i > 0) {
                json.append(',');
            }
            json.append('"').append(slots[i].id()).append('"');
        }
        json.append(']');
        customFieldValueRepository.upsert(field.id(), CustomFieldValue.ENTITY_TYPE_PARTICIPANT, participantId, json.toString());
    }

    private Map<TimeSlot, Long> slotLongIds(GroupPlanSolution solution) {
        Map<TimeSlot, Long> result = new HashMap<>();
        for (TimeSlot slot : List.of(slotA, slotB, slotC)) {
            TrainingBlock block = solution.getTrainingBlocks().stream()
                    .filter(b -> b.label().startsWith(slot.label()))
                    .findFirst().orElseThrow();
            result.put(slot, block.timeSlotId());
        }
        return result;
    }

    @Test
    void nonEmptyCanTimesMakesItsComplementUnavailable() {
        setUpPlanWithThreeSlots();
        String participantId = createParticipant("Ada");
        writeTimeField("canTimes", participantId, slotA); // only slotA allowed

        GroupPlanSolution solution = assembler.assemble(planId).solution();
        Map<TimeSlot, Long> longIds = slotLongIds(solution);
        PlayerAssignment pa = solution.getPlayerAssignments().get(0);

        assertThat(pa.canAttend(longIds.get(slotA))).isTrue();
        assertThat(pa.canAttend(longIds.get(slotB))).isFalse();
        assertThat(pa.canAttend(longIds.get(slotC))).isFalse();
    }

    @Test
    void emptyCanTimesArrayImposesNoRestriction() {
        setUpPlanWithThreeSlots();
        String participantId = createParticipant("Bo");
        writeTimeField("canTimes", participantId); // explicit empty array

        GroupPlanSolution solution = assembler.assemble(planId).solution();
        Map<TimeSlot, Long> longIds = slotLongIds(solution);
        PlayerAssignment pa = solution.getPlayerAssignments().get(0);

        assertThat(pa.canAttend(longIds.get(slotA))).isTrue();
        assertThat(pa.canAttend(longIds.get(slotB))).isTrue();
        assertThat(pa.canAttend(longIds.get(slotC))).isTrue();
    }

    @Test
    void missingCanTimesFieldImposesNoRestriction() {
        setUpPlanWithThreeSlots();
        createParticipant("Cissi");
        // No canTimes custom_field_value row written at all for this participant.

        GroupPlanSolution solution = assembler.assemble(planId).solution();
        Map<TimeSlot, Long> longIds = slotLongIds(solution);
        PlayerAssignment pa = solution.getPlayerAssignments().get(0);

        assertThat(pa.canAttend(longIds.get(slotA))).isTrue();
        assertThat(pa.canAttend(longIds.get(slotB))).isTrue();
        assertThat(pa.canAttend(longIds.get(slotC))).isTrue();
    }

    @Test
    void canTimesWithOnlyUnknownIdsImposesNoRestriction() {
        // Deleted slots or legacy free-text values leave canTimes ids the plan no longer knows.
        // Such a whitelist carries no information and must NOT collapse to "unavailable everywhere".
        setUpPlanWithThreeSlots();
        String participantId = createParticipant("Elin");
        FieldDefinition field = fieldDefinitionRepository.findGlobalByKey("canTimes").orElseThrow();
        customFieldValueRepository.upsert(field.id(), CustomFieldValue.ENTITY_TYPE_PARTICIPANT, participantId,
                "[\"" + Uuid7.generate() + "\",\"ej 21\"]");

        GroupPlanSolution solution = assembler.assemble(planId).solution();
        Map<TimeSlot, Long> longIds = slotLongIds(solution);
        PlayerAssignment pa = solution.getPlayerAssignments().get(0);

        assertThat(pa.canAttend(longIds.get(slotA))).isTrue();
        assertThat(pa.canAttend(longIds.get(slotB))).isTrue();
        assertThat(pa.canAttend(longIds.get(slotC))).isTrue();
    }

    @Test
    void canTimesComplementIsUnionedWithCannotTimes() {
        setUpPlanWithThreeSlots();
        String participantId = createParticipant("Disa");
        writeTimeField("canTimes", participantId, slotA, slotC); // slotA/slotC allowed -> complement is slotB
        writeTimeField("cannotTimes", participantId, slotC); // slotC additionally blacklisted directly

        GroupPlanSolution solution = assembler.assemble(planId).solution();
        Map<TimeSlot, Long> longIds = slotLongIds(solution);
        PlayerAssignment pa = solution.getPlayerAssignments().get(0);

        assertThat(pa.canAttend(longIds.get(slotA))).isTrue();
        assertThat(pa.canAttend(longIds.get(slotB))).isFalse(); // canTimes complement
        assertThat(pa.canAttend(longIds.get(slotC))).isFalse(); // cannotTimes (union with the complement)
    }
}
