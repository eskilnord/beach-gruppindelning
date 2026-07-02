package se.klubb.groupplanner.api;

import java.util.List;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;
import se.klubb.groupplanner.api.error.NotFoundException;
import se.klubb.groupplanner.domain.OptimizationRun;
import se.klubb.groupplanner.repo.ActivityPlanRepository;
import se.klubb.groupplanner.repo.OptimizationRunRepository;

/**
 * {@code GET /api/plans/{planId}/runs} — read-only run history (docs/design/02-product-data-ui.md §6
 * "körhistorik", spec §19.9 "Senaste score"/"Resultatsammanfattning"). Same kind of frontend-M6b
 * gap-fill as {@link AssignmentController} (see its javadoc for the full pattern/reasoning):
 * {@code OptimizationRunRepository#findByActivityPlanId} already existed (startup recovery, tests)
 * but was never exposed over REST — without it, the Optimeringsvy has no way to learn a run's final
 * score/duration once {@code SolveCoordinator#onFinalBestSolution} clears the in-memory {@code
 * ProgressRegistry} entry (at which point {@code GET .../solve/status} reverts to an empty {@code
 * NOT_SOLVING} response with no score fields — verified by reading {@code SolveCoordinator#status}).
 *
 * <p>Most-recent-first (the repository's own {@code ORDER BY started_at DESC, id DESC}); the
 * Optimeringsvy's "last-run summary" is simply this list's first element. {@code resultSummaryJson}
 * is a small pre-built JSON blob ({@code {hard,medium,soft,feasible,unassignedCount}}, written by
 * {@code OptimizationRunService#finishRun} for both real solves and the GREEDY baseline) - parsed
 * client-side rather than re-shaped into a new DTO here, mirroring how every other thin listing
 * controller in this codebase (e.g. {@link GroupController#list}) returns the domain record as-is.
 */
@RestController
public class OptimizationRunController {

    private final ActivityPlanRepository activityPlanRepository;
    private final OptimizationRunRepository optimizationRunRepository;

    public OptimizationRunController(
            ActivityPlanRepository activityPlanRepository, OptimizationRunRepository optimizationRunRepository) {
        this.activityPlanRepository = activityPlanRepository;
        this.optimizationRunRepository = optimizationRunRepository;
    }

    @GetMapping("/api/plans/{planId}/runs")
    public List<OptimizationRun> list(@PathVariable String planId) {
        if (activityPlanRepository.findById(planId).isEmpty()) {
            throw new NotFoundException("Activity plan not found: " + planId);
        }
        return optimizationRunRepository.findByActivityPlanId(planId);
    }
}
