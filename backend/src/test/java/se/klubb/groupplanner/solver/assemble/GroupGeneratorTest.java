package se.klubb.groupplanner.solver.assemble;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import se.klubb.groupplanner.api.error.ConflictException;
import se.klubb.groupplanner.domain.ActivityPlan;
import se.klubb.groupplanner.domain.ParticipantProfile;
import se.klubb.groupplanner.domain.Person;
import se.klubb.groupplanner.domain.PlayerAssignment;
import se.klubb.groupplanner.domain.SeasonPlan;
import se.klubb.groupplanner.domain.TimeSlot;
import se.klubb.groupplanner.domain.TrainingGroup;
import se.klubb.groupplanner.domain.Venue;
import se.klubb.groupplanner.repo.ActivityPlanRepository;
import se.klubb.groupplanner.repo.CourtRepository;
import se.klubb.groupplanner.repo.ParticipantProfileRepository;
import se.klubb.groupplanner.repo.PersonRepository;
import se.klubb.groupplanner.repo.PlayerAssignmentRepository;
import se.klubb.groupplanner.repo.SeasonPlanRepository;
import se.klubb.groupplanner.repo.TimeSlotRepository;
import se.klubb.groupplanner.repo.TrainingBlockRepository;
import se.klubb.groupplanner.repo.TrainingGroupRepository;
import se.klubb.groupplanner.repo.VenueRepository;
import se.klubb.groupplanner.domain.Court;
import se.klubb.groupplanner.domain.TrainingBlock;
import se.klubb.groupplanner.util.Uuid7;

/** GroupGenerator (docs/design/04-solver.md §7): count formula, naming, level bands, and the
 * simplified re-generation/lock-refusal policy (backend/docs/m6a-notes.md). */
@SpringBootTest
class GroupGeneratorTest {

    @TempDir
    static Path dataDir;

    @DynamicPropertySource
    static void appDataDir(DynamicPropertyRegistry registry) {
        registry.add("app.data-dir", () -> dataDir.toString());
    }

    @Autowired
    private GroupGenerator groupGenerator;
    @Autowired
    private SeasonPlanRepository seasonPlanRepository;
    @Autowired
    private ActivityPlanRepository activityPlanRepository;
    @Autowired
    private PersonRepository personRepository;
    @Autowired
    private ParticipantProfileRepository participantProfileRepository;
    @Autowired
    private VenueRepository venueRepository;
    @Autowired
    private CourtRepository courtRepository;
    @Autowired
    private TimeSlotRepository timeSlotRepository;
    @Autowired
    private TrainingBlockRepository trainingBlockRepository;
    @Autowired
    private TrainingGroupRepository trainingGroupRepository;
    @Autowired
    private PlayerAssignmentRepository playerAssignmentRepository;
    @Autowired
    private JdbcClient jdbcClient;

    private String createPlan(String category, Integer target, Integer min, Integer max) {
        return createPlan(category, target, min, max, null);
    }

    private String createPlan(String category, Integer target, Integer min, Integer max, Double defaultLevelMin) {
        Instant now = Instant.now();
        SeasonPlan season = seasonPlanRepository.insert(new SeasonPlan(Uuid7.generate(), "VT26", null, null, "active", now, now));
        ActivityPlan plan = activityPlanRepository.insert(
                new ActivityPlan(Uuid7.generate(), season.id(), "Herr", category, "draft", target, min, max, defaultLevelMin, now, now));
        return plan.id();
    }

    private void addParticipants(String planId, int count, double startLevel) {
        Instant now = Instant.now();
        for (int i = 0; i < count; i++) {
            Person person = personRepository.insert(new Person(
                    Uuid7.generate(), "P" + i, "Testsson", null, null, null, null, true, false, null, now, now));
            double level = startLevel + i;
            participantProfileRepository.insert(new ParticipantProfile(
                    Uuid7.generate(), person.id(), planId, null, null, null, null, level, 1.0, null, null, null, false, false));
        }
    }

    private void addActiveBlocks(String planId, int count) {
        Venue venue = venueRepository.insert(new Venue(Uuid7.generate(), "Hallen-" + Uuid7.generate().substring(0, 8), null));
        for (int i = 0; i < count; i++) {
            Court court = courtRepository.insert(new Court(Uuid7.generate(), venue.id(), "Bana " + i, true, null));
            TimeSlot slot = timeSlotRepository.insert(new TimeSlot(
                    Uuid7.generate(), planId, "THURSDAY", null, "18:0" + i, "19:3" + i, null, "Torsdag " + i));
            trainingBlockRepository.insert(new TrainingBlock(Uuid7.generate(), slot.id(), court.id(), planId, true, false, null));
        }
    }

    @Test
    void groupCountFollowsCeilOfActiveParticipantsOverTargetClampedToActiveBlocks() {
        String planId = createPlan("Torsdag Nybörjare", 5, 4, 6);
        addParticipants(planId, 10, 300);
        addActiveBlocks(planId, 2);

        List<TrainingGroup> groups = groupGenerator.generate(planId);

        assertThat(groups).hasSize(2); // ceil(10/5)=2, clamp(2,1,2)=2
        assertThat(groups.get(0).name()).isEqualTo("Torsdag Nybörjare 1");
        assertThat(groups.get(1).name()).isEqualTo("Torsdag Nybörjare 2");
        assertThat(groups.get(0).groupOrder()).isEqualTo(1);
        assertThat(groups.get(1).groupOrder()).isEqualTo(2);
        assertThat(groups).allSatisfy(g -> {
            assertThat(g.minSize()).isEqualTo(4);
            assertThat(g.targetSize()).isEqualTo(5);
            assertThat(g.maxSize()).isEqualTo(6);
        });
    }

    @Test
    void groupCountIsClampedToActiveBlockCountEvenWhenTargetWouldWantMore() {
        String planId = createPlan("Torsdag Herr", 10, 8, 12);
        addParticipants(planId, 130, 300);
        addActiveBlocks(planId, 12);

        List<TrainingGroup> groups = groupGenerator.generate(planId);

        assertThat(groups).hasSize(12); // ceil(130/10)=13, clamp(13,1,12)=12
    }

    @Test
    void topGroupGetsTheHighestLevelBand() {
        String planId = createPlan("Torsdag Herr", 5, 4, 6);
        addParticipants(planId, 10, 300); // levels 300..309
        addActiveBlocks(planId, 2);

        List<TrainingGroup> groups = groupGenerator.generate(planId);

        assertThat(groups.get(0).levelMin()).isGreaterThan(groups.get(1).levelMin());
        assertThat(groups.get(0).levelMax()).isGreaterThan(groups.get(1).levelMax());
    }

    /**
     * "Standard min-nivå" (v0.3.0 user feedback) clamp semantics on the LOWEST group's computed
     * band, per {@code GroupGenerator#applyLevelMinDefault}. All four scenarios below share the same
     * 10-participant/2-group setup as {@link #topGroupGetsTheHighestLevelBand}: levels 300..309 sort
     * into a highest band [305,309] (group 1) and a lowest band [300,304] (group 2).
     */
    @Test
    void unsetDefaultLevelMinLeavesTheLowestBandAtItsComputedFloor() {
        String planId = createPlan("Torsdag Herr", 5, 4, 6, null);
        addParticipants(planId, 10, 300);
        addActiveBlocks(planId, 2);

        List<TrainingGroup> groups = groupGenerator.generate(planId);

        assertThat(groups.get(1).levelMin()).isEqualTo(300.0);
        assertThat(groups.get(1).levelMax()).isEqualTo(304.0);
    }

    @Test
    void defaultLevelMinBelowTheComputedFloorLeavesTheLowestBandUnchanged() {
        String planId = createPlan("Torsdag Herr", 5, 4, 6, 250.0);
        addParticipants(planId, 10, 300);
        addActiveBlocks(planId, 2);

        List<TrainingGroup> groups = groupGenerator.generate(planId);

        assertThat(groups.get(1).levelMin()).isEqualTo(300.0); // computed floor (300) already above the 250 default
        assertThat(groups.get(1).levelMax()).isEqualTo(304.0);
        assertThat(groups.get(0).levelMin()).isEqualTo(305.0); // top group untouched
    }

    @Test
    void defaultLevelMinInsideTheComputedBandRaisesOnlyTheLowestGroupsFloor() {
        String planId = createPlan("Torsdag Herr", 5, 4, 6, 302.0);
        addParticipants(planId, 10, 300);
        addActiveBlocks(planId, 2);

        List<TrainingGroup> groups = groupGenerator.generate(planId);

        assertThat(groups.get(1).levelMin()).isEqualTo(302.0); // raised from the computed 300
        assertThat(groups.get(1).levelMax()).isEqualTo(304.0); // ceiling untouched
        assertThat(groups.get(0).levelMin()).isEqualTo(305.0); // top group untouched
        assertThat(groups.get(0).levelMax()).isEqualTo(309.0);
    }

    @Test
    void defaultLevelMinAboveTheLowestBandsCeilingClampsToItInsteadOfInvertingMinAboveMax() {
        String planId = createPlan("Torsdag Herr", 5, 4, 6, 500.0); // above every participant's level (300..309)
        addParticipants(planId, 10, 300);
        addActiveBlocks(planId, 2);

        List<TrainingGroup> groups = groupGenerator.generate(planId);

        assertThat(groups.get(1).levelMin()).isEqualTo(304.0); // clamped down to the band's own ceiling
        assertThat(groups.get(1).levelMax()).isEqualTo(304.0);
        assertThat(groups.get(1).levelMin()).isLessThanOrEqualTo(groups.get(1).levelMax()); // invariant
    }

    @Test
    void regeneratingWithTheSameGroupCountPreservesGroupIds() {
        String planId = createPlan("Torsdag Nybörjare", 5, 4, 6);
        addParticipants(planId, 10, 300);
        addActiveBlocks(planId, 2);

        List<TrainingGroup> first = groupGenerator.generate(planId);
        String firstGroup1Id = first.get(0).id();

        addParticipants(planId, 2, 400); // still ceil(12/5)=3 > clamp to 2 blocks -> groupCount stays 2
        List<TrainingGroup> second = groupGenerator.generate(planId);

        assertThat(second).hasSize(2);
        assertThat(second.get(0).id()).isEqualTo(firstGroup1Id);
    }

    @Test
    void regenerationRefusedWhenAnExistingGroupIsLocked() {
        String planId = createPlan("Torsdag Nybörjare", 5, 4, 6);
        addParticipants(planId, 10, 300);
        addActiveBlocks(planId, 2);
        List<TrainingGroup> groups = groupGenerator.generate(planId);
        TrainingGroup locked = groups.get(0);
        trainingGroupRepository.update(new TrainingGroup(
                locked.id(), locked.activityPlanId(), locked.name(), locked.groupOrder(), locked.category(),
                locked.minSize(), locked.targetSize(), locked.maxSize(), locked.requiredCoachCount(),
                locked.levelMin(), locked.levelMax(), locked.assignedTrainingBlockId(), true));

        assertThatThrownBy(() -> groupGenerator.generate(planId)).isInstanceOf(ConflictException.class);
    }

    @Test
    void regenerationRefusedWhenAPlayerAssignmentIsLocked() {
        String planId = createPlan("Torsdag Nybörjare", 5, 4, 6);
        addParticipants(planId, 10, 300);
        addActiveBlocks(planId, 2);
        List<TrainingGroup> groups = groupGenerator.generate(planId);
        ParticipantProfile someParticipant = participantProfileRepository.findByActivityPlanId(planId).get(0);
        PlayerAssignment assignment = playerAssignmentRepository.insertImportedIfAbsent(someParticipant.id());
        // No dedicated "lock" endpoint exists in M6a scope (locking arrives with the results UI in
        // M6b); a raw JdbcClient update sets the row's locked flag directly for this guard test,
        // mirroring the direct-DB-assertion pattern already used by CommentPrivacyControllerTest.
        jdbcClient.sql("UPDATE player_assignment SET group_id = :groupId, locked = 1 WHERE id = :id")
                .param("groupId", groups.get(0).id())
                .param("id", assignment.id())
                .update();

        assertThatThrownBy(() -> groupGenerator.generate(planId)).isInstanceOf(ConflictException.class);
    }
}
