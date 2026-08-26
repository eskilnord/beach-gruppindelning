package se.klubb.groupplanner.resources;

import static org.assertj.core.api.Assertions.assertThat;

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
import se.klubb.groupplanner.domain.ActivityPlan;
import se.klubb.groupplanner.domain.SeasonPlan;
import se.klubb.groupplanner.domain.TimeSlot;
import se.klubb.groupplanner.repo.ActivityPlanRepository;
import se.klubb.groupplanner.repo.SeasonPlanRepository;
import se.klubb.groupplanner.repo.TimeSlotRepository;
import se.klubb.groupplanner.util.Uuid7;

/**
 * DB-level tests for {@link DefaultTimeSlotService#seedDefaults} (v0.6.0 B3): the three default
 * Thursday time slots, idempotency, and that no {@code training_block} row is ever created as a
 * side effect (only {@code TimeSlot} rows — court/block generation is a separate, explicit step,
 * spec §12.2).
 */
@SpringBootTest
class DefaultTimeSlotServiceTest {

    @TempDir
    static Path dataDir;

    @DynamicPropertySource
    static void appDataDir(DynamicPropertyRegistry registry) {
        registry.add("app.data-dir", () -> dataDir.toString());
    }

    @Autowired
    private DefaultTimeSlotService defaultTimeSlotService;
    @Autowired
    private SeasonPlanRepository seasonPlanRepository;
    @Autowired
    private ActivityPlanRepository activityPlanRepository;
    @Autowired
    private TimeSlotRepository timeSlotRepository;
    @Autowired
    private JdbcClient jdbcClient;

    private String createPlan() {
        Instant now = Instant.now();
        SeasonPlan season = seasonPlanRepository.insert(
                new SeasonPlan(Uuid7.generate(), "VT26", null, null, "active", now, now));
        ActivityPlan plan = activityPlanRepository.insert(
                new ActivityPlan(Uuid7.generate(), season.id(), "Herr", "beach", "draft", 10, 8, 12, null, now, now));
        return plan.id();
    }

    @Test
    void seedsThreeThursdaySlotsWithExactSwedishLabelsAnd90MinuteDurations() {
        String planId = createPlan();

        List<TimeSlot> created = defaultTimeSlotService.seedDefaults(planId);

        assertThat(created).hasSize(3);
        assertThat(created).allSatisfy(slot -> {
            assertThat(slot.activityPlanId()).isEqualTo(planId);
            assertThat(slot.dayOfWeek()).isEqualTo("THURSDAY");
            assertThat(slot.date()).isNull();
            assertThat(slot.durationMinutes()).isEqualTo(90);
        });

        TimeSlot first = created.get(0);
        assertThat(first.startTime()).isEqualTo("18:00");
        assertThat(first.endTime()).isEqualTo("19:30");
        assertThat(first.label()).isEqualTo("Torsdag 18.00–19.30");

        TimeSlot second = created.get(1);
        assertThat(second.startTime()).isEqualTo("19:30");
        assertThat(second.endTime()).isEqualTo("21:00");
        assertThat(second.label()).isEqualTo("Torsdag 19.30–21.00");

        TimeSlot third = created.get(2);
        assertThat(third.startTime()).isEqualTo("21:00");
        assertThat(third.endTime()).isEqualTo("22:30");
        assertThat(third.label()).isEqualTo("Torsdag 21.00–22.30");

        // Persisted, not just returned.
        assertThat(timeSlotRepository.findByActivityPlanId(planId)).hasSize(3);
    }

    @Test
    void secondCallIsANoOp() {
        String planId = createPlan();

        List<TimeSlot> first = defaultTimeSlotService.seedDefaults(planId);
        assertThat(first).hasSize(3);

        List<TimeSlot> second = defaultTimeSlotService.seedDefaults(planId);

        assertThat(second).isEmpty();
        assertThat(timeSlotRepository.findByActivityPlanId(planId)).hasSize(3);
    }

    @Test
    void aPreExistingSlotSuppressesSeedingAndIsLeftUntouched() {
        String planId = createPlan();
        TimeSlot manual = timeSlotRepository.insert(
                new TimeSlot(Uuid7.generate(), planId, "MONDAY", null, "10:00", "11:00", 60, "Måndag 10.00–11.00"));

        List<TimeSlot> created = defaultTimeSlotService.seedDefaults(planId);

        assertThat(created).isEmpty();
        List<TimeSlot> slots = timeSlotRepository.findByActivityPlanId(planId);
        assertThat(slots).hasSize(1);
        assertThat(slots.get(0)).isEqualTo(manual);
    }

    @Test
    void noTrainingBlockRowsAreCreatedBySeeding() {
        String planId = createPlan();

        defaultTimeSlotService.seedDefaults(planId);

        Integer trainingBlockCount = jdbcClient.sql("SELECT COUNT(*) FROM training_block WHERE activity_plan_id = :planId")
                .param("planId", planId)
                .query(Integer.class)
                .single();
        assertThat(trainingBlockCount).isZero();
    }
}
