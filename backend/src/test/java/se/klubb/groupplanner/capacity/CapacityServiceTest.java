package se.klubb.groupplanner.capacity;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Path;
import java.time.Instant;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import se.klubb.groupplanner.domain.ActivityPlan;
import se.klubb.groupplanner.domain.CoachProfile;
import se.klubb.groupplanner.domain.CoachTimeSlot;
import se.klubb.groupplanner.domain.Court;
import se.klubb.groupplanner.domain.ParticipantProfile;
import se.klubb.groupplanner.domain.Person;
import se.klubb.groupplanner.domain.SeasonPlan;
import se.klubb.groupplanner.domain.TimeSlot;
import se.klubb.groupplanner.domain.TrainingBlock;
import se.klubb.groupplanner.domain.Venue;
import se.klubb.groupplanner.repo.ActivityPlanRepository;
import se.klubb.groupplanner.repo.CoachProfileRepository;
import se.klubb.groupplanner.repo.CoachTimeSlotRepository;
import se.klubb.groupplanner.repo.CourtRepository;
import se.klubb.groupplanner.repo.ParticipantProfileRepository;
import se.klubb.groupplanner.repo.PersonRepository;
import se.klubb.groupplanner.repo.SeasonPlanRepository;
import se.klubb.groupplanner.repo.TimeSlotRepository;
import se.klubb.groupplanner.repo.TrainingBlockRepository;
import se.klubb.groupplanner.repo.VenueRepository;
import se.klubb.groupplanner.util.Uuid7;

/**
 * Capacity math tests (spec §12.4), incl. reproducing the spec's own worked example exactly, and
 * the two coach-shortage signals described in {@link CapacityService}'s Javadoc.
 */
@SpringBootTest
class CapacityServiceTest {

    @TempDir
    static Path dataDir;

    @DynamicPropertySource
    static void appDataDir(DynamicPropertyRegistry registry) {
        registry.add("app.data-dir", () -> dataDir.toString());
    }

    @Autowired
    private CapacityService capacityService;
    @Autowired
    private SeasonPlanRepository seasonPlanRepository;
    @Autowired
    private ActivityPlanRepository activityPlanRepository;
    @Autowired
    private PersonRepository personRepository;
    @Autowired
    private ParticipantProfileRepository participantProfileRepository;
    @Autowired
    private TimeSlotRepository timeSlotRepository;
    @Autowired
    private VenueRepository venueRepository;
    @Autowired
    private CourtRepository courtRepository;
    @Autowired
    private TrainingBlockRepository trainingBlockRepository;
    @Autowired
    private CoachProfileRepository coachProfileRepository;
    @Autowired
    private CoachTimeSlotRepository coachTimeSlotRepository;

    // static: the DB (and its court.venue_id+name UNIQUE index) is shared across every test method
    // in this class (one Spring context per class, keyed by the static @TempDir), so the counter
    // must also be shared to guarantee unique names across methods, not just within one.
    private static final java.util.concurrent.atomic.AtomicInteger courtNameCounter = new java.util.concurrent.atomic.AtomicInteger();

    private String createPlan(Integer target, Integer min, Integer max) {
        Instant now = Instant.now();
        SeasonPlan season = seasonPlanRepository.insert(
                new SeasonPlan(Uuid7.generate(), "VT26", null, null, "active", now, now));
        ActivityPlan plan = activityPlanRepository.insert(
                new ActivityPlan(Uuid7.generate(), season.id(), "Herr", "beach", "draft", target, min, max, now, now));
        return plan.id();
    }

    private void addParticipants(String planId, int count) {
        Instant now = Instant.now();
        for (int i = 0; i < count; i++) {
            Person person = personRepository.insert(new Person(
                    Uuid7.generate(), "Spelare", "Nr" + i, null, null, null, null, true, false, null, now, now));
            participantProfileRepository.insert(new ParticipantProfile(
                    Uuid7.generate(), person.id(), planId, 500.0, "test", null, null, null, null, null,
                    null, null, false, false));
        }
    }

    /** Creates one time slot with {@code activeBlockCount} active blocks, all on distinct courts. */
    private TimeSlot createSlotWithBlocks(String planId, String dayOfWeek, String start, String end, int activeBlockCount) {
        TimeSlot slot = timeSlotRepository.insert(new TimeSlot(
                Uuid7.generate(), planId, dayOfWeek, null, start, end, null,
                dayOfWeek + " " + start + "-" + end));
        String venueId = venueRepository.findFirst().map(Venue::id)
                .orElseGet(() -> venueRepository.insert(new Venue(Uuid7.generate(), "Hallen", null)).id());
        for (int i = 1; i <= activeBlockCount; i++) {
            // UUIDv7's first 8 hex chars are a millisecond timestamp prefix that stays constant for
            // ~65s (RFC 9562 §5.7) - unsuitable as a "unique within this fast test" suffix. The
            // court.venue_id+name UNIQUE index (V4__resources.sql) needs a genuinely unique name, so
            // a monotonic counter is used instead.
            String courtName = "Bana test-" + courtNameCounter.incrementAndGet();
            Court court = courtRepository.insert(new Court(Uuid7.generate(), venueId, courtName, true, null));
            trainingBlockRepository.insert(new TrainingBlock(Uuid7.generate(), slot.id(), court.id(), planId, true, false, null));
        }
        return slot;
    }

    private CoachProfile createCoach(String planId, Integer maxGroupsPerDay, Integer maxGroupsPerWeek) {
        Instant now = Instant.now();
        Person person = personRepository.insert(new Person(
                Uuid7.generate(), "Coach", Uuid7.generate().substring(0, 8), null, null, null, null, false, true, null, now, now));
        return coachProfileRepository.insert(new CoachProfile(
                Uuid7.generate(), person.id(), planId, null, null, null,
                maxGroupsPerDay, maxGroupsPerWeek, false, null));
    }

    private void markSlot(CoachProfile coach, TimeSlot slot, String kind) {
        coachTimeSlotRepository.insert(new CoachTimeSlot(Uuid7.generate(), coach.id(), slot.id(), kind));
    }

    /**
     * Spec §12.4's exact worked example: 122 anmälda, 11 TrainingBlocks, target 10 / max 12 -&gt;
     * targetkapacitet 110, maxkapacitet 132, "Möjligt, men grupperna blir större än target".
     */
    @Test
    void reproducesSpecSection12Point4ExampleExactly() {
        String planId = createPlan(10, 8, 12);
        createSlotWithBlocks(planId, "THURSDAY", "18:00", "19:30", 11);
        addParticipants(planId, 122);

        CapacityResponse response = capacityService.compute(planId);

        assertThat(response.participantCount()).isEqualTo(122);
        assertThat(response.waitlistedCount()).isZero(); // the spec example has no waitlisted participants
        assertThat(response.activeTrainingBlockCount()).isEqualTo(11);
        assertThat(response.targetGroupSize()).isEqualTo(10);
        assertThat(response.maxGroupSize()).isEqualTo(12);
        assertThat(response.targetCapacity()).isEqualTo(110);
        assertThat(response.maxCapacity()).isEqualTo(132);
        assertThat(response.waitlistRisk()).isEqualTo(CapacityResponse.RISK_OVER_TARGET);
        assertThat(response.waitlistMessage()).isEqualTo("Möjligt, men grupperna blir större än target");
    }

    /**
     * Spec §12.4 says "Antal anmälda" - a waitlisted participant is still registered, so they count
     * (M5 review fix); the waitlisted subset is reported separately as {@code waitlistedCount}.
     */
    @Test
    void participantCountIncludesWaitlistedAndWaitlistedCountReportsTheSubset() {
        String planId = createPlan(10, 8, 10);
        createSlotWithBlocks(planId, "THURSDAY", "18:00", "19:30", 2);
        addParticipants(planId, 5);
        Instant now = Instant.now();
        Person waitlistedPerson = personRepository.insert(new Person(
                Uuid7.generate(), "Kö", "Person", null, null, null, null, true, false, null, now, now));
        participantProfileRepository.insert(new ParticipantProfile(
                Uuid7.generate(), waitlistedPerson.id(), planId, null, null, null, null, null, null, null,
                null, null, false, true));

        CapacityResponse response = capacityService.compute(planId);

        assertThat(response.participantCount()).isEqualTo(6);
        assertThat(response.waitlistedCount()).isEqualTo(1);
    }

    @Test
    void underCapacityIsRiskNone() {
        String planId = createPlan(10, 8, 12);
        createSlotWithBlocks(planId, "THURSDAY", "18:00", "19:30", 2);
        addParticipants(planId, 15);

        CapacityResponse response = capacityService.compute(planId);

        assertThat(response.targetCapacity()).isEqualTo(20);
        assertThat(response.waitlistRisk()).isEqualTo(CapacityResponse.RISK_NONE);
    }

    @Test
    void overMaxCapacityRequiresWaitlist() {
        String planId = createPlan(10, 8, 12);
        createSlotWithBlocks(planId, "THURSDAY", "18:00", "19:30", 2); // max capacity = 24
        addParticipants(planId, 30);

        CapacityResponse response = capacityService.compute(planId);

        assertThat(response.waitlistRisk()).isEqualTo(CapacityResponse.RISK_OVER_MAX);
        assertThat(response.waitlistMessage()).isEqualTo("Fler anmälda än maxkapacitet — kölista krävs");
    }

    @Test
    void missingDefaultSizesYieldsUnknownRiskInsteadOfCrashing() {
        String planId = createPlan(null, null, null);
        createSlotWithBlocks(planId, "THURSDAY", "18:00", "19:30", 2);
        addParticipants(planId, 5);

        CapacityResponse response = capacityService.compute(planId);

        assertThat(response.targetCapacity()).isNull();
        assertThat(response.maxCapacity()).isNull();
        assertThat(response.waitlistRisk()).isEqualTo(CapacityResponse.RISK_UNKNOWN);
    }

    /**
     * Coach-shortage signal 1: enough coaches overall, but explicitly UNAVAILABLE at a slot that
     * needs them. Semantics per M5 review fix: only an explicit UNAVAILABLE row blocks a coach from
     * a slot (neutral/unlisted = available, matching the solver's CoachFact which carries only
     * unavailableTimeSlotIds), so this scenario marks the coaches UNAVAILABLE at slot2 rather than
     * merely omitting rows for it.
     */
    @Test
    void coachShortageDetectedWhenCoachesAreUnavailableAtTheSlotThatNeedsThem() {
        String planId = createPlan(5, 4, 6);
        TimeSlot slot1 = createSlotWithBlocks(planId, "THURSDAY", "16:30", "18:00", 2);
        TimeSlot slot2 = createSlotWithBlocks(planId, "THURSDAY", "18:00", "19:30", 2);
        addParticipants(planId, 5);

        // 2 unlimited coaches, both fine at slot1 but explicitly UNAVAILABLE at slot2.
        for (int i = 0; i < 2; i++) {
            CoachProfile coach = createCoach(planId, null, null);
            markSlot(coach, slot1, CoachTimeSlot.AVAILABLE);
            markSlot(coach, slot2, CoachTimeSlot.UNAVAILABLE);
        }

        CapacityResponse response = capacityService.compute(planId);

        assertThat(response.coachCount()).isEqualTo(2);
        assertThat(response.groupsRequiringCoachEstimate()).isEqualTo(4);
        assertThat(response.coachShortageRisk()).isTrue();
        assertThat(response.coachShortageMessage()).contains("för få tillgängliga tränare");
        var slot1View = response.perTimeSlot().stream().filter(v -> v.timeSlotId().equals(slot1.id())).findFirst().orElseThrow();
        assertThat(slot1View.coachesAvailableCount()).isEqualTo(2);
        var slot2View = response.perTimeSlot().stream().filter(v -> v.timeSlotId().equals(slot2.id())).findFirst().orElseThrow();
        assertThat(slot2View.coachesAvailableCount()).isZero();
        assertThat(slot2View.activeBlockCount()).isEqualTo(2);
    }

    /**
     * The false-positive case the M5 review called out: coaches who simply have NOT filled in any
     * availability yet (no coach_time_slot rows at all) must count as available everywhere - a
     * pre-solve warning that fires just because data entry is incomplete would train users to
     * ignore it.
     */
    @Test
    void noShortageWarningWhenCoachesHaveNoAvailabilityRowsAtAll() {
        String planId = createPlan(5, 4, 6);
        TimeSlot slot1 = createSlotWithBlocks(planId, "THURSDAY", "16:30", "18:00", 2);
        TimeSlot slot2 = createSlotWithBlocks(planId, "THURSDAY", "18:00", "19:30", 2);
        addParticipants(planId, 5);
        createCoach(planId, null, null);
        createCoach(planId, null, null); // neither coach has any coach_time_slot row

        CapacityResponse response = capacityService.compute(planId);

        assertThat(response.coachShortageRisk()).isFalse();
        for (var view : response.perTimeSlot()) {
            assertThat(view.coachesAvailableCount()).isEqualTo(2); // neutral = available
            assertThat(view.coachesPreferredCount()).isZero();
        }
    }

    /** Coach-shortage signal 2: coaches present at every slot, but their combined weekly cap is too low. */
    @Test
    void coachShortageDetectedWhenAggregateMaxGroupsPerWeekIsTooLow() {
        String planId = createPlan(5, 4, 6);
        TimeSlot slot1 = createSlotWithBlocks(planId, "THURSDAY", "16:30", "18:00", 2);
        TimeSlot slot2 = createSlotWithBlocks(planId, "FRIDAY", "16:30", "18:00", 2);
        addParticipants(planId, 5);

        // 2 coaches, both available at BOTH slots (no per-slot deficiency: 2 coaches >= 2 blocks at
        // each slot individually), but each capped at 1 group/week in total -> 2 < 4 groups needed.
        CoachProfile coachA = createCoach(planId, null, 1);
        CoachProfile coachB = createCoach(planId, null, 1);
        for (CoachProfile coach : java.util.List.of(coachA, coachB)) {
            markSlot(coach, slot1, CoachTimeSlot.AVAILABLE);
            markSlot(coach, slot2, CoachTimeSlot.AVAILABLE);
        }

        CapacityResponse response = capacityService.compute(planId);

        assertThat(response.groupsRequiringCoachEstimate()).isEqualTo(4);
        assertThat(response.coachShortageRisk()).isTrue();
        assertThat(response.coachShortageMessage()).contains("sammanlagda maxantal grupper");
        // Per-slot coverage was fine at each individual slot.
        for (var view : response.perTimeSlot()) {
            assertThat(view.coachesAvailableCount()).isGreaterThanOrEqualTo(view.activeBlockCount());
        }
    }

    @Test
    void noShortageWhenCoachesCoverEverySlotAndHaveNoBindingLimits() {
        String planId = createPlan(5, 4, 6);
        TimeSlot slot1 = createSlotWithBlocks(planId, "THURSDAY", "16:30", "18:00", 2);
        addParticipants(planId, 5);
        CoachProfile coachA = createCoach(planId, null, null);
        CoachProfile coachB = createCoach(planId, null, null);
        markSlot(coachA, slot1, CoachTimeSlot.AVAILABLE);
        markSlot(coachB, slot1, CoachTimeSlot.PREFERRED);

        CapacityResponse response = capacityService.compute(planId);

        assertThat(response.coachShortageRisk()).isFalse();
        assertThat(response.coachShortageMessage()).isEqualTo("Tillräckligt med tränare för samtliga grupper");
        // PREFERRED is surfaced separately in the breakdown (forward signal for M6b).
        var slot1View = response.perTimeSlot().stream().filter(v -> v.timeSlotId().equals(slot1.id())).findFirst().orElseThrow();
        assertThat(slot1View.coachesPreferredCount()).isEqualTo(1);
    }
}
