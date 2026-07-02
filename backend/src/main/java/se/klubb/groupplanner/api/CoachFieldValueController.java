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
import se.klubb.groupplanner.repo.CoachProfileRepository;

/**
 * Bulk read/write of a coach's {@code CUSTOM}-storage field values (spec §7.14) — {@code GET|PUT
 * /api/plans/{planId}/coaches/{cid}/field-values}. The coach-scoped counterpart to {@code
 * ParticipantFieldValueController} promised by {@link FieldValueService}'s M4 Javadoc ("the same
 * service backs the coach-scoped endpoint arriving in M5") — delegates all type validation there.
 */
@RestController
public class CoachFieldValueController {

    private final FieldValueService fieldValueService;
    private final ActivityPlanRepository activityPlanRepository;
    private final CoachProfileRepository coachProfileRepository;

    public CoachFieldValueController(
            FieldValueService fieldValueService,
            ActivityPlanRepository activityPlanRepository,
            CoachProfileRepository coachProfileRepository) {
        this.fieldValueService = fieldValueService;
        this.activityPlanRepository = activityPlanRepository;
        this.coachProfileRepository = coachProfileRepository;
    }

    @GetMapping("/api/plans/{planId}/coaches/{cid}/field-values")
    public List<FieldValueView> get(@PathVariable String planId, @PathVariable String cid) {
        requireCoachInPlan(planId, cid);
        return fieldValueService.getValues(planId, CustomFieldValue.ENTITY_TYPE_COACH, cid);
    }

    @PutMapping("/api/plans/{planId}/coaches/{cid}/field-values")
    public List<FieldValueView> put(
            @PathVariable String planId, @PathVariable String cid, @RequestBody Map<String, JsonNode> values) {
        requireCoachInPlan(planId, cid);
        List<FieldValueView> result = fieldValueService.putValues(planId, CustomFieldValue.ENTITY_TYPE_COACH, cid, values);
        activityPlanRepository.bumpRevision(planId); // M7 review fix M2: coach field values feed solver facts.
        return result;
    }

    private void requireCoachInPlan(String planId, String cid) {
        if (activityPlanRepository.findById(planId).isEmpty()) {
            throw new NotFoundException("Activity plan not found: " + planId);
        }
        boolean belongsToPlan = coachProfileRepository.findById(cid)
                .filter(c -> c.activityPlanId().equals(planId))
                .isPresent();
        if (!belongsToPlan) {
            throw new NotFoundException("Coach not found in plan " + planId + ": " + cid);
        }
    }
}
