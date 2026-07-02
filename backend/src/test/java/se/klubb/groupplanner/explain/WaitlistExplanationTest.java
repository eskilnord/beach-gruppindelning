package se.klubb.groupplanner.explain;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import se.klubb.groupplanner.domain.CustomFieldValue;
import se.klubb.groupplanner.explain.ExplanationDtos.PersonExplanationResponse;
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

/**
 * Waitlist explanation branch tests (docs/design/05-solver-verification.md amendment (a), this
 * milestone's task item 6): the concrete hard blocker per group + a data-derived priority narrative
 * (never the mathematically-impossible "placeable but worse" branch a single-move probe would
 * otherwise imply), and the "förbättring möjlig" solver-quality-warning branch for a feasible non-full
 * candidate (deliberately constructed so the waitlisted player has an obviously open, unblocked slot
 * — simulating a solver that stopped before converging).
 */
@SpringBootTest
class WaitlistExplanationTest {

    @TempDir
    static Path dataDir;

    @DynamicPropertySource
    static void appDataDir(DynamicPropertyRegistry registry) {
        registry.add("app.data-dir", () -> dataDir.toString());
    }

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
    private TrainingGroupRepository trainingGroupRepository;
    @Autowired
    private TimeSlotRepository timeSlotRepository;
    @Autowired
    private TrainingBlockGenerationService trainingBlockGenerationService;
    @Autowired
    private FieldDefinitionRepository fieldDefinitionRepository;
    @Autowired
    private CustomFieldValueRepository customFieldValueRepository;
    @Autowired
    private OptimizationRunRepository optimizationRunRepository;
    @Autowired
    private ExplanationService explanationService;
    @Autowired
    private JdbcClient jdbcClient;

    private ExplanationTestFixture newFixture() {
        return new ExplanationTestFixture(
                seasonPlanRepository, activityPlanRepository, personRepository, participantProfileRepository,
                playerAssignmentRepository, trainingGroupRepository, timeSlotRepository, trainingBlockGenerationService,
                fieldDefinitionRepository, customFieldValueRepository, optimizationRunRepository);
    }

    @Test
    void reportsConcreteHardBlockerAndPriorityNarrativePerGroup() {
        ExplanationTestFixture fx = newFixture();
        List<String> blocks = fx.addTimeSlotWithBlocks("Torsdag 18.00-19.30", 2);
        String groupA = fx.addGroup("Grupp A", 1, 1, 2, 2, blocks.get(0));
        String groupB = fx.addGroup("Grupp B", 2, 1, 5, 5, blocks.get(1));

        String m1 = fx.addParticipant("M1", "M", 600.0, 3);
        String m2 = fx.addParticipant("M2", "M", 610.0, 3);
        fx.place(m1, groupA);
        fx.place(m2, groupA); // Grupp A now full (2/2), both priority 3.

        String erik = fx.addParticipant("Erik", "Eriksson", 605.0, 2); // lower priority than group A's members
        fx.place(erik, null);

        // Erik cannot attend Grupp B's time slot at all.
        String timeSlotId = jdbcClient.sql("SELECT time_slot_id FROM training_block WHERE id = :id")
                .param("id", blocks.get(1)).query(String.class).single();
        String cannotTimesFieldId = fieldDefinitionRepository.findGlobalByKey("cannotTimes").orElseThrow().id();
        customFieldValueRepository.upsert(cannotTimesFieldId, CustomFieldValue.ENTITY_TYPE_PARTICIPANT, erik, "[\"" + timeSlotId + "\"]");

        String runId = fx.insertFinishedRun();
        PersonExplanationResponse response = explanationService.explainPerson(fx.planId, runId, erik);

        assertThat(response.selectedGroup()).isNull();
        assertThat(response.waitlist()).isNotNull();
        assertThat(response.waitlist().qualityWarningSv()).isNull(); // both groups genuinely blocked

        assertThat(response.waitlist().perGroupBlockers())
                .anyMatch(b -> b.groupId().equals(groupA) && b.blockerSv().contains("full") && b.blockerSv().contains("2/2"));
        assertThat(response.waitlist().perGroupBlockers())
                .anyMatch(b -> b.groupId().equals(groupB) && b.blockerSv().contains("Erik") && b.blockerSv().toLowerCase().contains("tiden"));
    }

    @Test
    void feasibleNonFullCandidateRendersImprovementPossibleWarningNotAPriorityStory() {
        ExplanationTestFixture fx = newFixture();
        List<String> blocks = fx.addTimeSlotWithBlocks("Torsdag 18.00-19.30", 1);
        String groupA = fx.addGroup("Grupp A", 1, 1, 5, 5, blocks.get(0));

        String m1 = fx.addParticipant("M1", "M", 600.0, 3);
        fx.place(m1, groupA); // 1/5 - plenty of room, no time/wish conflicts.

        // Deliberately-undersolved state: Erik has no reason to be unplaced (no time conflicts, no
        // wish conflicts, room exists) - simulating a solver run that stopped before converging.
        String erik = fx.addParticipant("Erik", "Eriksson", 605.0, 3);
        fx.place(erik, null);

        String runId = fx.insertFinishedRun();
        PersonExplanationResponse response = explanationService.explainPerson(fx.planId, runId, erik);

        assertThat(response.waitlist()).isNotNull();
        assertThat(response.waitlist().qualityWarningSv()).isNotNull();
        assertThat(response.waitlist().qualityWarningSv()).contains("örbättring möjlig");
        // Never rendered as a priority explanation - it is a distinct, honestly-labelled branch.
        assertThat(response.waitlist().perGroupBlockers())
                .anyMatch(b -> b.groupId().equals(groupA) && !b.blockerSv().toLowerCase().contains("prioritet"));
    }
}
