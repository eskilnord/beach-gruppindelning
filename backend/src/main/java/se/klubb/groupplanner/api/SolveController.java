package se.klubb.groupplanner.api;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;
import se.klubb.groupplanner.solver.run.SolveCoordinator;
import se.klubb.groupplanner.solver.run.SolveProfile;
import se.klubb.groupplanner.solver.run.SolveStatus;

/**
 * Solve lifecycle endpoints (docs/design/04-solver.md §14.2): start/status/cancel. The design's
 * {@code blocking} request field (§14.4 cross-plan checkboxes) is accepted but ignored this
 * milestone — {@code SavedPlanResourceUsage} is a placeholder until M6b/M8 wires real cross-plan
 * data (see {@code se.klubb.groupplanner.solver.domain.SavedPlanResourceUsage} javadoc).
 *
 * <p>Deviates from the design doc's illustrative "422 on invalid pins/weights": this codebase has no
 * 422 usage anywhere (every other validation failure is 400 via {@link
 * se.klubb.groupplanner.api.error.BadRequestException}, an M4 precedent - see
 * backend/docs/m4-notes.md's ConstraintWeightService note) - {@code SolverInputAssembler} throwing
 * {@code BadRequestException} on an invalid pin/weight naturally maps to 400 through the existing
 * {@code ApiExceptionHandler}, with zero special-casing needed here.
 */
@RestController
public class SolveController {

    private final SolveCoordinator solveCoordinator;

    public SolveController(SolveCoordinator solveCoordinator) {
        this.solveCoordinator = solveCoordinator;
    }

    @PostMapping("/api/plans/{planId}/solve")
    public ResponseEntity<StartSolveResponse> solve(@PathVariable String planId, @RequestBody(required = false) SolveRequest request) {
        SolveProfile profile = SolveProfile.fromString(request == null ? null : request.profile());
        String runId = solveCoordinator.startSolve(planId, profile);
        // Report the ACTUAL SolverManager status (SOLVING_SCHEDULED until a solver thread picks the
        // job up, then SOLVING_ACTIVE) rather than a hardcoded value (review fix 8).
        String status = solveCoordinator.status(planId).status();
        return ResponseEntity.status(HttpStatus.ACCEPTED).body(new StartSolveResponse(runId, status));
    }

    @GetMapping("/api/plans/{planId}/solve/status")
    public SolveStatus status(@PathVariable String planId) {
        return solveCoordinator.status(planId);
    }

    @PostMapping("/api/plans/{planId}/solve/cancel")
    public CancelSolveResponse cancel(@PathVariable String planId) {
        String finalRunId = solveCoordinator.cancelSolve(planId);
        return new CancelSolveResponse(finalRunId);
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record SolveRequest(String profile) {
    }

    public record StartSolveResponse(String runId, String status) {
    }

    public record CancelSolveResponse(String finalRunId) {
    }
}
