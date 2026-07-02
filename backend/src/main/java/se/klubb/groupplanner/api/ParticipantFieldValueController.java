package se.klubb.groupplanner.api;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.List;
import java.util.Map;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;
import se.klubb.groupplanner.api.error.NotFoundException;
import se.klubb.groupplanner.domain.CustomFieldValue;
import se.klubb.groupplanner.fields.FieldValueService;
import se.klubb.groupplanner.fields.FieldValueView;
import se.klubb.groupplanner.repo.ActivityPlanRepository;
import se.klubb.groupplanner.repo.ParticipantProfileRepository;

/**
 * Bulk read/write of a participant's {@code CUSTOM}-storage field values (spec §7.14, §9.1
 * structured-entry drawer) — {@code GET|PUT /api/plans/{planId}/participants/{pid}/field-values}.
 * Delegates all type validation to {@link FieldValueService}, which is generic over {@code
 * entityType} so the same service backs the coach-scoped endpoint arriving in M5.
 *
 * <p>M7: {@code PUT} bumps {@code activity_plan.plan_revision} — this is exactly where wishes/
 * priority/time-availability edits land (docs/design/04-solver.md §11.6's "participant edits"
 * invalidation trigger), which feed the solver's constraints directly.
 */
@RestController
public class ParticipantFieldValueController {

    private final FieldValueService fieldValueService;
    private final ActivityPlanRepository activityPlanRepository;
    private final ParticipantProfileRepository participantProfileRepository;

    public ParticipantFieldValueController(
            FieldValueService fieldValueService,
            ActivityPlanRepository activityPlanRepository,
            ParticipantProfileRepository participantProfileRepository) {
        this.fieldValueService = fieldValueService;
        this.activityPlanRepository = activityPlanRepository;
        this.participantProfileRepository = participantProfileRepository;
    }

    @GetMapping("/api/plans/{planId}/participants/{pid}/field-values")
    public List<FieldValueView> get(@PathVariable String planId, @PathVariable String pid) {
        requireParticipantInPlan(planId, pid);
        return fieldValueService.getValues(planId, CustomFieldValue.ENTITY_TYPE_PARTICIPANT, pid);
    }

    @PutMapping("/api/plans/{planId}/participants/{pid}/field-values")
    public List<FieldValueView> put(
            @PathVariable String planId, @PathVariable String pid, @RequestBody Map<String, JsonNode> values) {
        requireParticipantInPlan(planId, pid);
        List<FieldValueView> result = fieldValueService.putValues(planId, CustomFieldValue.ENTITY_TYPE_PARTICIPANT, pid, values);
        activityPlanRepository.bumpRevision(planId);
        return result;
    }

    private void requireParticipantInPlan(String planId, String pid) {
        if (activityPlanRepository.findById(planId).isEmpty()) {
            throw new NotFoundException("Activity plan not found: " + planId);
        }
        boolean belongsToPlan = participantProfileRepository.findById(pid)
                .filter(p -> p.activityPlanId().equals(planId))
                .isPresent();
        if (!belongsToPlan) {
            throw new NotFoundException("Participant not found in plan " + planId + ": " + pid);
        }
    }
}
