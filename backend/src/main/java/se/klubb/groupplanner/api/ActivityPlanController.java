package se.klubb.groupplanner.api;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
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
import se.klubb.groupplanner.solver.assemble.GroupGenerator;
import se.klubb.groupplanner.util.Uuid7;

/**
 * CRUD for {@code ActivityPlan} (spec §7.6), nested under its {@code SeasonPlan}
 * (docs/design/02-product-data-ui.md §8: {@code /api/seasons/{id}/plans}, {@code /api/plans/{id}}).
 */
@RestController
public class ActivityPlanController {

    private static final String DEFAULT_STATUS = "draft";
    // Same 0-1000 level scale as LevelService/CoachController (LEVEL_MIN/LEVEL_MAX there) - the
    // plan-level "min-nivå" default is just a floor on that same scale, not a separate unit.
    private static final double LEVEL_MIN = 0.0;
    private static final double LEVEL_MAX = 1000.0;

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
        requireValidDefaults(
                request.defaultGroupTargetSize(), request.defaultGroupMinSize(), request.defaultGroupMaxSize(),
                request.defaultLevelMin());
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
                request.defaultLevelMin(),
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
        Integer mergedTarget = resolvePatch(request.defaultGroupTargetSize(), existing.defaultGroupTargetSize());
        Integer mergedMin = resolvePatch(request.defaultGroupMinSize(), existing.defaultGroupMinSize());
        Integer mergedMax = resolvePatch(request.defaultGroupMaxSize(), existing.defaultGroupMaxSize());
        Double mergedLevelMin = resolvePatch(request.defaultLevelMin(), existing.defaultLevelMin());
        requireValidDefaults(mergedTarget, mergedMin, mergedMax, mergedLevelMin);
        ActivityPlan updated = new ActivityPlan(
                existing.id(),
                existing.seasonPlanId(),
                request.name() != null ? request.name() : existing.name(),
                request.category() != null ? request.category() : existing.category(),
                request.status() != null ? request.status() : existing.status(),
                mergedTarget,
                mergedMin,
                mergedMax,
                mergedLevelMin,
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

    /**
     * Three-state PATCH semantics for the group-default fields (v0.3.0 review fix - without this,
     * a default could be set but never CLEARED): {@code null} means the property was absent from
     * the JSON (keep the existing value), {@code Optional.empty()} an explicit JSON {@code null}
     * (clear to unset), {@code Optional.of(v)} a value (set). See {@link UpdateActivityPlanRequest}
     * for how the three states are captured; behavior is pinned by
     * ActivityPlanControllerTest#patchDistinguishesAbsentNullAndValueForDefaults.
     */
    private static <T> T resolvePatch(Optional<T> patch, T existing) {
        return patch == null ? existing : patch.orElse(null);
    }

    /**
     * Sanity checks for the plan's "Standardvärden för grupper" (group-generation defaults, spec
     * §7.6/GroupGenerator): each size must be a positive count, and the EFFECTIVE post-fallback
     * triple ({@link GroupGenerator#effectiveSizes}: unset target -&gt; 10, max -&gt; target+2, min
     * -&gt; max(1, target-2) - exactly what "Generera grupper" will use) must satisfy min &lt;=
     * target &lt;= max. Validating the effective values rather than only the explicitly-set pairs
     * catches partially-set contradictions like "only min=20" (effective 20/10/12) that would
     * otherwise produce groups whose minSize exceeds their maxSize (v0.3.0 review fix). {@code
     * defaultLevelMin} must fall on the same 0-1000 level scale as {@code estimatedLevel}/ranking
     * (LevelService) - mirrors CoachController's canCoachMinLevel/canCoachMaxLevel range check.
     */
    private void requireValidDefaults(Integer target, Integer min, Integer max, Double levelMin) {
        if (target != null && target < 1) {
            throw new BadRequestException("defaultGroupTargetSize must be >= 1");
        }
        if (min != null && min < 1) {
            throw new BadRequestException("defaultGroupMinSize must be >= 1");
        }
        if (max != null && max < 1) {
            throw new BadRequestException("defaultGroupMaxSize must be >= 1");
        }
        GroupGenerator.EffectiveSizes effective = GroupGenerator.effectiveSizes(target, min, max);
        if (effective.min() > effective.target() || effective.target() > effective.max()) {
            throw new BadRequestException("group size defaults are contradictory once fallbacks are applied: effective"
                    + " min=" + effective.min() + ", target=" + effective.target() + ", max=" + effective.max()
                    + " must satisfy min <= target <= max (unset fields fall back to: target=10, max=target+2, min=target-2)");
        }
        if (levelMin != null && (levelMin < LEVEL_MIN || levelMin > LEVEL_MAX)) {
            throw new BadRequestException("defaultLevelMin must be between " + LEVEL_MIN + " and " + LEVEL_MAX);
        }
    }

    public record CreateActivityPlanRequest(
            String name,
            String category,
            String status,
            Integer defaultGroupTargetSize,
            Integer defaultGroupMinSize,
            Integer defaultGroupMaxSize,
            Double defaultLevelMin) {
    }

    /**
     * Three-state PATCH body (see {@link #resolvePatch}): each Optional field is {@code null} when
     * the property was ABSENT from the JSON (keep the existing value), {@code Optional.empty()} for
     * an explicit JSON {@code null} (clear back to unset), {@code Optional.of(v)} for a value (set).
     *
     * <p>A mutable bean with presence-tracking setters rather than a record like every other request
     * DTO in this codebase, deliberately: Jackson (2.21 + jdk8 module) deserializes an ABSENT {@code
     * Optional} record component as {@code Optional.empty()} - indistinguishable from an explicit
     * null (verified empirically; this class started as a record and the three-state controller test
     * caught it). Bean setters, by contrast, are only invoked for properties actually present in the
     * JSON, so a field left {@code null} reliably means "absent". Record-style accessors keep the
     * controller call sites uniform with the other DTOs. The non-default fields keep the plain
     * "null/absent means keep" convention - clearing a name/category/status to null is not a
     * meaningful operation for this app.
     */
    public static final class UpdateActivityPlanRequest {
        private String name;
        private String category;
        private String status;
        private Optional<Integer> defaultGroupTargetSize;
        private Optional<Integer> defaultGroupMinSize;
        private Optional<Integer> defaultGroupMaxSize;
        private Optional<Double> defaultLevelMin;

        public void setName(String name) {
            this.name = name;
        }

        public void setCategory(String category) {
            this.category = category;
        }

        public void setStatus(String status) {
            this.status = status;
        }

        public void setDefaultGroupTargetSize(Integer value) {
            this.defaultGroupTargetSize = Optional.ofNullable(value);
        }

        public void setDefaultGroupMinSize(Integer value) {
            this.defaultGroupMinSize = Optional.ofNullable(value);
        }

        public void setDefaultGroupMaxSize(Integer value) {
            this.defaultGroupMaxSize = Optional.ofNullable(value);
        }

        public void setDefaultLevelMin(Double value) {
            this.defaultLevelMin = Optional.ofNullable(value);
        }

        public String name() {
            return name;
        }

        public String category() {
            return category;
        }

        public String status() {
            return status;
        }

        public Optional<Integer> defaultGroupTargetSize() {
            return defaultGroupTargetSize;
        }

        public Optional<Integer> defaultGroupMinSize() {
            return defaultGroupMinSize;
        }

        public Optional<Integer> defaultGroupMaxSize() {
            return defaultGroupMaxSize;
        }

        public Optional<Double> defaultLevelMin() {
            return defaultLevelMin;
        }
    }
}
