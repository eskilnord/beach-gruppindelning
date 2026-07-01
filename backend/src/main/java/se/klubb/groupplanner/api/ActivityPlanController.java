package se.klubb.groupplanner.api;

import java.time.Instant;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import se.klubb.groupplanner.api.error.BadRequestException;
import se.klubb.groupplanner.api.error.NotFoundException;
import se.klubb.groupplanner.domain.ActivityPlan;
import se.klubb.groupplanner.repo.ActivityPlanRepository;
import se.klubb.groupplanner.repo.SeasonPlanRepository;
import se.klubb.groupplanner.util.Uuid7;

/**
 * CRUD for {@code ActivityPlan} (spec §7.6), nested under its {@code SeasonPlan}
 * (docs/design/02-product-data-ui.md §8: {@code /api/seasons/{id}/plans}, {@code /api/plans/{id}}).
 */
@RestController
public class ActivityPlanController {

    private static final String DEFAULT_STATUS = "draft";

    private final ActivityPlanRepository activityPlanRepository;
    private final SeasonPlanRepository seasonPlanRepository;

    public ActivityPlanController(ActivityPlanRepository activityPlanRepository, SeasonPlanRepository seasonPlanRepository) {
        this.activityPlanRepository = activityPlanRepository;
        this.seasonPlanRepository = seasonPlanRepository;
    }

    @GetMapping("/api/seasons/{seasonId}/plans")
    public List<ActivityPlan> listForSeason(@PathVariable String seasonId) {
        requireSeasonExists(seasonId);
        return activityPlanRepository.findBySeasonPlanId(seasonId);
    }

    @PostMapping("/api/seasons/{seasonId}/plans")
    @ResponseStatus(HttpStatus.CREATED)
    public ActivityPlan create(@PathVariable String seasonId, @RequestBody CreateActivityPlanRequest request) {
        requireSeasonExists(seasonId);
        if (request == null || request.name() == null || request.name().isBlank()) {
            throw new BadRequestException("name is required");
        }
        Instant now = Instant.now();
        ActivityPlan plan = new ActivityPlan(
                Uuid7.generate(),
                seasonId,
                request.name(),
                request.category(),
                request.status() == null || request.status().isBlank() ? DEFAULT_STATUS : request.status(),
                request.defaultGroupTargetSize(),
                request.defaultGroupMinSize(),
                request.defaultGroupMaxSize(),
                now,
                now);
        return activityPlanRepository.insert(plan);
    }

    @GetMapping("/api/plans/{id}")
    public ActivityPlan get(@PathVariable String id) {
        return findOrThrow(id);
    }

    @PatchMapping("/api/plans/{id}")
    public ActivityPlan update(@PathVariable String id, @RequestBody UpdateActivityPlanRequest request) {
        ActivityPlan existing = findOrThrow(id);
        if (request == null) {
            return existing;
        }
        ActivityPlan updated = new ActivityPlan(
                existing.id(),
                existing.seasonPlanId(),
                request.name() != null ? request.name() : existing.name(),
                request.category() != null ? request.category() : existing.category(),
                request.status() != null ? request.status() : existing.status(),
                request.defaultGroupTargetSize() != null ? request.defaultGroupTargetSize() : existing.defaultGroupTargetSize(),
                request.defaultGroupMinSize() != null ? request.defaultGroupMinSize() : existing.defaultGroupMinSize(),
                request.defaultGroupMaxSize() != null ? request.defaultGroupMaxSize() : existing.defaultGroupMaxSize(),
                existing.createdAt(),
                Instant.now());
        return activityPlanRepository.update(updated);
    }

    @DeleteMapping("/api/plans/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@PathVariable String id) {
        findOrThrow(id);
        activityPlanRepository.deleteById(id);
    }

    private void requireSeasonExists(String seasonId) {
        if (seasonPlanRepository.findById(seasonId).isEmpty()) {
            throw new NotFoundException("Season plan not found: " + seasonId);
        }
    }

    private ActivityPlan findOrThrow(String id) {
        return activityPlanRepository.findById(id)
                .orElseThrow(() -> new NotFoundException("Activity plan not found: " + id));
    }

    public record CreateActivityPlanRequest(
            String name,
            String category,
            String status,
            Integer defaultGroupTargetSize,
            Integer defaultGroupMinSize,
            Integer defaultGroupMaxSize) {
    }

    public record UpdateActivityPlanRequest(
            String name,
            String category,
            String status,
            Integer defaultGroupTargetSize,
            Integer defaultGroupMinSize,
            Integer defaultGroupMaxSize) {
    }
}
