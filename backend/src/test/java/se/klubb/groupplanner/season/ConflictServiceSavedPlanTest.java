package se.klubb.groupplanner.season;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import se.klubb.groupplanner.domain.ParticipantProfile;
import se.klubb.groupplanner.domain.SavedPlan;
import se.klubb.groupplanner.domain.SeasonPlan;
import se.klubb.groupplanner.domain.TrainingBlock;
import se.klubb.groupplanner.domain.TrainingGroup;
import se.klubb.groupplanner.level.LevelService;
import se.klubb.groupplanner.repo.ActivityPlanRepository;
import se.klubb.groupplanner.repo.CoachProfileRepository;
import se.klubb.groupplanner.repo.CoachTimeSlotRepository;
import se.klubb.groupplanner.repo.CustomFieldValueRepository;
import se.klubb.groupplanner.repo.FieldDefinitionRepository;
import se.klubb.groupplanner.repo.ParticipantProfileRepository;
import se.klubb.groupplanner.repo.PersonRepository;
import se.klubb.groupplanner.repo.PlayerAssignmentRepository;
import se.klubb.groupplanner.repo.SeasonPlanRepository;
import se.klubb.groupplanner.repo.TimeSlotRepository;
import se.klubb.groupplanner.repo.TrainingBlockRepository;
import se.klubb.groupplanner.repo.TrainingGroupRepository;
import se.klubb.groupplanner.resources.TrainingBlockGenerationService;
import se.klubb.groupplanner.savedplan.SavedPlanService;
import se.klubb.groupplanner.solver.assemble.GroupGenerator;
import se.klubb.groupplanner.solver.regression.TestDatasetLoader;
import se.klubb.groupplanner.util.Uuid7;

/**
 * M8 task item 1: "ConflictService reads ALL statuses ... season view should show conflicts between
 * saved plans AND live plan state per spec §19.2" — verifies {@code ConflictService} now actually
 * reads {@code saved_plan}/{@code saved_plan_resource_usage} (it previously read ONLY live current
 * assignments, per backend/docs/m6b-notes.md's own javadoc at the time). Complements {@code
 * ConflictServiceTest}'s pre-existing pure-live-state coverage (still green, unchanged - no
 * saved_plan rows exist in those fixtures, so the fallback-to-live path is exercised there).
 */
@SpringBootTest
class ConflictServiceSavedPlanTest {

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
    private TrainingGroupRepository trainingGroupRepository;
    @Autowired
    private TrainingBlockRepository trainingBlockRepository;
    @Autowired
    private ConflictService conflictService;
    @Autowired
    private SavedPlanService savedPlanService;

    private String loadPlan(String seasonPlanId) {
        TestDatasetLoader loader = new TestDatasetLoader(
                seasonPlanRepository, activityPlanRepository, personRepository, participantProfileRepository,
                playerAssignmentRepository, coachProfileRepository, coachTimeSlotRepository, timeSlotRepository,
                trainingBlockGenerationService, fieldDefinitionRepository, customFieldValueRepository, levelService,
                groupGenerator);
        return loader.load("small-10", seasonPlanId);
    }

    private void scheduleEvenly(String planId) {
        List<TrainingGroup> groups = trainingGroupRepository.findByActivityPlanId(planId);
        List<TrainingBlock> blocks = trainingBlockRepository.findByActivityPlanId(planId);
        for (int i = 0; i < groups.size(); i++) {
            trainingGroupRepository.lockToBlock(groups.get(i).id(), blocks.get(i % blocks.size()).id());
        }
        List<ParticipantProfile> participants = participantProfileRepository.findByActivityPlanId(planId);
        for (int i = 0; i < participants.size(); i++) {
            playerAssignmentRepository.lockToGroup(participants.get(i).id(), groups.get(i % groups.size()).id());
        }
    }

    private String firstGroupId(String planId) {
        return trainingGroupRepository.findByActivityPlanId(planId).get(0).id();
    }

    private String seasonId() {
        return seasonPlanRepository
                .insert(new SeasonPlan(Uuid7.generate(), "VT26", null, null, "active", java.time.Instant.now(), java.time.Instant.now()))
                .id();
    }

    /** A locked snapshot's FROZEN usage stays authoritative even after the underlying live plan
     * diverges (moved away from the overlap) - the conflict report must still catch the overlap the
     * officially-recorded/locked plan actually has, not silently miss it because someone kept
     * editing the live plan after locking it. */
    @Test
    void lockedSnapshotStaysAuthoritativeAfterTheLivePlanDiverges() {
        String season = seasonId();
        String herrPlanId = loadPlan(season);
        String damPlanId = loadPlan(season);
        scheduleEvenly(herrPlanId);
        scheduleEvenly(damPlanId);

        String kallePersonId = participantProfileRepository.findByActivityPlanId(herrPlanId).get(0).personId();
        ParticipantProfile kalleInDam = participantProfileRepository.insert(new ParticipantProfile(
                Uuid7.generate(), kallePersonId, damPlanId, null, null, null, null, 500.0, 1.0, null, null, null,
                false, false));
        playerAssignmentRepository.lockToGroup(kalleInDam.id(), firstGroupId(damPlanId));

        // Lock Herr while Kalle is still on group 0 (overlaps Dam's group 0 - same round-robin
        // first block, per ConflictServiceTest's own established fixture shape).
        savedPlanService.materialize(herrPlanId, "Herr torsdag", SavedPlan.STATUS_LOCKED);

        // Diverge the LIVE Herr plan: move Kalle to group 1's block instead (no longer overlapping
        // Dam's group 0). Nothing in this codebase prevents further live edits after a save/lock
        // (spec's own "en låst plan" example only talks about it BLOCKING OTHER plans, not freezing
        // the source plan itself) - this is exactly the drift scenario the task calls out.
        String kallePid = participantProfileRepository.findByActivityPlanId(herrPlanId).stream()
                .filter(p -> p.personId().equals(kallePersonId)).findFirst().orElseThrow().id();
        playerAssignmentRepository.unlock(kallePid);
        playerAssignmentRepository.lockToGroup(kallePid, trainingGroupRepository.findByActivityPlanId(herrPlanId).get(1).id());

        List<SeasonConflict> conflicts = conflictService.findConflicts(season);

        long personConflicts = conflicts.stream()
                .filter(c -> SeasonConflict.PERSON_DOUBLE_BOOKED.equals(c.type()) && kallePersonId.equals(c.personId()))
                .count();
        assertThat(personConflicts)
                .as("the LOCKED snapshot (Kalle still at the overlapping slot) must be used, not the diverged live state")
                .isEqualTo(1);
    }

    /** A plan is NEVER reported as double-conflicting with itself: picking exactly one usage source
     * per plan (snapshot XOR live, never both) means the pairwise sweep still produces exactly ONE
     * conflict per real-world overlap, not two (one from live-vs-live, one from snapshot-vs-live) -
     * the exact duplicate-conflict failure mode backend/docs/m6b-notes.md already documented once
     * for the court sweep. */
    @Test
    void savedSnapshotSourceNeverDuplicatesTheLiveSourceConflict() {
        String season = seasonId();
        String herrPlanId = loadPlan(season);
        String damPlanId = loadPlan(season);
        scheduleEvenly(herrPlanId);
        scheduleEvenly(damPlanId);

        String kallePersonId = participantProfileRepository.findByActivityPlanId(herrPlanId).get(0).personId();
        ParticipantProfile kalleInDam = participantProfileRepository.insert(new ParticipantProfile(
                Uuid7.generate(), kallePersonId, damPlanId, null, null, null, null, 500.0, 1.0, null, null, null,
                false, false));
        playerAssignmentRepository.lockToGroup(kalleInDam.id(), firstGroupId(damPlanId));

        // Herr is saved (not locked) - still not diverged from its snapshot.
        savedPlanService.materialize(herrPlanId, "Herr torsdag", SavedPlan.STATUS_SAVED);

        List<SeasonConflict> conflicts = conflictService.findConflicts(season);
        long personConflicts = conflicts.stream()
                .filter(c -> SeasonConflict.PERSON_DOUBLE_BOOKED.equals(c.type()) && kallePersonId.equals(c.personId()))
                .count();
        assertThat(personConflicts).isEqualTo(1);
    }

    /** {@code archived} explicitly falls back to live state (class javadoc's own documented
     * decision) - an archived snapshot's stale overlap must NOT keep being reported once the live
     * plan itself has moved past it. */
    @Test
    void archivedSnapshotFallsBackToLiveStateAndStopsReportingAStaleOverlap() {
        String season = seasonId();
        String herrPlanId = loadPlan(season);
        String damPlanId = loadPlan(season);
        scheduleEvenly(herrPlanId);
        scheduleEvenly(damPlanId);

        String kallePersonId = participantProfileRepository.findByActivityPlanId(herrPlanId).get(0).personId();
        ParticipantProfile kalleInDam = participantProfileRepository.insert(new ParticipantProfile(
                Uuid7.generate(), kallePersonId, damPlanId, null, null, null, null, 500.0, 1.0, null, null, null,
                false, false));
        playerAssignmentRepository.lockToGroup(kalleInDam.id(), firstGroupId(damPlanId));

        // Archive Herr while Kalle is still overlapping - this snapshot's usage would report a
        // conflict if it were still consulted.
        savedPlanService.materialize(herrPlanId, "Herr torsdag (arkiverad)", SavedPlan.STATUS_ARCHIVED);

        // Live Herr has since moved Kalle off the overlap.
        String kallePid = participantProfileRepository.findByActivityPlanId(herrPlanId).stream()
                .filter(p -> p.personId().equals(kallePersonId)).findFirst().orElseThrow().id();
        playerAssignmentRepository.unlock(kallePid);
        playerAssignmentRepository.lockToGroup(kallePid, trainingGroupRepository.findByActivityPlanId(herrPlanId).get(1).id());

        List<SeasonConflict> conflicts = conflictService.findConflicts(season);
        assertThat(conflicts)
                .as("archived snapshots must not keep reporting a conflict the live plan has already moved past")
                .noneMatch(c -> SeasonConflict.PERSON_DOUBLE_BOOKED.equals(c.type()) && kallePersonId.equals(c.personId()));
    }

    /**
     * M8 review fix, finding 1 — the exact "lock v1 → save v2 → archive v2" sequence: archiving the
     * NEWEST snapshot must re-expose the older, still-locked v1 (whose frozen usage still blocks
     * other plans' solves), NOT fall back to live state. The pre-fix code filtered {@code archived}
     * AFTER a plain latest-row {@code LIMIT 1}, so archiving v2 made the whole plan fall back to
     * live — and since live had moved past the overlap, the conflict v1 still genuinely embodies
     * silently vanished from the season view.
     */
    @Test
    void archivingTheNewestSnapshotReExposesTheOlderStillLockedOne() throws Exception {
        String season = seasonId();
        String herrPlanId = loadPlan(season);
        String damPlanId = loadPlan(season);
        scheduleEvenly(herrPlanId);
        scheduleEvenly(damPlanId);

        String kallePersonId = participantProfileRepository.findByActivityPlanId(herrPlanId).get(0).personId();
        ParticipantProfile kalleInDam = participantProfileRepository.insert(new ParticipantProfile(
                Uuid7.generate(), kallePersonId, damPlanId, null, null, null, null, 500.0, 1.0, null, null, null,
                false, false));
        playerAssignmentRepository.lockToGroup(kalleInDam.id(), firstGroupId(damPlanId));

        // v1: LOCKED while Kalle still overlaps Dam's group 0.
        savedPlanService.materialize(herrPlanId, "Herr torsdag v1", SavedPlan.STATUS_LOCKED);

        // Live diverges: Kalle moves off the overlap...
        String kallePid = participantProfileRepository.findByActivityPlanId(herrPlanId).stream()
                .filter(p -> p.personId().equals(kallePersonId)).findFirst().orElseThrow().id();
        playerAssignmentRepository.unlock(kallePid);
        playerAssignmentRepository.lockToGroup(kallePid, trainingGroupRepository.findByActivityPlanId(herrPlanId).get(1).id());

        // ...v2 saved from the diverged state, then archived. created_at has second granularity
        // (Instant.toString() carries sub-second, but be explicit): v2 must sort strictly newer than
        // v1 for the LIMIT 1 to have ever picked it, so give the clock a beat.
        Thread.sleep(5);
        SavedPlan v2 = savedPlanService.materialize(herrPlanId, "Herr torsdag v2", SavedPlan.STATUS_SAVED);
        savedPlanService.transitionStatus(herrPlanId, v2.id(), SavedPlan.STATUS_ARCHIVED);

        List<SeasonConflict> conflicts = conflictService.findConflicts(season);
        long personConflicts = conflicts.stream()
                .filter(c -> SeasonConflict.PERSON_DOUBLE_BOOKED.equals(c.type()) && kallePersonId.equals(c.personId()))
                .count();
        assertThat(personConflicts)
                .as("after archiving v2, the still-locked v1 (frozen overlap) must be the effective snapshot")
                .isEqualTo(1);
    }
}
