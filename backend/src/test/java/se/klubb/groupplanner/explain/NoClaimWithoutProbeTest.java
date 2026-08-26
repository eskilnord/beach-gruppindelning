package se.klubb.groupplanner.explain;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import se.klubb.groupplanner.domain.CustomFieldValue;
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
 * M-E2 structural null-safety contract (independent of exact wording, unlike {@code
 * CausalNarrativeTruthfulnessTest}): {@code TRADE_OFF}/{@code EQUAL}/{@code SOLVER_MISS} — the three
 * outcomes that name a specific "best candidate" at all — must carry a non-null {@code
 * bestCandidateGroupId}/{@code bestCandidateDelta}; every other outcome must carry NEITHER, and must
 * never fabricate {@code competingReasons}. {@code prioritySensitivity.available()} is always
 * {@code false} for M-E2 (E3 fills it in), with every other sensitivity field {@code null}.
 */
@SpringBootTest
class NoClaimWithoutProbeTest {

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

    private static final Set<String> NAMES_A_CANDIDATE = Set.of("TRADE_OFF", "EQUAL", "SOLVER_MISS");
    private static final Set<String> NAMES_NO_CANDIDATE = Set.of("LOCKED", "NO_CANDIDATE", "BLOCKED_HARD", "INCONCLUSIVE");

    private ExplanationTestFixture newFixture() {
        return new ExplanationTestFixture(
                seasonPlanRepository, activityPlanRepository, personRepository, participantProfileRepository,
                playerAssignmentRepository, trainingGroupRepository, timeSlotRepository, trainingBlockGenerationService,
                fieldDefinitionRepository, customFieldValueRepository, optimizationRunRepository,
                coachProfileRepository, coachAssignmentRepository);
    }

    private void setPreferTimes(String participantId, String timeSlotId) {
        String fieldId = fieldDefinitionRepository.findGlobalByKey("preferTimes").orElseThrow().id();
        customFieldValueRepository.upsert(fieldId, CustomFieldValue.ENTITY_TYPE_PARTICIPANT, participantId, "[\"" + timeSlotId + "\"]");
    }

    private void assertContract(UnmetWishView wish) {
        assertThat(wish.prioritySensitivity().available()).as("wish %s sensitivity.available", wish.wishId()).isFalse();
        assertThat(wish.prioritySensitivity().unavailableReasonSv()).isEqualTo("Beräknas i ett senare steg.");
        assertThat(wish.prioritySensitivity().wouldChangeAtRankSv()).as("wish %s wouldChangeAtRankSv", wish.wishId()).isNull();
        assertThat(wish.prioritySensitivity().newRank()).as("wish %s newRank", wish.wishId()).isNull();

        if (NAMES_A_CANDIDATE.contains(wish.outcome())) {
            assertThat(wish.bestCandidateGroupId()).as(wish.outcome() + " bestCandidateGroupId").isNotNull();
            assertThat(wish.bestCandidateDelta()).as(wish.outcome() + " bestCandidateDelta").isNotNull();
        }
        if (NAMES_NO_CANDIDATE.contains(wish.outcome())) {
            assertThat(wish.bestCandidateGroupId()).as(wish.outcome() + " bestCandidateGroupId must stay null").isNull();
            assertThat(wish.bestCandidateDelta()).as(wish.outcome() + " bestCandidateDelta must stay null").isNull();
            assertThat(wish.competingReasons()).as(wish.outcome() + " competingReasons must never be fabricated").isEmpty();
        }
        if (!"TRADE_OFF".equals(wish.outcome())) {
            assertThat(wish.competingReasons()).as(wish.outcome() + " competingReasons only ever populated for TRADE_OFF").isEmpty();
        }
    }

    @Test
    void blockedHardLockedAndNoCandidateNeverNameABestCandidateOrFabricateCompetingReasons() {
        ExplanationTestFixture fx = newFixture();
        List<String> blocksA = fx.addTimeSlotWithBlocks("Torsdag 18.00-19.30", 1);
        List<String> blocksC = fx.addTimeSlotWithBlocks("Fredag 20.00-21.00", 1);
        String groupA = fx.addGroup("Grupp A", 1, 0, 5, 10, blocksA.get(0));
        String groupC = fx.addGroup("Grupp C", 2, 1, 1, 1, blocksC.get(0)); // full.

        String kalle = fx.addParticipant("Kalle", "Karlsson", 500.0, 3);
        fx.place(kalle, groupA);
        fx.lockToGroup(kalle, groupA);

        String erik = fx.addParticipant("Erik", "Eriksson", 500.0, 3);
        fx.place(erik, groupC);
        fx.wish(kalle, erik, "playWith"); // WANT_SAME, broken -> LOCKED (pinned trumps BLOCKED_HARD).

        String runId = fx.insertFinishedRun();
        PersonExplanationResponse response = explanationService.explainPerson(fx.planId, runId, kalle);
        assertThat(response.unmetWishes()).isNotEmpty();
        for (UnmetWishView wish : response.unmetWishes()) {
            assertThat(wish.outcome()).isEqualTo("LOCKED");
            assertContract(wish);
        }
    }

    @Test
    void tradeOffEqualAndSolverMissAlwaysNameANonNullBestCandidate() {
        ExplanationTestFixture fx = newFixture();
        List<String> blocksA = fx.addTimeSlotWithBlocks("Torsdag 18.00-19.30", 1);
        List<String> blocksB = fx.addTimeSlotWithBlocks("Fredag 20.00-21.00", 1);
        String groupA = fx.addGroup("Grupp A", 1, 0, 0, 10, blocksA.get(0));
        String groupB = fx.addGroup("Grupp B", 2, 0, 0, 10, blocksB.get(0));

        String kalle = fx.addParticipant("Kalle", "Karlsson", 500.0, 3);
        fx.place(kalle, groupA);
        setPreferTimes(kalle, timeSlotIdOfBlock(blocksB.get(0))); // TIME wish, nothing else changes -> SOLVER_MISS.

        String runId = fx.insertFinishedRun();
        PersonExplanationResponse response = explanationService.explainPerson(fx.planId, runId, kalle);
        UnmetWishView time = response.unmetWishes().stream().filter(w -> w.wishId().equals("TIME")).findFirst().orElseThrow();
        assertThat(time.outcome()).isEqualTo("SOLVER_MISS");
        assertContract(time);
    }

    @Test
    void sensitivityNullSafetyContractHoldsAcrossEveryOutcomeSeenInASingleResponse() {
        // Kalle has TWO unmet wishes (a broken friend wish and a broken time wish) whose sole
        // candidate is the SAME full group - both resolve to BLOCKED_HARD independently, exercising
        // the null-safety contract across more than one UnmetWishView in a single response.
        ExplanationTestFixture fx = newFixture();
        List<String> blocksA = fx.addTimeSlotWithBlocks("Torsdag 18.00-19.30", 1);
        List<String> blocksB = fx.addTimeSlotWithBlocks("Fredag 20.00-21.00", 1);
        String groupA = fx.addGroup("Grupp A", 1, 0, 0, 10, blocksA.get(0));
        String groupB = fx.addGroup("Grupp B", 2, 1, 1, 1, blocksB.get(0)); // full once Lisa is placed there.

        String kalle = fx.addParticipant("Kalle", "Karlsson", 500.0, 3);
        String lisa = fx.addParticipant("Lisa", "Larsson", 500.0, 3);
        fx.place(kalle, groupA);
        fx.place(lisa, groupB); // Grupp B is now 1/1 - full.
        fx.wish(kalle, lisa, "playWith"); // WANT_SAME, broken, only candidate (Grupp B) is full -> BLOCKED_HARD.
        setPreferTimes(kalle, timeSlotIdOfBlock(blocksB.get(0))); // TIME wish - Grupp B is ALSO its only candidate.

        String runId = fx.insertFinishedRun();
        PersonExplanationResponse response = explanationService.explainPerson(fx.planId, runId, kalle);
        assertThat(response.unmetWishes()).hasSizeGreaterThanOrEqualTo(2);
        assertThat(response.unmetWishes()).extracting(UnmetWishView::outcome).contains("BLOCKED_HARD");
        for (UnmetWishView wish : response.unmetWishes()) {
            assertContract(wish);
        }
    }

    private String timeSlotIdOfBlock(String blockId) {
        return jdbcClient.sql("SELECT time_slot_id FROM training_block WHERE id = :id").param("id", blockId).query(String.class).single();
    }
}
