package se.klubb.groupplanner.resources;

import java.time.LocalTime;
import java.util.ArrayList;
import java.util.List;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import se.klubb.groupplanner.domain.TimeSlot;
import se.klubb.groupplanner.repo.TimeSlotRepository;
import se.klubb.groupplanner.util.Uuid7;

/**
 * Seeds three default weekly Thursday {@link TimeSlot}s (18.00-19.30, 19.30-21.00, 21.00-22.30) for
 * a freshly created {@code ActivityPlan} (v0.6.0 B3) — most clubs' training week starts from exactly
 * this shape, so a brand-new plan no longer opens to an empty schedule the user must build from
 * scratch. Called from {@code ActivityPlanController.create}, same transaction as the plan insert.
 *
 * <p>Idempotent (M-style "safe to call repeatedly" convention, like {@link DefaultVenueService}): if
 * the plan already has ANY time slot — seeded by a prior call, or created directly via {@code
 * POST /api/plans/{planId}/time-slots} before this ran — nothing is touched and an empty list is
 * returned. Only {@code TimeSlot} rows are created; no {@code TrainingBlock}/court is generated,
 * since court counts are a per-slot decision the user makes explicitly (spec §12.2).
 */
@Service
public class DefaultTimeSlotService {

    private record DefaultSlot(String startTime, String endTime) {
    }

    private static final String DEFAULT_DAY_OF_WEEK = "THURSDAY";
    private static final List<DefaultSlot> DEFAULT_SLOTS = List.of(
            new DefaultSlot("18:00", "19:30"),
            new DefaultSlot("19:30", "21:00"),
            new DefaultSlot("21:00", "22:30"));

    private final TimeSlotRepository timeSlotRepository;
    private final TimeSlotLabelFormatter labelFormatter;

    public DefaultTimeSlotService(TimeSlotRepository timeSlotRepository, TimeSlotLabelFormatter labelFormatter) {
        this.timeSlotRepository = timeSlotRepository;
        this.labelFormatter = labelFormatter;
    }

    @Transactional
    public List<TimeSlot> seedDefaults(String planId) {
        if (!timeSlotRepository.findByActivityPlanId(planId).isEmpty()) {
            return List.of();
        }
        List<TimeSlot> created = new ArrayList<>();
        for (DefaultSlot defaultSlot : DEFAULT_SLOTS) {
            LocalTime start = labelFormatter.parseTime(defaultSlot.startTime(), "startTime");
            LocalTime end = labelFormatter.parseTime(defaultSlot.endTime(), "endTime");
            int duration = labelFormatter.durationMinutes(start, end);
            String startTime = labelFormatter.normalize(start);
            String endTime = labelFormatter.normalize(end);
            String label = labelFormatter.autoLabel(DEFAULT_DAY_OF_WEEK, null, start, end);
            TimeSlot slot = new TimeSlot(
                    Uuid7.generate(), planId, DEFAULT_DAY_OF_WEEK, null, startTime, endTime, duration, label);
            created.add(timeSlotRepository.insert(slot));
        }
        return created;
    }
}
