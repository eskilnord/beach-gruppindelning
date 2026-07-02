package se.klubb.groupplanner.api;

import com.fasterxml.jackson.databind.JsonNode;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import se.klubb.groupplanner.api.error.BadRequestException;
import se.klubb.groupplanner.api.error.ConflictException;
import se.klubb.groupplanner.api.error.NotFoundException;
import se.klubb.groupplanner.domain.TimeSlot;
import se.klubb.groupplanner.repo.ActivityPlanRepository;
import se.klubb.groupplanner.repo.TimeSlotRepository;
import se.klubb.groupplanner.resources.TimeSlotLabelFormatter;
import se.klubb.groupplanner.util.Uuid7;

/**
 * CRUD for {@code TimeSlot} (spec §6.7/§7.9/§12.1), nested under its {@code ActivityPlan}:
 * {@code /api/plans/{planId}/time-slots} for list/create, flat {@code /api/time-slots/{id}} for
 * get/patch/delete (existing codebase convention, see {@code ParticipantProfileController}).
 *
 * <p>Overlap between two slots (e.g. two slots both at Thursday 18.00-19.30 on different courts is
 * not even a thing — courts are declared via {@code TrainingBlock} generation, not on the slot
 * itself) is allowed; only an <em>exact</em> duplicate (same dayOfWeek/date + startTime + endTime)
 * is rejected, with 409, since it could never mean anything other than a duplicate data-entry.
 */
@RestController
public class TimeSlotController {

    private static final Set<String> PATCHABLE_FIELDS = Set.of("dayOfWeek", "date", "startTime", "endTime", "label");

    private final TimeSlotRepository timeSlotRepository;
    private final ActivityPlanRepository activityPlanRepository;
    private final TimeSlotLabelFormatter labelFormatter;

    public TimeSlotController(
            TimeSlotRepository timeSlotRepository,
            ActivityPlanRepository activityPlanRepository,
            TimeSlotLabelFormatter labelFormatter) {
        this.timeSlotRepository = timeSlotRepository;
        this.activityPlanRepository = activityPlanRepository;
        this.labelFormatter = labelFormatter;
    }

    @GetMapping("/api/plans/{planId}/time-slots")
    public List<TimeSlot> listForPlan(@PathVariable String planId) {
        requirePlanExists(planId);
        return timeSlotRepository.findByActivityPlanId(planId);
    }

    @PostMapping("/api/plans/{planId}/time-slots")
    @ResponseStatus(HttpStatus.CREATED)
    public TimeSlot create(@PathVariable String planId, @RequestBody CreateTimeSlotRequest request) {
        requirePlanExists(planId);
        if (request == null) {
            throw new BadRequestException("Request body is required");
        }
        String dayOfWeek = normalizeDayOfWeek(request.dayOfWeek());
        String date = normalizeDate(request.date());
        requireExactlyOneOf(dayOfWeek, date);

        LocalTime start = labelFormatter.parseTime(request.startTime(), "startTime");
        LocalTime end = labelFormatter.parseTime(request.endTime(), "endTime");
        int duration = labelFormatter.durationMinutes(start, end);
        String startTime = labelFormatter.normalize(start);
        String endTime = labelFormatter.normalize(end);

        requireNoDuplicate(planId, dayOfWeek, date, startTime, endTime, null);

        String label = (request.label() == null || request.label().isBlank())
                ? labelFormatter.autoLabel(dayOfWeek, date, start, end)
                : request.label();

        TimeSlot slot = new TimeSlot(Uuid7.generate(), planId, dayOfWeek, date, startTime, endTime, duration, label);
        TimeSlot created = timeSlotRepository.insert(slot);
        activityPlanRepository.bumpRevision(planId); // M7 review fix M2: slots define the placement space.
        return created;
    }

    @GetMapping("/api/time-slots/{id}")
    public TimeSlot get(@PathVariable String id) {
        return findOrThrow(id);
    }

    @PatchMapping("/api/time-slots/{id}")
    public TimeSlot update(@PathVariable String id, @RequestBody(required = false) Map<String, JsonNode> body) {
        TimeSlot existing = findOrThrow(id);
        if (body == null || body.isEmpty()) {
            return existing;
        }
        for (String key : body.keySet()) {
            if (!PATCHABLE_FIELDS.contains(key)) {
                throw new BadRequestException("Unknown field: '" + key + "' - patchable fields are " + PATCHABLE_FIELDS);
            }
        }

        String dayOfWeek = normalizeDayOfWeek(nullableString(body, "dayOfWeek", existing.dayOfWeek()));
        String date = normalizeDate(nullableString(body, "date", existing.date()));
        requireExactlyOneOf(dayOfWeek, date);

        String startTimeRaw = nullableString(body, "startTime", existing.startTime());
        String endTimeRaw = nullableString(body, "endTime", existing.endTime());
        LocalTime start = labelFormatter.parseTime(startTimeRaw, "startTime");
        LocalTime end = labelFormatter.parseTime(endTimeRaw, "endTime");
        int duration = labelFormatter.durationMinutes(start, end);
        String startTime = labelFormatter.normalize(start);
        String endTime = labelFormatter.normalize(end);

        requireNoDuplicate(existing.activityPlanId(), dayOfWeek, date, startTime, endTime, id);

        String label;
        if (body.containsKey("label")) {
            String requested = nullableString(body, "label", null);
            label = (requested == null || requested.isBlank())
                    ? labelFormatter.autoLabel(dayOfWeek, date, start, end)
                    : requested;
        } else if (isAutoGeneratedLabel(existing)) {
            // The label was never customized (it still matches what auto-generation would have
            // produced for the OLD day/time) -> keep it in sync when day/time change via PATCH.
            label = labelFormatter.autoLabel(dayOfWeek, date, start, end);
        } else {
            label = existing.label();
        }

        TimeSlot updated = new TimeSlot(existing.id(), existing.activityPlanId(), dayOfWeek, date, startTime, endTime, duration, label);
        TimeSlot saved = timeSlotRepository.update(updated);
        activityPlanRepository.bumpRevision(existing.activityPlanId()); // M7 review fix M2.
        return saved;
    }

    @DeleteMapping("/api/time-slots/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@PathVariable String id) {
        TimeSlot existing = findOrThrow(id);
        timeSlotRepository.deleteById(id);
        activityPlanRepository.bumpRevision(existing.activityPlanId()); // M7 review fix M2.
    }

    private boolean isAutoGeneratedLabel(TimeSlot slot) {
        if (slot.label() == null || slot.label().isBlank()) {
            return true;
        }
        String currentAutoLabel = labelFormatter.autoLabel(
                slot.dayOfWeek(), slot.date(),
                labelFormatter.parseTime(slot.startTime(), "startTime"),
                labelFormatter.parseTime(slot.endTime(), "endTime"));
        return slot.label().equals(currentAutoLabel);
    }

    /**
     * Exactly one of {@code dayOfWeek} (recurring weekly slot) / {@code date} (one-off dated slot)
     * must be set (M5 review fix): both at once is ambiguous - which one wins if the date's actual
     * weekday contradicts dayOfWeek? - so it is rejected outright rather than silently prioritized.
     */
    private void requireExactlyOneOf(String dayOfWeek, String date) {
        if (dayOfWeek == null && date == null) {
            throw new BadRequestException("One of dayOfWeek or date is required");
        }
        if (dayOfWeek != null && date != null) {
            throw new BadRequestException(
                    "dayOfWeek and date cannot both be set - use dayOfWeek for a recurring weekly slot"
                            + " or date for a one-off dated slot (its weekday is derived from the date)");
        }
    }

    private void requireNoDuplicate(String planId, String dayOfWeek, String date, String startTime, String endTime, String excludeId) {
        if (timeSlotRepository.findDuplicate(planId, dayOfWeek, date, startTime, endTime, excludeId).isPresent()) {
            throw new ConflictException(
                    "A time slot with the same day/date and start/end time already exists in this plan");
        }
    }

    private String normalizeDayOfWeek(String dayOfWeek) {
        if (dayOfWeek == null || dayOfWeek.isBlank()) {
            return null;
        }
        return labelFormatter.parseDayOfWeek(dayOfWeek.toUpperCase(java.util.Locale.ROOT)).name();
    }

    private String normalizeDate(String date) {
        if (date == null || date.isBlank()) {
            return null;
        }
        LocalDate parsed = labelFormatter.parseDate(date);
        return parsed.toString();
    }

    private void requirePlanExists(String planId) {
        if (activityPlanRepository.findById(planId).isEmpty()) {
            throw new NotFoundException("Activity plan not found: " + planId);
        }
    }

    private TimeSlot findOrThrow(String id) {
        return timeSlotRepository.findById(id).orElseThrow(() -> new NotFoundException("Time slot not found: " + id));
    }

    /** Absent key -&gt; keep {@code current}; JSON {@code null} -&gt; clear; otherwise parsed as text. */
    private static String nullableString(Map<String, JsonNode> body, String field, String current) {
        if (!body.containsKey(field)) {
            return current;
        }
        JsonNode node = body.get(field);
        if (node == null || node.isNull()) {
            return null;
        }
        if (!node.isTextual()) {
            throw new BadRequestException(field + " must be a string or null");
        }
        return node.asText();
    }

    public record CreateTimeSlotRequest(String dayOfWeek, String date, String startTime, String endTime, String label) {
    }
}
