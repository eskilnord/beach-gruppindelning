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
import se.klubb.groupplanner.domain.CoachProfile;
import se.klubb.groupplanner.domain.ParticipantProfile;
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
import se.klubb.groupplanner.repo.SeasonPlanRepository;
import se.klubb.groupplanner.repo.TimeSlotRepository;
import se.klubb.groupplanner.repo.TrainingBlockRepository;
import se.klubb.groupplanner.repo.TrainingGroupRepository;
import se.klubb.groupplanner.resources.TrainingBlockGenerationService;
import se.klubb.groupplanner.solver.assemble.GroupGenerator;
import se.klubb.groupplanner.solver.regression.TestDatasetLoader;
import se.klubb.groupplanner.util.Uuid7;

/**
 * Season-level conflict detection (docs/design/04-solver.md §6.3, spec §19.2). Uses two
 * independently-scheduled plans in the SAME season (never solved by Timefold — a direct
 * round-robin schedule, matching {@code solver.assemble.SavedPlanUsageAssemblyTest}'s approach,
 * since only {@code ConflictService}'s reporting logic is under test here) and a person shared
 * between them at an OVERLAPPING time, per the milestone's "cross-plan person double-booking" gate.
 */
@SpringBootTest
class ConflictServiceTest {

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
    private CoachAssignmentRepository coachAssignmentRepository;
    @Autowired
    private ConflictService conflictService;

    private String loadPlan(String seasonPlanId) {
        TestDatasetLoader loader = new TestDatasetLoader(
                seasonPlanRepository, activityPlanRepository, personRepository, participantProfileRepository,
                playerAssignmentRepository, coachProfileRepository, coachTimeSlotRepository, timeSlotRepository,
                trainingBlockGenerationService, fieldDefinitionRepository, customFieldValueRepository, levelService,
                groupGenerator);
        return loader.load("small-10", seasonPlanId);
    }

    /** Deterministic round-robin schedule/group assignment (no solver run needed for reporting-only
     * ConflictService coverage) - group 0 always ends up on the FIRST training block. */
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

    @Test
    void twoPlansInTheSameSeasonWithASharedPersonInOverlappingGroupsReportPersonDoubleBooked() {
        String seasonId = seasonPlanRepository
                .insert(new se.klubb.groupplanner.domain.SeasonPlan(Uuid7.generate(), "VT26", null, null, "active",
                        java.time.Instant.now(), java.time.Instant.now()))
                .id();
        String herrPlanId = loadPlan(seasonId);
        String damPlanId = loadPlan(seasonId);
        scheduleEvenly(herrPlanId);
        scheduleEvenly(damPlanId);

        // Kalle plays in Herr's FIRST group AND is added to Dam's FIRST group too - both groups
        // are on that plan's own first training block (round-robin), which per small-10's fixture
        // (both plans use the identical timeslots.csv) is the SAME wall-clock time -> overlap.
        String kallePersonId = participantProfileRepository.findByActivityPlanId(herrPlanId).get(0).personId();
        ParticipantProfile kalleInDam = participantProfileRepository.insert(new ParticipantProfile(
                Uuid7.generate(), kallePersonId, damPlanId, null, null, null, null, 500.0, 1.0, null, null, null,
                false, false));
        playerAssignmentRepository.lockToGroup(kalleInDam.id(), firstGroupId(damPlanId));

        List<SeasonConflict> conflicts = conflictService.findConflicts(seasonId);

        assertThat(conflicts)
                .anySatisfy(c -> {
                    assertThat(c.type()).isEqualTo(SeasonConflict.PERSON_DOUBLE_BOOKED);
                    assertThat(c.severity()).isEqualTo(SeasonConflict.SEVERITY_WARNING);
                    assertThat(c.personId()).isEqualTo(kallePersonId);
                    assertThat(c.usages()).hasSize(2);
                    assertThat(c.usages()).extracting(SeasonConflict.ConflictUsage::planId)
                            .containsExactlyInAnyOrder(herrPlanId, damPlanId);
                });

        // Regression (found via manual E2E verification, backend/docs/m6b-notes.md): herr's and
        // dam's groups round-robin onto small-10's 2 (club-shared, courts_per_slot=1) blocks, so
        // BOTH plans' group-1-vs-group-1 (block 0) AND group-2-vs-group-2 (block 1) genuinely share
        // a court at the same time - exactly 2 real conflicts. Each group has several players
        // (small-10: 5 each); the court sweep must report each collision EXACTLY ONCE, not once per
        // (player, opposing-player) pair (that bug would have produced up to 5x5 + 5x5 = 50 entries).
        long courtConflictCount = conflicts.stream().filter(c -> SeasonConflict.COURT_DOUBLE_BOOKED.equals(c.type())).count();
        assertThat(courtConflictCount)
                .as("court conflicts must be deduplicated per (plan, group, court, time), not fanned out per player")
                .isEqualTo(2);
    }

    @Test
    void aCoachInOnePlanPlayingInAnotherAtAnOverlappingTimeReportsCoachPlaysWhileCoaching() {
        String seasonId = seasonPlanRepository
                .insert(new se.klubb.groupplanner.domain.SeasonPlan(Uuid7.generate(), "VT26", null, null, "active",
                        java.time.Instant.now(), java.time.Instant.now()))
                .id();
        String herrPlanId = loadPlan(seasonId);
        String damPlanId = loadPlan(seasonId);
        scheduleEvenly(herrPlanId);
        scheduleEvenly(damPlanId);

        CoachProfile annaInHerr = coachProfileRepository.findByActivityPlanId(herrPlanId).get(0);
        coachAssignmentRepository.lockToGroup(annaInHerr.id(), firstGroupId(herrPlanId));

        ParticipantProfile annaInDam = participantProfileRepository.insert(new ParticipantProfile(
                Uuid7.generate(), annaInHerr.personId(), damPlanId, null, null, null, null, 500.0, 1.0, null, null,
                null, false, false));
        playerAssignmentRepository.lockToGroup(annaInDam.id(), firstGroupId(damPlanId));

        List<SeasonConflict> conflicts = conflictService.findConflicts(seasonId);

        assertThat(conflicts)
                .anySatisfy(c -> {
                    assertThat(c.type()).isEqualTo(SeasonConflict.COACH_PLAYS_WHILE_COACHING);
                    assertThat(c.personId()).isEqualTo(annaInHerr.personId());
                });
    }

    @Test
    void differentPlansWithNoSharedPeopleReportNoPersonConflicts() {
        String seasonId = seasonPlanRepository
                .insert(new se.klubb.groupplanner.domain.SeasonPlan(Uuid7.generate(), "VT26", null, null, "active",
                        java.time.Instant.now(), java.time.Instant.now()))
                .id();
        String herrPlanId = loadPlan(seasonId);
        String damPlanId = loadPlan(seasonId);
        scheduleEvenly(herrPlanId);
        scheduleEvenly(damPlanId);

        List<SeasonConflict> conflicts = conflictService.findConflicts(seasonId);

        assertThat(conflicts).noneMatch(c -> SeasonConflict.PERSON_DOUBLE_BOOKED.equals(c.type())
                || SeasonConflict.COACH_PLAYS_WHILE_COACHING.equals(c.type()));
    }

    @Test
    void aPlanInADifferentSeasonIsNeverCompared() {
        String seasonA = seasonPlanRepository
                .insert(new se.klubb.groupplanner.domain.SeasonPlan(Uuid7.generate(), "VT26", null, null, "active",
                        java.time.Instant.now(), java.time.Instant.now()))
                .id();
        String seasonB = seasonPlanRepository
                .insert(new se.klubb.groupplanner.domain.SeasonPlan(Uuid7.generate(), "HT26", null, null, "active",
                        java.time.Instant.now(), java.time.Instant.now()))
                .id();
        String herrPlanId = loadPlan(seasonA);
        String otherSeasonPlanId = loadPlan(seasonB);
        scheduleEvenly(herrPlanId);
        scheduleEvenly(otherSeasonPlanId);

        String kallePersonId = participantProfileRepository.findByActivityPlanId(herrPlanId).get(0).personId();
        ParticipantProfile kalleElsewhere = participantProfileRepository.insert(new ParticipantProfile(
                Uuid7.generate(), kallePersonId, otherSeasonPlanId, null, null, null, null, 500.0, 1.0, null, null,
                null, false, false));
        playerAssignmentRepository.lockToGroup(kalleElsewhere.id(), firstGroupId(otherSeasonPlanId));

        // Season A alone: only herrPlanId is in scope, so the "double booking" in season B is invisible.
        List<SeasonConflict> conflictsInSeasonA = conflictService.findConflicts(seasonA);
        assertThat(conflictsInSeasonA).noneMatch(c -> SeasonConflict.PERSON_DOUBLE_BOOKED.equals(c.type()));
    }
}
