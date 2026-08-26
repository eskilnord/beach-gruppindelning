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
import se.klubb.groupplanner.explain.ExplanationDtos.PersonExplanationResponse;
import se.klubb.groupplanner.explain.ExplanationDtos.UnmetWishView;
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
 * M-E2 {@code CausalNarrator} LOCKED outcome: a PINNED player's unmet wishes must ALL come back
 * LOCKED (the solver was never allowed to try moving them, per {@code
 * SolverInputAssembler}/{@code @PlanningPin}) — never a fabricated TRADE_OFF/BLOCKED_HARD/etc. claim
 * about candidates the solver could not have considered. Also the pre-existing truthfulness bug fix
 * (M-E2 brief): a pinned player's response carries a top-level {@code lockedNoticeSv}.
 */
@SpringBootTest
class PinnedPlacementTest {

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
    @Autowired
    private JdbcClient jdbcClient;

    @Test
    void everyUnmetWishOfAPinnedPlayerIsLockedWithNoReorderCtaData() {
        ExplanationTestFixture fx = new ExplanationTestFixture(
                seasonPlanRepository, activityPlanRepository, personRepository, participantProfileRepository,
                playerAssignmentRepository, trainingGroupRepository, timeSlotRepository, trainingBlockGenerationService,
                fieldDefinitionRepository, customFieldValueRepository, optimizationRunRepository,
                coachProfileRepository, coachAssignmentRepository);
        List<String> blocks = fx.addTimeSlotWithBlocks("Torsdag 18.00-19.30", 2);
        String groupA = fx.addGroup("Grupp A", 1, 1, 5, 5, blocks.get(0));
        String groupB = fx.addGroup("Grupp B", 2, 1, 5, 5, blocks.get(1));

        String kalle = fx.addParticipant("Kalle", "Karlsson", 500.0, 3);
        fx.place(kalle, groupA);
        fx.lockToGroup(kalle, groupA); // pinned: the solver was never allowed to move Kalle.

        String lisa = fx.addParticipant("Lisa", "Larsson", 510.0, 3);
        fx.place(lisa, groupB);
        fx.wish(kalle, lisa, "playWith"); // WANT_SAME, broken (different groups) -> a FRIEND unmet wish.

        // Also gives Kalle a broken PREVGROUP wish (previous group order 2, currently in group order 1).
        jdbcClient.sql("UPDATE participant_profile SET previous_group_name = 'Grupp 2' WHERE id = :id")
                .param("id", kalle).update();

        String runId = fx.insertFinishedRun();
        PersonExplanationResponse response = explanationService.explainPerson(fx.planId, runId, kalle);

        assertThat(response.selectedGroup()).isNotNull();
        assertThat(response.lockedNoticeSv()).isNotNull();
        assertThat(response.lockedNoticeSv()).contains("Kalle Karlsson").contains("Grupp A").contains("låst");

        assertThat(response.unmetWishes()).isNotEmpty();
        for (UnmetWishView wish : response.unmetWishes()) {
            assertThat(wish.outcome()).as("wish " + wish.wishId()).isEqualTo("LOCKED");
            assertThat(wish.primaryReasonSv()).contains("låst").contains("Lås upp placeringen");
            // No reorder CTA data: nothing claims a specific "best candidate" the solver could have
            // picked, since it was never allowed to try any of them.
            assertThat(wish.bestCandidateGroupId()).isNull();
            assertThat(wish.bestCandidateDelta()).isNull();
            assertThat(wish.competingReasons()).isEmpty();
            // M-E2 review fix (MAJOR, "scope honesty"): LOCKED now also carries the standard hedge
            // sentence (non-null) - it never names a candidate, but the hedge documents the scope of
            // the comparison the alternatives list below it still shows.
            assertThat(wish.hedgeSv()).isEqualTo("Jämförelsen gäller att flytta Kalle Karlsson ensam, med planen i övrigt oförändrad.");
        }
        assertThat(response.unmetWishes()).anyMatch(w -> w.wishId().startsWith("FRIEND:"));
        assertThat(response.unmetWishes()).anyMatch(w -> w.wishId().equals("PREVGROUP"));
    }
}
