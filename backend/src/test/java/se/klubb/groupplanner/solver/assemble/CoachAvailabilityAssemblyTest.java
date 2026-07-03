package se.klubb.groupplanner.solver.assemble;

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
import se.klubb.groupplanner.domain.Person;
import se.klubb.groupplanner.domain.SeasonPlan;
import se.klubb.groupplanner.domain.TimeSlot;
import se.klubb.groupplanner.repo.ActivityPlanRepository;
import se.klubb.groupplanner.repo.CoachProfileRepository;
import se.klubb.groupplanner.repo.CoachTimeSlotRepository;
import se.klubb.groupplanner.repo.PersonRepository;
import se.klubb.groupplanner.repo.SeasonPlanRepository;
import se.klubb.groupplanner.repo.TimeSlotRepository;
import se.klubb.groupplanner.solver.domain.CoachFact;
import se.klubb.groupplanner.util.Uuid7;

/**
 * WI-B: {@link SolverInputAssembler}'s fold-down of {@code coach_time_slot} rows into {@link
 * CoachFact#availableTimeSlotIds()} - the union of explicit {@code AVAILABLE}/{@code PREFERRED}
 * rows, backing the new {@code coachUnknownTimeSlot} SOFT constraint. A time slot with NO row
 * ("Okänd") must be excluded from this array (and stay excluded from {@code
 * unavailableTimeSlotIds} too - it is not hard-blocked, only soft-nudged).
 *
 * <p>Relies on {@link Uuid7}'s documented monotonic-within-a-JVM-call-sequence ordering (its own
 * javadoc: "IDs generated back-to-back sort strictly increasing") and on {@code
 * SolverInputAssembler} indexing time slots via {@code sortedById} (a plain ascending sort of the
 * {@code TimeSlot.id} UUIDv7 strings, NOT the repository's weekday-based ordering) to know, without
 * reaching into assembler-internal state, that four slots generated and inserted in order get
 * solver ids 1..4 in that same order.
 */
@SpringBootTest
class CoachAvailabilityAssemblyTest {

    @TempDir
    static Path dataDir;

    @DynamicPropertySource
    static void appDataDir(DynamicPropertyRegistry registry) {
        registry.add("app.data-dir", () -> dataDir.toString());
    }

    @Autowired
    private SolverInputAssembler assembler;
    @Autowired
    private SeasonPlanRepository seasonPlanRepository;
    @Autowired
    private ActivityPlanRepository activityPlanRepository;
    @Autowired
    private TimeSlotRepository timeSlotRepository;
    @Autowired
    private PersonRepository personRepository;
    @Autowired
    private CoachProfileRepository coachProfileRepository;
    @Autowired
    private CoachTimeSlotRepository coachTimeSlotRepository;

    private String createPlan() {
        Instant now = Instant.now();
        SeasonPlan season = seasonPlanRepository.insert(new SeasonPlan(Uuid7.generate(), "VT26", null, null, "active", now, now));
        ActivityPlan plan = activityPlanRepository.insert(
                new ActivityPlan(Uuid7.generate(), season.id(), "Herr", "beach", "draft", 10, 8, 12, null, now, now));
        return plan.id();
    }

    private TimeSlot insertSlot(String planId, String label) {
        TimeSlot slot = new TimeSlot(Uuid7.generate(), planId, "THURSDAY", null, "18:00", "19:30", 90, label);
        return timeSlotRepository.insert(slot);
    }

    @Test
    void availableTimeSlotIdsIsBuiltFromAvailableAndPreferredRowsAndExcludesUnknownSlots() {
        String planId = createPlan();

        // Generated (and inserted) strictly in this order - see class javadoc for why that pins
        // their solver long ids to 1, 2, 3, 4 respectively.
        TimeSlot available = insertSlot(planId, "Explicit AVAILABLE");
        TimeSlot preferred = insertSlot(planId, "Explicit PREFERRED");
        TimeSlot unavailable = insertSlot(planId, "Explicit UNAVAILABLE");
        TimeSlot unknown = insertSlot(planId, "Okänd - no row at all");

        Instant now = Instant.now();
        Person person = personRepository.insert(new Person(
                Uuid7.generate(), "Anna", "Coachsson", null, null, null, null, false, true, null, now, now));
        CoachProfile coachProfile = coachProfileRepository.insert(
                new CoachProfile(Uuid7.generate(), person.id(), planId, 60.0, 0.0, 100.0, null, null, false, null));

        coachTimeSlotRepository.insert(new CoachTimeSlot(Uuid7.generate(), coachProfile.id(), available.id(), CoachTimeSlot.AVAILABLE));
        coachTimeSlotRepository.insert(new CoachTimeSlot(Uuid7.generate(), coachProfile.id(), preferred.id(), CoachTimeSlot.PREFERRED));
        coachTimeSlotRepository.insert(new CoachTimeSlot(Uuid7.generate(), coachProfile.id(), unavailable.id(), CoachTimeSlot.UNAVAILABLE));
        // `unknown` deliberately gets no coach_time_slot row at all - the frontend PUT payload omits
        // UNKNOWN rows entirely (frontend/src/routes/plan/coaches/availability.ts).

        CoachFact fact = assembler.assemble(planId).solution().getCoaches().get(0);

        assertThat(fact.availableTimeSlotIds()).containsExactly(1L, 2L);
        assertThat(fact.preferredTimeSlotIds()).containsExactly(2L);
        assertThat(fact.unavailableTimeSlotIds()).containsExactly(3L);

        assertThat(fact.hasKnownAvailabilityAt(1L)).as("explicit AVAILABLE slot").isTrue();
        assertThat(fact.hasKnownAvailabilityAt(2L)).as("explicit PREFERRED slot").isTrue();
        assertThat(fact.hasKnownAvailabilityAt(3L)).as("explicit UNAVAILABLE slot is not in availableTimeSlotIds").isFalse();
        assertThat(fact.hasKnownAvailabilityAt(4L)).as("unknown/unlisted slot").isFalse();

        assertThat(fact.availableAt(1L)).isTrue();
        assertThat(fact.availableAt(3L)).as("explicit UNAVAILABLE still blocks (HARD layer unchanged)").isFalse();
        assertThat(fact.availableAt(4L)).as("unknown/unlisted slot is NOT hard-blocked").isTrue();
    }
}
