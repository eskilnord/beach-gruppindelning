package se.klubb.groupplanner.api;

import com.fasterxml.jackson.databind.JsonNode;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.http.HttpStatus;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import se.klubb.groupplanner.api.error.BadRequestException;
import se.klubb.groupplanner.api.error.NotFoundException;
import se.klubb.groupplanner.domain.CoachProfile;
import se.klubb.groupplanner.domain.CoachTimeSlot;
import se.klubb.groupplanner.domain.Person;
import se.klubb.groupplanner.domain.TimeSlot;
import se.klubb.groupplanner.repo.ActivityPlanRepository;
import se.klubb.groupplanner.repo.CoachProfileRepository;
import se.klubb.groupplanner.repo.CoachTimeSlotRepository;
import se.klubb.groupplanner.repo.PersonRepository;
import se.klubb.groupplanner.repo.TimeSlotRepository;
import se.klubb.groupplanner.util.Uuid7;

/**
 * CRUD for {@code CoachProfile} (spec §13.1) nested under its {@code ActivityPlan}, plus tri-state
 * availability (spec §7.3 available/unavailable/preferred time slots, normalized into {@code
 * coach_time_slot}).
 *
 * <p>{@code POST} either links an existing {@link Person} (by {@code personId} — spec §13.2 "samma
 * person ska kunna vara både tränare och deltagare", so an existing participant can become a coach
 * too; their {@code canBeCoach} flag is flipped on if not already) or creates a new one inline
 * (spec §13.1 "lägga till tränare manuellt").
 */
@RestController
public class CoachController {

    private static final double LEVEL_MIN = 0.0;
    private static final double LEVEL_MAX = 1000.0;

    private final CoachProfileRepository coachProfileRepository;
    private final ActivityPlanRepository activityPlanRepository;
    private final PersonRepository personRepository;
    private final CoachTimeSlotRepository coachTimeSlotRepository;
    private final TimeSlotRepository timeSlotRepository;

    public CoachController(
            CoachProfileRepository coachProfileRepository,
            ActivityPlanRepository activityPlanRepository,
            PersonRepository personRepository,
            CoachTimeSlotRepository coachTimeSlotRepository,
            TimeSlotRepository timeSlotRepository) {
        this.coachProfileRepository = coachProfileRepository;
        this.activityPlanRepository = activityPlanRepository;
        this.personRepository = personRepository;
        this.coachTimeSlotRepository = coachTimeSlotRepository;
        this.timeSlotRepository = timeSlotRepository;
    }

    @GetMapping("/api/plans/{planId}/coaches")
    public List<CoachProfile> listForPlan(@PathVariable String planId) {
        requirePlanExists(planId);
        return coachProfileRepository.findByActivityPlanId(planId);
    }

    @GetMapping("/api/coaches/{id}")
    public CoachProfile get(@PathVariable String id) {
        return findOrThrow(id);
    }

    @PostMapping("/api/plans/{planId}/coaches")
    @ResponseStatus(HttpStatus.CREATED)
    @Transactional
    public CoachProfile create(@PathVariable String planId, @RequestBody CreateCoachRequest request) {
        requirePlanExists(planId);
        if (request == null) {
            throw new BadRequestException("Request body is required");
        }
        validateLevels(request.coachLevel(), request.canCoachMinLevel(), request.canCoachMaxLevel());
        validateMaxGroups(request.maxGroupsPerDay(), request.maxGroupsPerWeek());

        String personId = resolvePersonId(request);

        CoachProfile profile = new CoachProfile(
                Uuid7.generate(),
                personId,
                planId,
                request.coachLevel(),
                request.canCoachMinLevel(),
                request.canCoachMaxLevel(),
                request.maxGroupsPerDay(),
                request.maxGroupsPerWeek(),
                request.canAlsoTrainAsParticipant() != null && request.canAlsoTrainAsParticipant(),
                request.notes());
        CoachProfile created = coachProfileRepository.insert(profile);
        activityPlanRepository.bumpRevision(planId); // M7 review fix M2: coaches feed CoachSlot/coach-wish probes.
        return created;
    }

    private String resolvePersonId(CreateCoachRequest request) {
        if (request.personId() != null && !request.personId().isBlank()) {
            Person person = personRepository.findById(request.personId())
                    .orElseThrow(() -> new BadRequestException("Person not found: " + request.personId()));
            if (!person.canBeCoach()) {
                personRepository.update(new Person(
                        person.id(), person.firstName(), person.lastName(), person.displayName(),
                        person.email(), person.phone(), person.externalId(), person.canBeParticipant(),
                        true, person.notes(), person.createdAt(), Instant.now()));
            }
            return person.id();
        }
        if (request.firstName() == null || request.firstName().isBlank()
                || request.lastName() == null || request.lastName().isBlank()) {
            throw new BadRequestException("Either personId, or firstName+lastName for a new person, is required");
        }
        Instant now = Instant.now();
        Person created = personRepository.insert(new Person(
                Uuid7.generate(), request.firstName(), request.lastName(), null,
                request.email(), request.phone(), null, false, true, null, now, now));
        return created.id();
    }

    private static final Set<String> PATCHABLE_FIELDS = Set.of(
            "coachLevel", "canCoachMinLevel", "canCoachMaxLevel", "maxGroupsPerDay", "maxGroupsPerWeek",
            "canAlsoTrainAsParticipant", "notes");

    @PatchMapping("/api/coaches/{id}")
    public CoachProfile update(@PathVariable String id, @RequestBody(required = false) Map<String, JsonNode> body) {
        CoachProfile existing = findOrThrow(id);
        if (body == null || body.isEmpty()) {
            return existing;
        }
        for (String key : body.keySet()) {
            if (!PATCHABLE_FIELDS.contains(key)) {
                throw new BadRequestException("Unknown field: '" + key + "' - patchable fields are " + PATCHABLE_FIELDS);
            }
        }
        Double coachLevel = nullableDouble(body, "coachLevel", existing.coachLevel());
        Double canCoachMinLevel = nullableDouble(body, "canCoachMinLevel", existing.canCoachMinLevel());
        Double canCoachMaxLevel = nullableDouble(body, "canCoachMaxLevel", existing.canCoachMaxLevel());
        Integer maxGroupsPerDay = nullableInt(body, "maxGroupsPerDay", existing.maxGroupsPerDay());
        Integer maxGroupsPerWeek = nullableInt(body, "maxGroupsPerWeek", existing.maxGroupsPerWeek());
        validateLevels(coachLevel, canCoachMinLevel, canCoachMaxLevel);
        validateMaxGroups(maxGroupsPerDay, maxGroupsPerWeek);

        CoachProfile updated = new CoachProfile(
                existing.id(),
                existing.personId(),
                existing.activityPlanId(),
                coachLevel,
                canCoachMinLevel,
                canCoachMaxLevel,
                maxGroupsPerDay,
                maxGroupsPerWeek,
                requiredBoolean(body, "canAlsoTrainAsParticipant", existing.canAlsoTrainAsParticipant()),
                nullableString(body, "notes", existing.notes()));
        CoachProfile saved = coachProfileRepository.update(updated);
        activityPlanRepository.bumpRevision(existing.activityPlanId()); // M7 review fix M2.
        return saved;
    }

    @DeleteMapping("/api/coaches/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@PathVariable String id) {
        CoachProfile existing = findOrThrow(id);
        coachProfileRepository.deleteById(id);
        activityPlanRepository.bumpRevision(existing.activityPlanId()); // M7 review fix M2.
    }

    @GetMapping("/api/plans/{planId}/coaches/{id}/availability")
    public List<AvailabilityEntry> getAvailability(@PathVariable String planId, @PathVariable String id) {
        requireCoachInPlan(planId, id);
        return coachTimeSlotRepository.findByCoachProfileId(id).stream()
                .map(entry -> new AvailabilityEntry(entry.timeSlotId(), entry.kind()))
                .toList();
    }

    /**
     * Replaces the coach's full tri-state availability set (spec §13.1). A time slot omitted from
     * the request body is left neutral/unknown ({@code coach_time_slot} has no row for it) — not the
     * same as {@code UNAVAILABLE}.
     */
    @PutMapping("/api/plans/{planId}/coaches/{id}/availability")
    @Transactional
    public List<AvailabilityEntry> putAvailability(
            @PathVariable String planId, @PathVariable String id, @RequestBody(required = false) List<AvailabilityEntry> entries) {
        requireCoachInPlan(planId, id);
        List<AvailabilityEntry> safeEntries = entries == null ? List.of() : entries;
        Set<String> seenSlotIds = new HashSet<>();
        for (AvailabilityEntry entry : safeEntries) {
            if (entry.timeSlotId() == null || entry.timeSlotId().isBlank()) {
                throw new BadRequestException("timeSlotId is required for every availability entry");
            }
            if (!seenSlotIds.add(entry.timeSlotId())) {
                throw new BadRequestException("Duplicate timeSlotId in request: " + entry.timeSlotId());
            }
            if (!CoachTimeSlot.isValidKind(entry.kind())) {
                throw new BadRequestException(
                        "kind must be one of AVAILABLE, UNAVAILABLE, PREFERRED - was: " + entry.kind());
            }
            TimeSlot slot = timeSlotRepository.findById(entry.timeSlotId())
                    .orElseThrow(() -> new BadRequestException("Time slot not found: " + entry.timeSlotId()));
            if (!slot.activityPlanId().equals(planId)) {
                throw new BadRequestException("Time slot does not belong to plan " + planId + ": " + entry.timeSlotId());
            }
        }

        coachTimeSlotRepository.deleteByCoachProfileId(id);
        List<AvailabilityEntry> result = new ArrayList<>();
        for (AvailabilityEntry entry : safeEntries) {
            coachTimeSlotRepository.insert(new CoachTimeSlot(Uuid7.generate(), id, entry.timeSlotId(), entry.kind()));
            result.add(entry);
        }
        activityPlanRepository.bumpRevision(planId); // M7 review fix M2: availability drives coachAvailabilityHard.
        return result;
    }

    private void validateLevels(Double coachLevel, Double minLevel, Double maxLevel) {
        requireInRange(coachLevel, "coachLevel");
        requireInRange(minLevel, "canCoachMinLevel");
        requireInRange(maxLevel, "canCoachMaxLevel");
        if (minLevel != null && maxLevel != null && minLevel > maxLevel) {
            throw new BadRequestException("canCoachMinLevel must be <= canCoachMaxLevel");
        }
    }

    private void requireInRange(Double value, String fieldName) {
        if (value != null && (value < LEVEL_MIN || value > LEVEL_MAX)) {
            throw new BadRequestException(fieldName + " must be between " + LEVEL_MIN + " and " + LEVEL_MAX);
        }
    }

    private void validateMaxGroups(Integer maxGroupsPerDay, Integer maxGroupsPerWeek) {
        if (maxGroupsPerDay != null && maxGroupsPerDay < 1) {
            throw new BadRequestException("maxGroupsPerDay must be >= 1");
        }
        if (maxGroupsPerWeek != null && maxGroupsPerWeek < 1) {
            throw new BadRequestException("maxGroupsPerWeek must be >= 1");
        }
        if (maxGroupsPerDay != null && maxGroupsPerWeek != null && maxGroupsPerDay > maxGroupsPerWeek) {
            throw new BadRequestException("maxGroupsPerDay must be <= maxGroupsPerWeek");
        }
    }

    private void requirePlanExists(String planId) {
        if (activityPlanRepository.findById(planId).isEmpty()) {
            throw new NotFoundException("Activity plan not found: " + planId);
        }
    }

    private void requireCoachInPlan(String planId, String coachId) {
        requirePlanExists(planId);
        boolean belongsToPlan = coachProfileRepository.findById(coachId)
                .filter(c -> c.activityPlanId().equals(planId))
                .isPresent();
        if (!belongsToPlan) {
            throw new NotFoundException("Coach not found in plan " + planId + ": " + coachId);
        }
    }

    private CoachProfile findOrThrow(String id) {
        return coachProfileRepository.findById(id).orElseThrow(() -> new NotFoundException("Coach not found: " + id));
    }

    private static Double nullableDouble(Map<String, JsonNode> body, String field, Double current) {
        if (!body.containsKey(field)) {
            return current;
        }
        JsonNode node = body.get(field);
        if (node == null || node.isNull()) {
            return null;
        }
        if (!node.isNumber()) {
            throw new BadRequestException(field + " must be a number or null");
        }
        return node.asDouble();
    }

    private static Integer nullableInt(Map<String, JsonNode> body, String field, Integer current) {
        if (!body.containsKey(field)) {
            return current;
        }
        JsonNode node = body.get(field);
        if (node == null || node.isNull()) {
            return null;
        }
        if (!node.isIntegralNumber()) {
            throw new BadRequestException(field + " must be an integer or null");
        }
        return node.asInt();
    }

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

    private static boolean requiredBoolean(Map<String, JsonNode> body, String field, boolean current) {
        if (!body.containsKey(field)) {
            return current;
        }
        JsonNode node = body.get(field);
        if (node == null || node.isNull()) {
            throw new BadRequestException(field + " cannot be cleared (required field) - send false instead of null");
        }
        if (!node.isBoolean()) {
            throw new BadRequestException(field + " must be a boolean");
        }
        return node.asBoolean();
    }

    public record CreateCoachRequest(
            String personId,
            String firstName,
            String lastName,
            String email,
            String phone,
            Double coachLevel,
            Double canCoachMinLevel,
            Double canCoachMaxLevel,
            Integer maxGroupsPerDay,
            Integer maxGroupsPerWeek,
            Boolean canAlsoTrainAsParticipant,
            String notes) {
    }

    public record AvailabilityEntry(String timeSlotId, String kind) {
    }
}
