package se.klubb.groupplanner.repo;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import se.klubb.groupplanner.domain.TimeSlot;

/**
 * {@code time_slot} CRUD via {@link JdbcClient} (ADR-004, spec §6.7/§7.9).
 */
@Repository
public class TimeSlotRepository {

    private final JdbcClient jdbcClient;

    public TimeSlotRepository(JdbcClient jdbcClient) {
        this.jdbcClient = jdbcClient;
    }

    /**
     * Slots in schedule order (M5 review fix): {@code day_of_week} stores {@link DayOfWeek} enum
     * NAMES, whose lexicographic order (FRIDAY &lt; THURSDAY &lt; TUESDAY ...) is not weekday order,
     * so ordering happens Java-side — by weekday ordinal (Monday=1..Sunday=7; a dated-only slot
     * derives its weekday from the date), then by concrete date when set, then start time, then id.
     * Every schedule-shaped view (slot list, grouped block view, capacity breakdown) reads through
     * this method, so they all share this ordering.
     */
    public List<TimeSlot> findByActivityPlanId(String activityPlanId) {
        List<TimeSlot> slots = jdbcClient.sql("SELECT * FROM time_slot WHERE activity_plan_id = :activityPlanId")
                .param("activityPlanId", activityPlanId)
                .query(TimeSlotRepository::mapRow)
                .list();
        return slots.stream()
                .sorted(Comparator
                        .comparingInt(TimeSlotRepository::weekdayOrdinal)
                        .thenComparing(slot -> slot.date() == null ? "" : slot.date())
                        .thenComparing(TimeSlot::startTime)
                        .thenComparing(TimeSlot::id))
                .toList();
    }

    /** Monday=1..Sunday=7, derived from {@code date} for dated-only slots; 8 if neither resolves. */
    private static int weekdayOrdinal(TimeSlot slot) {
        if (slot.dayOfWeek() != null) {
            try {
                return DayOfWeek.valueOf(slot.dayOfWeek()).getValue();
            } catch (IllegalArgumentException e) {
                return 8; // Unknown column value - sort last rather than blow up every list view.
            }
        }
        if (slot.date() != null) {
            try {
                return LocalDate.parse(slot.date()).getDayOfWeek().getValue();
            } catch (DateTimeParseException e) {
                return 8;
            }
        }
        return 8;
    }

    public Optional<TimeSlot> findById(String id) {
        return jdbcClient.sql("SELECT * FROM time_slot WHERE id = :id")
                .param("id", id)
                .query(TimeSlotRepository::mapRow)
                .optional();
    }

    /**
     * Finds an existing slot in the same plan with the identical (dayOfWeek, date, startTime,
     * endTime) tuple, optionally excluding one id (for PATCH self-comparison). Used by {@code
     * TimeSlotController} to enforce the exact-duplicate 409 rule (docs/plan.md M5 row) — a plain
     * {@code UNIQUE} column can't express this since {@code date} is nullable and SQL treats every
     * {@code NULL} as distinct from every other {@code NULL}.
     */
    public Optional<TimeSlot> findDuplicate(
            String activityPlanId, String dayOfWeek, String date, String startTime, String endTime, String excludeId) {
        return jdbcClient.sql("""
                        SELECT * FROM time_slot
                        WHERE activity_plan_id = :activityPlanId
                          AND start_time = :startTime AND end_time = :endTime
                          AND day_of_week IS :dayOfWeek AND date IS :date
                          AND (:excludeId IS NULL OR id != :excludeId)
                        """)
                .param("activityPlanId", activityPlanId)
                .param("startTime", startTime)
                .param("endTime", endTime)
                .param("dayOfWeek", dayOfWeek)
                .param("date", date)
                .param("excludeId", excludeId)
                .query(TimeSlotRepository::mapRow)
                .optional();
    }

    public TimeSlot insert(TimeSlot slot) {
        jdbcClient.sql("""
                        INSERT INTO time_slot
                            (id, activity_plan_id, day_of_week, date, start_time, end_time, duration_minutes, label)
                        VALUES
                            (:id, :activityPlanId, :dayOfWeek, :date, :startTime, :endTime, :durationMinutes, :label)
                        """)
                .param("id", slot.id())
                .param("activityPlanId", slot.activityPlanId())
                .param("dayOfWeek", slot.dayOfWeek())
                .param("date", slot.date())
                .param("startTime", slot.startTime())
                .param("endTime", slot.endTime())
                .param("durationMinutes", slot.durationMinutes())
                .param("label", slot.label())
                .update();
        return slot;
    }

    public TimeSlot update(TimeSlot slot) {
        jdbcClient.sql("""
                        UPDATE time_slot
                        SET day_of_week = :dayOfWeek, date = :date, start_time = :startTime,
                            end_time = :endTime, duration_minutes = :durationMinutes, label = :label
                        WHERE id = :id
                        """)
                .param("id", slot.id())
                .param("dayOfWeek", slot.dayOfWeek())
                .param("date", slot.date())
                .param("startTime", slot.startTime())
                .param("endTime", slot.endTime())
                .param("durationMinutes", slot.durationMinutes())
                .param("label", slot.label())
                .update();
        return slot;
    }

    public boolean deleteById(String id) {
        int rows = jdbcClient.sql("DELETE FROM time_slot WHERE id = :id").param("id", id).update();
        return rows > 0;
    }

    private static TimeSlot mapRow(ResultSet rs, int rowNum) throws SQLException {
        return new TimeSlot(
                rs.getString("id"),
                rs.getString("activity_plan_id"),
                rs.getString("day_of_week"),
                rs.getString("date"),
                rs.getString("start_time"),
                rs.getString("end_time"),
                NullableColumns.nullableInt(rs, "duration_minutes"),
                rs.getString("label"));
    }
}
