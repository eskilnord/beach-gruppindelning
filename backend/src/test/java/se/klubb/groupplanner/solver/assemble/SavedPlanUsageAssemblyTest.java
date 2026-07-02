package se.klubb.groupplanner.solver.assemble;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import se.klubb.groupplanner.domain.CoachProfile;
import se.klubb.groupplanner.domain.ParticipantProfile;
import se.klubb.groupplanner.domain.SavedPlan;
import se.klubb.groupplanner.domain.TrainingBlock;
import se.klubb.groupplanner.domain.TrainingGroup;
import se.klubb.groupplanner.level.LevelService;
import se.klubb.groupplanner.repo.ActivityPlanRepository;
import se.klubb.groupplanner.repo.CoachAssignmentRepository;
import se.klubb.groupplanner.repo.CoachProfileRepository;
import se.klubb.groupplanner.repo.CoachTimeSlotRepository;
import se.klubb.groupplanner.repo.CustomFieldValueRepository;
import se.klubb.groupplanner.repo.FieldDefinitionRepository;
import se.klubb.groupplanner.repo.ParticipantProfileRepository;
import se.klubb.groupplanner.repo.PersonRepository;
import se.klubb.groupplanner.repo.PlayerAssignmentRepository;
import se.klubb.groupplanner.repo.SavedPlanRepository;
import se.klubb.groupplanner.repo.SavedPlanResourceUsageRepository;
import se.klubb.groupplanner.repo.SeasonPlanRepository;
import se.klubb.groupplanner.repo.TimeSlotRepository;
import se.klubb.groupplanner.repo.TrainingBlockRepository;
import se.klubb.groupplanner.repo.TrainingGroupRepository;
import se.klubb.groupplanner.resources.TrainingBlockGenerationService;
import se.klubb.groupplanner.savedplan.SavedPlanService;
import se.klubb.groupplanner.solver.domain.GroupPlanSolution;
import se.klubb.groupplanner.solver.domain.SavedPlanResourceUsage;
import se.klubb.groupplanner.solver.domain.UsageType;
import se.klubb.groupplanner.solver.regression.TestDatasetLoader;

/**
 * Wires the REAL assembly path (docs/design/04-solver.md §6.2) for cross-plan blocking: two plans
 * in the SAME season, one materialized+LOCKED via {@link SavedPlanService}, the other assembled via
 * {@link SolverInputAssembler} with blocking enabled — asserts the resulting {@link
 * GroupPlanSolution#getSavedPlanResourceUsages()} actually contains facts sourced from the locked
 * plan's persisted assignments (not hand-built, unlike {@code solver.CrossPlanBlockingTest}, which
 * exercises the constraint/solver behavior against synthetic facts).
 */
@SpringBootTest
class SavedPlanUsageAssemblyTest {

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
    private CoachProfileRepository coachProfileRepository;
    @Autowired
    private CoachTimeSlotRepository coachTimeSlotRepository;
    @Autowired
    private TimeSlotRepository timeSlotRepository;
    @Autowired
    private TrainingBlockGenerationService trainingBlockGenerationService;
    @Autowired
    private FieldDefinitionRepository fieldDefinitionRepository;
    @Autowired
    private CustomFieldValueRepository customFieldValueRepository;
    @Autowired
    private LevelService levelService;
    @Autowired
    private GroupGenerator groupGenerator;
    @Autowired
    private SolverInputAssembler assembler;
    @Autowired
    private SavedPlanService savedPlanService;
    @Autowired
    private SavedPlanRepository savedPlanRepository;
    @Autowired
    private SavedPlanResourceUsageRepository savedPlanResourceUsageRepository;
    @Autowired
    private TrainingGroupRepository trainingGroupRepository;
    @Autowired
    private TrainingBlockRepository trainingBlockRepository;
    @Autowired
    private CoachAssignmentRepository coachAssignmentRepository;

    private String loadPlan() {
        return loadPlan(null);
    }

    /** {@code seasonPlanId == null} creates a fresh season; otherwise the plan joins an EXISTING
     * season (needed for cross-plan-in-the-same-season tests — {@code activity_plan.season_plan_id}
     * is set once at creation and never changes afterward, see {@code TestDatasetLoader}'s javadoc,
     * so two plans in the same season must both be LOADED that way, not "moved" after the fact). */
    private String loadPlan(String seasonPlanId) {
        TestDatasetLoader loader = new TestDatasetLoader(
                seasonPlanRepository, activityPlanRepository, personRepository, participantProfileRepository,
                playerAssignmentRepository, coachProfileRepository, coachTimeSlotRepository, timeSlotRepository,
                trainingBlockGenerationService, fieldDefinitionRepository, customFieldValueRepository, levelService,
                groupGenerator);
        return loader.load("small-10", seasonPlanId);
    }

    /**
     * {@code TestDatasetLoader.load} deliberately stops right before solving (matching what real
     * import produces) — {@code saved_plan_resource_usage} rows only make sense for a plan that
     * actually has assignments to materialize, so this helper does a minimal deterministic
     * round-robin schedule/group/coach assignment directly via repositories (a real solve is
     * unnecessary for testing the ASSEMBLY WIRING, which is this test's only concern).
     */
    private String loadAndSchedulePlan() {
        String planId = loadPlan();
        List<TrainingGroup> groups = trainingGroupRepository.findByActivityPlanId(planId);
        List<TrainingBlock> blocks = trainingBlockRepository.findByActivityPlanId(planId);
        for (int i = 0; i < groups.size(); i++) {
            trainingGroupRepository.lockToBlock(groups.get(i).id(), blocks.get(i % blocks.size()).id());
        }
        List<ParticipantProfile> participants = participantProfileRepository.findByActivityPlanId(planId);
        for (int i = 0; i < participants.size(); i++) {
            playerAssignmentRepository.lockToGroup(participants.get(i).id(), groups.get(i % groups.size()).id());
        }
        List<CoachProfile> coaches = coachProfileRepository.findByActivityPlanId(planId);
        for (int i = 0; i < coaches.size(); i++) {
            coachAssignmentRepository.lockToGroup(coaches.get(i).id(), groups.get(i % groups.size()).id());
        }
        return planId;
    }

    private String seasonOf(String planId) {
        return activityPlanRepository.findById(planId).orElseThrow().seasonPlanId();
    }

    /** Every {@code TestDatasetLoader.load} call creates brand-new {@code person} rows (fresh
     * UUIDs) — two independently-loaded datasets share no people by construction. PERSON blocking
     * only has anything to say about a person who is ALSO present in the plan being assembled (the
     * Anna example's whole premise), so tests that check for PERSON facts add one participant to
     * {@code toPlanId} who reuses an EXISTING person from {@code fromPlanId} (the same "shared
     * person across two plans" shape spec §13.2 describes). */
    private void addSharedPersonParticipant(String fromPlanId, String toPlanId) {
        String sharedPersonId = participantProfileRepository.findByActivityPlanId(fromPlanId).get(0).personId();
        participantProfileRepository.insert(new ParticipantProfile(
                se.klubb.groupplanner.util.Uuid7.generate(), sharedPersonId, toPlanId,
                null, null, null, null, 500.0, 1.0, null, null, null, false, false));
    }

    @Test
    void lockedOtherPlanInTheSameSeasonContributesSavedPlanResourceUsageFacts() {
        String herrPlanId = loadAndSchedulePlan();
        String damPlanId = loadPlan(seasonOf(herrPlanId));
        addSharedPersonParticipant(herrPlanId, damPlanId);

        SavedPlan locked = savedPlanService.materialize(herrPlanId, "Herr torsdag", SavedPlan.STATUS_LOCKED);
        assertThat(savedPlanResourceUsageRepository.findBySavedPlanId(locked.id())).isNotEmpty();

        GroupPlanSolution blockedEverything = assembler.assemble(
                damPlanId, OptimizeSelection.ALL, new BlockingOptions(true, true, true, false)).solution();
        assertThat(blockedEverything.getSavedPlanResourceUsages()).isNotEmpty();
        assertThat(blockedEverything.getSavedPlanResourceUsages())
                .allSatisfy(u -> assertThat(u.sourcePlanName()).isEqualTo("Herr torsdag"));
        // PERSON facts require a person shared between the two plans (added above); COURT facts
        // don't (small-10's two training blocks share ONE physical "Bana 1" court by fixture design
        // - courts are club-wide, per TrainingBlockGenerationService - so they always match).
        assertThat(blockedEverything.getSavedPlanResourceUsages())
                .anyMatch(u -> u.type() == UsageType.PERSON);
        assertThat(blockedEverything.getSavedPlanResourceUsages())
                .anyMatch(u -> u.type() == UsageType.COURT);
    }

    @Test
    void noBlockingCheckboxesMeansNoSavedPlanResourceUsageFactsEvenWithALockedPlanPresent() {
        String herrPlanId = loadAndSchedulePlan();
        String damPlanId = loadPlan(seasonOf(herrPlanId));
        savedPlanService.materialize(herrPlanId, "Herr torsdag", SavedPlan.STATUS_LOCKED);

        GroupPlanSolution noBlocking = assembler.assemble(damPlanId).solution(); // defaults: BlockingOptions.NONE
        assertThat(noBlocking.getSavedPlanResourceUsages()).isEmpty();
    }

    @Test
    void aSavedPlanNotYetLockedNeverBlocksAnything() {
        String herrPlanId = loadAndSchedulePlan();
        String damPlanId = loadPlan(seasonOf(herrPlanId));
        savedPlanService.materialize(herrPlanId, "Herr torsdag", SavedPlan.STATUS_SAVED); // NOT locked.

        GroupPlanSolution solution = assembler.assemble(
                damPlanId, OptimizeSelection.ALL, new BlockingOptions(true, true, true, false)).solution();
        assertThat(solution.getSavedPlanResourceUsages()).isEmpty();
    }

    @Test
    void blockingChecksOnlyApplyTheCheckedCategories() {
        String herrPlanId = loadAndSchedulePlan();
        String damPlanId = loadPlan(seasonOf(herrPlanId));
        savedPlanService.materialize(herrPlanId, "Herr torsdag", SavedPlan.STATUS_LOCKED);

        // Only blockCourts checked - no PERSON facts should appear, only COURT ones.
        GroupPlanSolution courtsOnly = assembler.assemble(
                damPlanId, OptimizeSelection.ALL, new BlockingOptions(false, false, true, false)).solution();
        assertThat(courtsOnly.getSavedPlanResourceUsages()).isNotEmpty();
        assertThat(courtsOnly.getSavedPlanResourceUsages()).allMatch(u -> u.type() == UsageType.COURT);
    }

    /**
     * M8 review fix, finding 2: since M8 every save is a NEW versioned {@code saved_plan} row, so
     * "lock v1, re-save, lock v2" leaves TWO locked snapshots of the same unchanged plan — the
     * assembly must still produce exactly ONE {@link
     * se.klubb.groupplanner.solver.domain.SavedPlanResourceUsage} fact per real (resource, time)
     * commitment, not one per snapshot (a duplicate fact doubles the hard-score magnitude of a
     * single genuine clash and duplicates its explanation justifications).
     */
    @Test
    void twoLockedSnapshotsOfTheSamePlanProduceNoDuplicateBlockingFacts() {
        String herrPlanId = loadAndSchedulePlan();
        String damPlanId = loadPlan(seasonOf(herrPlanId));
        addSharedPersonParticipant(herrPlanId, damPlanId);

        savedPlanService.materialize(herrPlanId, "Herr torsdag v1", SavedPlan.STATUS_LOCKED);
        GroupPlanSolution afterOneLock = assembler.assemble(
                damPlanId, OptimizeSelection.ALL, new BlockingOptions(true, true, true, false)).solution();
        assertThat(afterOneLock.getSavedPlanResourceUsages()).isNotEmpty();

        // Same plan, unchanged assignments, locked AGAIN as a second snapshot.
        savedPlanService.materialize(herrPlanId, "Herr torsdag v2", SavedPlan.STATUS_LOCKED);
        GroupPlanSolution afterTwoLocks = assembler.assemble(
                damPlanId, OptimizeSelection.ALL, new BlockingOptions(true, true, true, false)).solution();

        // Exactly the same fact set as with one snapshot - and provably duplicate-free by key.
        assertThat(afterTwoLocks.getSavedPlanResourceUsages())
                .as("a second locked snapshot of the SAME state must not add duplicate facts")
                .hasSize(afterOneLock.getSavedPlanResourceUsages().size());
        long distinctKeys = afterTwoLocks.getSavedPlanResourceUsages().stream()
                .map(u -> u.type().name() + "|" + u.personId() + "|" + u.courtId() + "|" + u.timeKey())
                .distinct()
                .count();
        assertThat(distinctKeys).isEqualTo(afterTwoLocks.getSavedPlanResourceUsages().size());
    }
}
