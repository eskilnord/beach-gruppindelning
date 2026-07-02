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
import se.klubb.groupplanner.explain.ExplanationDtos.WhatIfMoveResponse;
import se.klubb.groupplanner.explain.ExplanationDtos.WhatIfWhyNotResponse;
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
 * §18.1 what-if output-fields test (this milestone's task item 6): "Konsekvens av flytt" must report
 * new group sizes, a level-spread delta (the spec's own "82 -&gt; 141" style example), the total score
 * delta, and whether the move breaks a hard constraint — plus {@code why-not} answering for an
 * ARBITRARY group, not just the automatic candidate set (design §11.5's own framing).
 */
@SpringBootTest
class WhatIfServiceTest {

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
    private WhatIfService whatIfService;

    private ExplanationTestFixture newFixture() {
        return new ExplanationTestFixture(
                seasonPlanRepository, activityPlanRepository, personRepository, participantProfileRepository,
                playerAssignmentRepository, trainingGroupRepository, timeSlotRepository, trainingBlockGenerationService,
                fieldDefinitionRepository, customFieldValueRepository, optimizationRunRepository,
                coachProfileRepository, coachAssignmentRepository);
    }

    @Test
    void moveReportsSizesSpreadScoreDeltaAndHardBreakFlag() {
        ExplanationTestFixture fx = newFixture();
        List<String> blocks = fx.addTimeSlotWithBlocks("Torsdag 18.00-19.30", 2);
        String groupY = fx.addGroup("Grupp Y", 1, 1, 5, 5, blocks.get(0));
        String groupB = fx.addGroup("Grupp B", 2, 1, 12, 12, blocks.get(1));

        String kalle = fx.addParticipant("Kalle", "Karlsson", 640.0, 3);
        fx.place(kalle, groupY);

        // Grupp B: 11 players with a wide level spread so the SAD-based spread visibly changes.
        int[] levels = {600, 610, 620, 630, 640, 650, 660, 670, 680, 690, 900};
        for (int lvl : levels) {
            String filler = fx.addParticipant("B" + lvl, "Fillersson", (double) lvl, 3);
            fx.place(filler, groupB);
        }

        String runId = fx.insertFinishedRun();

        WhatIfMoveResponse response = whatIfService.move(fx.planId, runId, kalle, groupB);

        assertThat(response.groupSizeChanges()).hasSize(2);
        assertThat(response.groupSizeChanges())
                .anyMatch(c -> c.groupId().equals(groupY) && c.from() == 1 && c.to() == 0);
        assertThat(response.groupSizeChanges())
                .anyMatch(c -> c.groupId().equals(groupB) && c.from() == 11 && c.to() == 12 && c.max() == 12);

        assertThat(response.levelSpreadChanges()).hasSize(2);
        assertThat(response.levelSpreadChanges()).anyMatch(c -> c.groupId().equals(groupB) && c.from() != c.to());

        assertThat(response.scoreDelta()).isNotNull();
        assertThat(response.wouldBreakHard()).isFalse(); // Grupp B has room (11 < 12 max)
        assertThat(response.suggestedActions()).containsExactly("KEEP", "MOVE_ANYWAY", "LOCK_AND_RESOLVE");
        assertThat(response.runId()).isEqualTo(runId);
    }

    @Test
    void moveThatOverflowsMaxSizeReportsHardBreak() {
        ExplanationTestFixture fx = newFixture();
        List<String> blocks = fx.addTimeSlotWithBlocks("Torsdag 18.00-19.30", 2);
        String groupY = fx.addGroup("Grupp Y", 1, 1, 5, 5, blocks.get(0));
        String groupC = fx.addGroup("Grupp C", 2, 1, 2, 2, blocks.get(1));

        String kalle = fx.addParticipant("Kalle", "Karlsson", 640.0, 3);
        fx.place(kalle, groupY);
        String a = fx.addParticipant("A", "A", 600.0, 3);
        String b = fx.addParticipant("B", "B", 610.0, 3);
        fx.place(a, groupC);
        fx.place(b, groupC);

        String runId = fx.insertFinishedRun();
        WhatIfMoveResponse response = whatIfService.move(fx.planId, runId, kalle, groupC);

        assertThat(response.wouldBreakHard()).isTrue();
        assertThat(response.scoreDelta().hard()).isLessThan(0);
        assertThat(response.newlyBroken()).anyMatch(m -> m.key().equals("groupMaxSizeHard"));
    }

    @Test
    void whyNotAnswersForAnArbitraryGroupNotJustTheAutomaticCandidateSet() {
        ExplanationTestFixture fx = newFixture();
        List<String> blocks = fx.addTimeSlotWithBlocks("Torsdag 18.00-19.30", 3);
        String groupY = fx.addGroup("Grupp Y", 1, 1, 5, 5, blocks.get(0));
        String groupD = fx.addGroup("Grupp D", 3, 1, 5, 5, blocks.get(2));

        String kalle = fx.addParticipant("Kalle", "Karlsson", 640.0, 3);
        fx.place(kalle, groupY);
        // Grupp D has no relationship to Kalle at all (no friend/coach wish, not his previous group) -
        // it would never appear in the automatic candidate-set alternatives[] list, but why-not must
        // still answer for it (design §11.5).
        String runId = fx.insertFinishedRun();

        WhatIfWhyNotResponse response = whatIfService.whyNot(fx.planId, runId, kalle, groupD);

        assertThat(response.alternative()).isNotNull();
        assertThat(response.alternative().groupId()).isEqualTo(groupD);
        assertThat(response.alternative().verdict()).isIn("BETTER", "NEUTRAL", "WORSE", "WOULD_BREAK_HARD");
        assertThat(response.runId()).isEqualTo(runId);
    }
}
