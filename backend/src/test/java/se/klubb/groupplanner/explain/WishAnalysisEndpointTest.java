package se.klubb.groupplanner.explain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import ai.timefold.solver.core.api.score.buildin.hardmediumsoftlong.HardMediumSoftLongScore;
import ai.timefold.solver.core.api.solver.SolutionManager;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Proxy;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import se.klubb.groupplanner.api.error.NotFoundException;
import se.klubb.groupplanner.domain.CustomFieldValue;
import se.klubb.groupplanner.explain.ExplanationDtos.WishAnalysisResponse;
import se.klubb.groupplanner.repo.ActivityPlanRepository;
import se.klubb.groupplanner.repo.CoachAssignmentRepository;
import se.klubb.groupplanner.repo.CoachProfileRepository;
import se.klubb.groupplanner.repo.CustomFieldValueRepository;
import se.klubb.groupplanner.repo.ExplanationRecordRepository;
import se.klubb.groupplanner.repo.FieldDefinitionRepository;
import se.klubb.groupplanner.repo.OptimizationRunRepository;
import se.klubb.groupplanner.repo.ParticipantProfileRepository;
import se.klubb.groupplanner.repo.PersonRepository;
import se.klubb.groupplanner.repo.PlayerAssignmentRepository;
import se.klubb.groupplanner.repo.SeasonPlanRepository;
import se.klubb.groupplanner.repo.TimeSlotRepository;
import se.klubb.groupplanner.repo.TrainingGroupRepository;
import se.klubb.groupplanner.resources.TrainingBlockGenerationService;
import se.klubb.groupplanner.solver.assemble.SolverInputAssembler;
import se.klubb.groupplanner.solver.domain.GroupPlanSolution;

/**
 * M-E3 {@code GET .../wish-analysis?wish={wishId}} — staleness envelope, cache-hit probe-count
 * behavior (mirrors {@code ExplanationProbeCountTest}'s counting-{@link Proxy} pattern), 404s for an
 * unknown {@code wishId}, and the non-TRADE_OFF shape (empty {@code breakEven}/{@code orderings} plus
 * the outcome's own honest reason).
 */
@SpringBootTest
class WishAnalysisEndpointTest {

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
    private SolverInputAssembler solverInputAssembler;
    @Autowired
    private SolutionManager<GroupPlanSolution, HardMediumSoftLongScore> realSolutionManager;
    @Autowired
    private ExplanationRecordRepository explanationRecordRepository;
    @Autowired
    private ObjectMapper objectMapper;
    @Autowired
    private ExplanationService explanationService;
    @Autowired
    private JdbcClient jdbcClient;

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

    private String timeSlotIdOfBlock(String blockId) {
        return jdbcClient.sql("SELECT time_slot_id FROM training_block WHERE id = :id").param("id", blockId).query(String.class).single();
    }

    /** The exact TRADE_OFF fixture reused across {@code NoClaimWithoutProbeTest}/{@code
     * CausalNarrativeTruthfulnessTest}: Kalle+Lisa's sameGroupSoft friend wish breaks, Kalle's
     * timePreferenceSoft wish is fixed, moving to Grupp B. */
    private record TradeOffFixture(ExplanationTestFixture fx, String kalle, String runId, int numberOfGroups) {
    }

    private TradeOffFixture buildTradeOffFixture() {
        ExplanationTestFixture fx = newFixture();
        List<String> blocksA = fx.addTimeSlotWithBlocks("Torsdag 18.00-19.30", 1);
        List<String> blocksB = fx.addTimeSlotWithBlocks("Fredag 20.00-21.00", 1);
        String groupA = fx.addGroup("Grupp A", 1, 0, 0, 10, blocksA.get(0));
        fx.addGroup("Grupp B", 2, 0, 0, 10, blocksB.get(0));

        String kalle = fx.addParticipant("Kalle", "Karlsson", 500.0, 3);
        String lisa = fx.addParticipant("Lisa", "Larsson", 500.0, 3);
        fx.place(kalle, groupA);
        fx.place(lisa, groupA);
        fx.wish(kalle, lisa, "playWith");
        setPreferTimes(kalle, timeSlotIdOfBlock(blocksB.get(0)));

        String runId = fx.insertFinishedRun();
        return new TradeOffFixture(fx, kalle, runId, 2);
    }

    @Test
    void envelopeAndShapeForATradeOffWish() {
        TradeOffFixture t = buildTradeOffFixture();
        WishAnalysisResponse response = explanationService.wishAnalysis(t.fx().planId, t.runId(), t.kalle(), "TIME");

        assertThat(response.runId()).isEqualTo(t.runId());
        assertThat(response.stale()).isFalse();
        assertThat(response.wishId()).isEqualTo("TIME");
        assertThat(response.unavailableReasonSv()).isNull();
        assertThat(response.breakEven()).isNotEmpty();
        assertThat(response.breakEven()).extracting(ExplanationDtos.WeightBreakEvenView::key).contains("sameGroupSoft", "timePreferenceSoft");
        assertThat(response.orderings()).hasSize(24);
        assertThat(response.orderings()).anyMatch(ExplanationDtos.OrderingView::nonWorse);
        assertThat(response.orderings()).anyMatch(o -> !o.nonWorse());
        // FIX 2 (M-E3 review, MAJOR): cautionSv is mandatory whenever this payload carries a concrete
        // "this would help" claim - breakEven/orderings are both non-empty here.
        assertThat(response.cautionSv()).isEqualTo(PrioritySensitivityCalculator.CAUTION_SV);
    }

    @Test
    void unknownWishIdIsA404WithASwedishMessage() {
        TradeOffFixture t = buildTradeOffFixture();
        assertThatThrownBy(() -> explanationService.wishAnalysis(t.fx().planId, t.runId(), t.kalle(), "NOT_A_REAL_WISH"))
                .isInstanceOf(NotFoundException.class)
                .hasMessageContaining("Okänt önskemål");
    }

    @Test
    void nonTradeOffWishHasEmptyBreakEvenAndOrderingsWithTheOutcomesOwnReason() {
        ExplanationTestFixture fx = newFixture();
        List<String> blocksA = fx.addTimeSlotWithBlocks("Torsdag 18.00-19.30", 1);
        List<String> blocksB = fx.addTimeSlotWithBlocks("Fredag 20.00-21.00", 1);
        String groupA = fx.addGroup("Grupp A", 1, 0, 0, 10, blocksA.get(0));
        fx.addGroup("Grupp B", 2, 0, 0, 10, blocksB.get(0));

        String kalle = fx.addParticipant("Kalle", "Karlsson", 500.0, 3);
        fx.place(kalle, groupA);
        setPreferTimes(kalle, timeSlotIdOfBlock(blocksB.get(0))); // nothing else changes -> SOLVER_MISS.
        String runId = fx.insertFinishedRun();

        WishAnalysisResponse response = explanationService.wishAnalysis(fx.planId, runId, kalle, "TIME");

        assertThat(response.breakEven()).isEmpty();
        assertThat(response.orderings()).isEmpty();
        assertThat(response.unavailableReasonSv())
                .isEqualTo("Flytten skulle redan förbättra planen – prioritetsordningen är inte det som håller emot här.");
        // FIX 2 (M-E3 review, MAJOR): cautionSv is null exactly when there is nothing here to scope a
        // caution onto - both breakEven and orderings are empty for this non-TRADE_OFF outcome.
        assertThat(response.cautionSv()).isNull();
    }

    /** Mirrors {@code ExplanationProbeCountTest}'s counting-{@link Proxy} pattern: a first call probes
     * exactly {@code groups+1} times (same budget as {@code explainPerson}); a second call for the SAME
     * (runId, planRevision, participantId, wishId) must hit the {@link WishAnalysisCache} and probe
     * ZERO additional times. */
    @SuppressWarnings("unchecked")
    @Test
    void secondCallForTheSameWishHitsTheCacheAndProbesNoMoreTimes() {
        TradeOffFixture t = buildTradeOffFixture();

        AtomicInteger analyzeCalls = new AtomicInteger();
        InvocationHandler handler = (proxy, method, args) -> {
            if ("analyze".equals(method.getName())) {
                analyzeCalls.incrementAndGet();
            }
            return method.invoke(realSolutionManager, args);
        };
        SolutionManager<GroupPlanSolution, HardMediumSoftLongScore> countingSolutionManager =
                (SolutionManager<GroupPlanSolution, HardMediumSoftLongScore>) Proxy.newProxyInstance(
                        SolutionManager.class.getClassLoader(), new Class<?>[] {SolutionManager.class}, handler);

        MoveProbe countingProbe = new MoveProbe(countingSolutionManager);
        WishAnalysisCache wishAnalysisCache = new WishAnalysisCache();
        ExplanationService svc = new ExplanationService(
                activityPlanRepository, optimizationRunRepository, participantProfileRepository, trainingGroupRepository,
                coachAssignmentRepository, coachProfileRepository, personRepository, solverInputAssembler,
                countingSolutionManager, countingProbe, new ExplanationCache(), wishAnalysisCache, explanationRecordRepository,
                objectMapper);

        WishAnalysisResponse first = svc.wishAnalysis(t.fx().planId, t.runId(), t.kalle(), "TIME");
        int afterFirst = analyzeCalls.get();
        assertThat(afterFirst).isEqualTo(t.numberOfGroups() + 1);

        WishAnalysisResponse second = svc.wishAnalysis(t.fx().planId, t.runId(), t.kalle(), "TIME");
        assertThat(analyzeCalls.get()).as("cache hit must not probe again").isEqualTo(afterFirst);
        assertThat(second).isEqualTo(first);
    }
}
