package se.klubb.groupplanner.explain;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import se.klubb.groupplanner.explain.ExplanationDtos.PersonExplanationResponse;
import se.klubb.groupplanner.repo.ActivityPlanRepository;
import se.klubb.groupplanner.repo.CoachAssignmentRepository;
import se.klubb.groupplanner.repo.CoachProfileRepository;
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
 * kravspec §23.7 "Förklarbarhet" acceptance criteria, scripted verbatim (this milestone's task item
 * 6 — "all five bullets"):
 * <ol>
 *   <li>Användaren kan klicka på spelare och se varför placeringen gjordes.
 *   <li>Systemet visar uppfyllda faktorer.
 *   <li>Systemet visar brutna önskemål.
 *   <li>Systemet visar varför minst en alternativ grupp inte valdes.
 *   <li>Systemet visar relevanta constraint weights.
 * </ol>
 */
@SpringBootTest
class Section23Point7Test {

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
    private CoachProfileRepository coachProfileRepository;
    @Autowired
    private CoachAssignmentRepository coachAssignmentRepository;
    @Autowired
    private ExplanationService explanationService;

    @Test
    void allFiveSection23Point7BulletsAreSatisfied() {
        ExplanationTestFixture fx = new ExplanationTestFixture(
                seasonPlanRepository, activityPlanRepository, personRepository, participantProfileRepository,
                playerAssignmentRepository, trainingGroupRepository, timeSlotRepository, trainingBlockGenerationService,
                fieldDefinitionRepository, customFieldValueRepository, optimizationRunRepository,
                coachProfileRepository, coachAssignmentRepository);

        List<String> blocks = fx.addTimeSlotWithBlocks("Torsdag 18.00-19.30", 2);
        String groupY = fx.addGroup("Grupp Y", 1, 1, 5, 5, blocks.get(0));
        String groupC = fx.addGroup("Grupp C", 2, 1, 3, 3, blocks.get(1));

        String kalle = fx.addParticipant("Kalle", "Karlsson", 640.0, 3);
        fx.place(kalle, groupY);
        String lisa = fx.addParticipant("Lisa", "Larsson", 650.0, 3);
        fx.place(lisa, groupC);
        for (int i = 0; i < 2; i++) {
            fx.place(fx.addParticipant("F" + i, "Filler", 655.0, 3), groupC);
        }
        fx.wish(kalle, lisa, "playWith");
        String runId = fx.insertFinishedRun();

        // 1. "Klicka på spelare och se varför placeringen gjordes" - the endpoint call itself.
        PersonExplanationResponse explanation = explanationService.explainPerson(fx.planId, runId, kalle);
        assertThat(explanation).isNotNull();
        assertThat(explanation.selectedGroup()).isNotNull();

        // 2. Uppfyllda faktorer.
        assertThat(explanation.positiveFactors()).isNotEmpty();

        // 3. Brutna önskemål.
        assertThat(explanation.brokenWishes()).isNotEmpty();
        assertThat(explanation.brokenWishes().get(0).messageSv()).isNotBlank();

        // 4. Varför minst en alternativ grupp inte valdes (with reasons).
        assertThat(explanation.alternatives()).isNotEmpty();
        assertThat(explanation.alternatives()).allSatisfy(alt -> {
            assertThat(alt.verdict()).isNotBlank();
            assertThat(alt.narrativeSv()).isNotBlank();
        });

        // 5. Relevanta constraint weights.
        assertThat(explanation.appliedWeights()).isNotEmpty();
        assertThat(explanation.appliedWeights()).allSatisfy(w -> {
            assertThat(w.label()).isNotBlank();
            assertThat(w.level()).isIn("HARD", "MEDIUM", "SOFT");
        });
    }
}
