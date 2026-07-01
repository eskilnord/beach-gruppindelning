package se.klubb.groupplanner.api;

import java.util.List;
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
import se.klubb.groupplanner.api.error.NotFoundException;
import se.klubb.groupplanner.domain.ParticipantProfile;
import se.klubb.groupplanner.repo.ActivityPlanRepository;
import se.klubb.groupplanner.repo.ParticipantProfileRepository;
import se.klubb.groupplanner.repo.PersonRepository;
import se.klubb.groupplanner.util.Uuid7;

/**
 * CRUD for {@code ParticipantProfile} (spec §7.2), nested under its {@code ActivityPlan}
 * (docs/design/02-product-data-ui.md §8: {@code /api/plans/{planId}/participants}).
 */
@RestController
public class ParticipantProfileController {

    private final ParticipantProfileRepository participantProfileRepository;
    private final ActivityPlanRepository activityPlanRepository;
    private final PersonRepository personRepository;

    public ParticipantProfileController(
            ParticipantProfileRepository participantProfileRepository,
            ActivityPlanRepository activityPlanRepository,
            PersonRepository personRepository) {
        this.participantProfileRepository = participantProfileRepository;
        this.activityPlanRepository = activityPlanRepository;
        this.personRepository = personRepository;
    }

    @GetMapping("/api/plans/{planId}/participants")
    public List<ParticipantProfile> listForPlan(@PathVariable String planId) {
        requirePlanExists(planId);
        return participantProfileRepository.findByActivityPlanId(planId);
    }

    @PostMapping("/api/plans/{planId}/participants")
    @ResponseStatus(HttpStatus.CREATED)
    public ParticipantProfile create(@PathVariable String planId, @RequestBody CreateParticipantProfileRequest request) {
        requirePlanExists(planId);
        if (request == null || request.personId() == null || request.personId().isBlank()) {
            throw new BadRequestException("personId is required");
        }
        if (personRepository.findById(request.personId()).isEmpty()) {
            throw new BadRequestException("Person not found: " + request.personId());
        }
        ParticipantProfile profile = new ParticipantProfile(
                Uuid7.generate(),
                request.personId(),
                planId,
                request.rankingPoints(),
                request.rankingSource(),
                request.previousGroupName(),
                request.previousGroupLevel(),
                request.estimatedLevel(),
                request.levelConfidence(),
                request.manualLevelScore(),
                request.importedComment(),
                request.internalNote(),
                request.manualReviewFlag() != null && request.manualReviewFlag(),
                request.waitlisted() != null && request.waitlisted());
        return participantProfileRepository.insert(profile);
    }

    @GetMapping("/api/participants/{id}")
    public ParticipantProfile get(@PathVariable String id) {
        return findOrThrow(id);
    }

    @PatchMapping("/api/participants/{id}")
    public ParticipantProfile update(@PathVariable String id, @RequestBody UpdateParticipantProfileRequest request) {
        ParticipantProfile existing = findOrThrow(id);
        if (request == null) {
            return existing;
        }
        ParticipantProfile updated = new ParticipantProfile(
                existing.id(),
                existing.personId(),
                existing.activityPlanId(),
                request.rankingPoints() != null ? request.rankingPoints() : existing.rankingPoints(),
                request.rankingSource() != null ? request.rankingSource() : existing.rankingSource(),
                request.previousGroupName() != null ? request.previousGroupName() : existing.previousGroupName(),
                request.previousGroupLevel() != null ? request.previousGroupLevel() : existing.previousGroupLevel(),
                request.estimatedLevel() != null ? request.estimatedLevel() : existing.estimatedLevel(),
                request.levelConfidence() != null ? request.levelConfidence() : existing.levelConfidence(),
                request.manualLevelScore() != null ? request.manualLevelScore() : existing.manualLevelScore(),
                request.importedComment() != null ? request.importedComment() : existing.importedComment(),
                request.internalNote() != null ? request.internalNote() : existing.internalNote(),
                request.manualReviewFlag() != null ? request.manualReviewFlag() : existing.manualReviewFlag(),
                request.waitlisted() != null ? request.waitlisted() : existing.waitlisted());
        return participantProfileRepository.update(updated);
    }

    @DeleteMapping("/api/participants/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@PathVariable String id) {
        findOrThrow(id);
        participantProfileRepository.deleteById(id);
    }

    private void requirePlanExists(String planId) {
        if (activityPlanRepository.findById(planId).isEmpty()) {
            throw new NotFoundException("Activity plan not found: " + planId);
        }
    }

    private ParticipantProfile findOrThrow(String id) {
        return participantProfileRepository.findById(id)
                .orElseThrow(() -> new NotFoundException("Participant profile not found: " + id));
    }

    public record CreateParticipantProfileRequest(
            String personId,
            Double rankingPoints,
            String rankingSource,
            String previousGroupName,
            Double previousGroupLevel,
            Double estimatedLevel,
            Double levelConfidence,
            Double manualLevelScore,
            String importedComment,
            String internalNote,
            Boolean manualReviewFlag,
            Boolean waitlisted) {
    }

    public record UpdateParticipantProfileRequest(
            Double rankingPoints,
            String rankingSource,
            String previousGroupName,
            Double previousGroupLevel,
            Double estimatedLevel,
            Double levelConfidence,
            Double manualLevelScore,
            String importedComment,
            String internalNote,
            Boolean manualReviewFlag,
            Boolean waitlisted) {
    }
}
