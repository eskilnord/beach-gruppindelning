package se.klubb.groupplanner.api;

import java.util.List;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;
import se.klubb.groupplanner.api.error.BadRequestException;
import se.klubb.groupplanner.api.error.NotFoundException;
import se.klubb.groupplanner.domain.Court;
import se.klubb.groupplanner.domain.TimeSlot;
import se.klubb.groupplanner.domain.TrainingBlock;
import se.klubb.groupplanner.repo.ActivityPlanRepository;
import se.klubb.groupplanner.repo.CourtRepository;
import se.klubb.groupplanner.repo.TimeSlotRepository;
import se.klubb.groupplanner.repo.TrainingBlockRepository;
import se.klubb.groupplanner.resources.TrainingBlockGenerationService;
import se.klubb.groupplanner.resources.TrainingBlockView;

/**
 * {@code TrainingBlock} generation (spec §12.2), manual exceptions (spec §12.3), and the plan-wide
 * grouped view.
 */
@RestController
public class TrainingBlockController {

    private final TrainingBlockGenerationService generationService;
    private final TrainingBlockRepository trainingBlockRepository;
    private final TimeSlotRepository timeSlotRepository;
    private final ActivityPlanRepository activityPlanRepository;
    private final CourtRepository courtRepository;

    public TrainingBlockController(
            TrainingBlockGenerationService generationService,
            TrainingBlockRepository trainingBlockRepository,
            TimeSlotRepository timeSlotRepository,
            ActivityPlanRepository activityPlanRepository,
            CourtRepository courtRepository) {
        this.generationService = generationService;
        this.trainingBlockRepository = trainingBlockRepository;
        this.timeSlotRepository = timeSlotRepository;
        this.activityPlanRepository = activityPlanRepository;
        this.courtRepository = courtRepository;
    }

    /**
     * Declares {@code count} courts available for one time slot and (re)generates its {@code
     * TrainingBlock}s (spec §12.2). Idempotent; see {@link TrainingBlockGenerationService} for the
     * identity/regeneration contract.
     */
    @PutMapping("/api/plans/{planId}/time-slots/{slotId}/courts")
    public List<TrainingBlockView> setCourts(
            @PathVariable String planId, @PathVariable String slotId, @RequestBody SetCourtsRequest request) {
        TimeSlot slot = requireSlotInPlan(planId, slotId);
        if (request == null || request.count() == null) {
            throw new BadRequestException("count is required");
        }
        List<TrainingBlockView> blocks = generationService.generateForSlot(slot, request.count());
        // M7 review fix M2: block availability changes what placements/probes are physically possible.
        activityPlanRepository.bumpRevision(planId);
        return blocks;
    }

    /**
     * Manual exception (spec §12.3): deactivate (or reactivate) one block without touching others.
     * {@code {active:false}} marks the block {@code MANUAL} so court-count regeneration never
     * auto-reactivates it; {@code {active:true}} clears the marker, returning the block to normal
     * generation management (M5 review fix).
     */
    @PatchMapping("/api/training-blocks/{id}")
    public TrainingBlockView updateActive(@PathVariable String id, @RequestBody UpdateTrainingBlockRequest request) {
        TrainingBlock existing = trainingBlockRepository.findById(id)
                .orElseThrow(() -> new NotFoundException("Training block not found: " + id));
        if (request == null || request.active() == null) {
            throw new BadRequestException("active is required");
        }
        if (request.active()) {
            trainingBlockRepository.reactivate(id);
        } else {
            trainingBlockRepository.deactivate(id, TrainingBlock.DEACTIVATION_MANUAL);
        }
        Court court = courtRepository.findById(existing.courtId())
                .orElseThrow(() -> new IllegalStateException("Court not found: " + existing.courtId()));
        TrainingBlock updated = trainingBlockRepository.findById(id)
                .orElseThrow(() -> new IllegalStateException("Training block vanished mid-update: " + id));
        activityPlanRepository.bumpRevision(existing.activityPlanId()); // M7 review fix M2.
        return TrainingBlockView.of(updated, court);
    }

    /** Grouped view: every time slot in the plan with its (sorted) blocks. Spec §12.2/UI resources view. */
    @GetMapping("/api/plans/{planId}/training-blocks")
    public List<SlotBlocksView> listForPlan(@PathVariable String planId) {
        requirePlanExists(planId);
        return timeSlotRepository.findByActivityPlanId(planId).stream()
                .map(slot -> new SlotBlocksView(slot, generationService.currentBlocksForSlot(slot.id())))
                .toList();
    }

    private TimeSlot requireSlotInPlan(String planId, String slotId) {
        requirePlanExists(planId);
        TimeSlot slot = timeSlotRepository.findById(slotId)
                .orElseThrow(() -> new NotFoundException("Time slot not found: " + slotId));
        if (!slot.activityPlanId().equals(planId)) {
            throw new NotFoundException("Time slot not found in plan " + planId + ": " + slotId);
        }
        return slot;
    }

    private void requirePlanExists(String planId) {
        if (activityPlanRepository.findById(planId).isEmpty()) {
            throw new NotFoundException("Activity plan not found: " + planId);
        }
    }

    public record SetCourtsRequest(Integer count) {
    }

    public record UpdateTrainingBlockRequest(Boolean active) {
    }

    public record SlotBlocksView(TimeSlot timeSlot, List<TrainingBlockView> blocks) {
    }
}
