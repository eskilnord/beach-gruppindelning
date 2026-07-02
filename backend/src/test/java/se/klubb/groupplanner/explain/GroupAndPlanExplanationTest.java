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
import se.klubb.groupplanner.explain.ExplanationDtos.GroupExplanationResponse;
import se.klubb.groupplanner.explain.ExplanationDtos.PlanExplanationResponse;
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
 * Group- and plan-level explanations (kravspec §17 "Förklarbarhet: systemet får inte kännas som en
 * black box", levels 2 and 3; docs/design/04-solver.md §14.2).
 */
@SpringBootTest
class GroupAndPlanExplanationTest {

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

    @Test
    void groupLevelReportsSizeTargetLevelSpreadAndBrokenWishMembers() {
        ExplanationTestFixture fx = new ExplanationTestFixture(
                seasonPlanRepository, activityPlanRepository, personRepository, participantProfileRepository,
                playerAssignmentRepository, trainingGroupRepository, timeSlotRepository, trainingBlockGenerationService,
                fieldDefinitionRepository, customFieldValueRepository, optimizationRunRepository);
        List<String> blocks = fx.addTimeSlotWithBlocks("Torsdag 18.00-19.30", 2);
        String groupY = fx.addGroup("Grupp Y", 1, 1, 5, 5, blocks.get(0));
        String groupC = fx.addGroup("Grupp C", 2, 1, 3, 3, blocks.get(1));

        String kalle = fx.addParticipant("Kalle", "Karlsson", 640.0, 3);
        fx.place(kalle, groupY);
        String lisa = fx.addParticipant("Lisa", "Larsson", 720.0, 3);
        fx.place(lisa, groupC);
        fx.wish(kalle, lisa, "playWith");

        String runId = fx.insertFinishedRun();
        GroupExplanationResponse response = explanationService.explainGroup(fx.planId, runId, groupC);

        assertThat(response.groupId()).isEqualTo(groupC);
        assertThat(response.size()).isEqualTo(1);
        assertThat(response.targetSize()).isEqualTo(3);
        assertThat(response.maxSize()).isEqualTo(3);
        assertThat(response.levelMeanSv()).isNotBlank();
        assertThat(response.warnings()).isNotEmpty(); // under minSize
        assertThat(response.membersWithBrokenWishes())
                .anyMatch(m -> m.participantProfileId().equals(lisa) && m.messageSv().contains("Kompisönskemål"));
    }

    @Test
    void planLevelReportsScoreConstraintSummariesWaitlistAndManualReview() {
        ExplanationTestFixture fx = new ExplanationTestFixture(
                seasonPlanRepository, activityPlanRepository, personRepository, participantProfileRepository,
                playerAssignmentRepository, trainingGroupRepository, timeSlotRepository, trainingBlockGenerationService,
                fieldDefinitionRepository, customFieldValueRepository, optimizationRunRepository);
        List<String> blocks = fx.addTimeSlotWithBlocks("Torsdag 18.00-19.30", 1);
        String groupY = fx.addGroup("Grupp Y", 1, 1, 2, 2, blocks.get(0));

        String kalle = fx.addParticipant("Kalle", "Karlsson", 640.0, 3);
        fx.place(kalle, groupY);
        String erik = fx.addParticipant("Erik", "Eriksson", 610.0, 3);
        fx.place(erik, null); // waitlisted

        String runId = fx.insertFinishedRun();
        PlanExplanationResponse response = explanationService.explainPlan(fx.planId, runId);

        assertThat(response.score()).isNotNull();
        assertThat(response.constraintSummaries()).isNotEmpty();
        assertThat(response.constraintSummaries()).anyMatch(c -> c.key().equals("levelBalance"));
        assertThat(response.waitlist()).anyMatch(w -> w.participantProfileId().equals(erik));
        assertThat(response.runId()).isEqualTo(runId);
    }
}
