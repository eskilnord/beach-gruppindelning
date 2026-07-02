package se.klubb.groupplanner.api;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;
import se.klubb.groupplanner.solver.assemble.BlockingOptions;
import se.klubb.groupplanner.solver.assemble.OptimizeSelection;
import se.klubb.groupplanner.solver.run.SolveCoordinator;
import se.klubb.groupplanner.solver.run.SolveProfile;
import se.klubb.groupplanner.solver.run.SolveStatus;
import se.klubb.groupplanner.solver.run.SuggestDurationService;

/**
 * Solve lifecycle endpoints (docs/design/04-solver.md §14.2): start/status/cancel. {@code
 * profile:"GREEDY"} (spec §16.7) bypasses Timefold entirely and runs synchronously — see {@link
 * #solve}. {@code optimize} (§15.5) and {@code blocking} (§14.4) are M6b additions, both optional
 * and defaulting to M6a's exact behavior (fully optimize, no cross-plan blocking) when omitted.
 *
 * <p>Deviates from the design doc's illustrative "422 on invalid pins/weights": this codebase has no
 * 422 usage anywhere (every other validation failure is 400 via {@link
 * se.klubb.groupplanner.api.error.BadRequestException}, an M4 precedent - see
 * backend/docs/m4-notes.md's ConstraintWeightService note) - {@code SolverInputAssembler} throwing
 * {@code BadRequestException} on an invalid pin/weight naturally maps to 400 through the existing
 * {@code ApiExceptionHandler}, with zero special-casing needed here.
 *
 * <p><b>M8 task item 3 fix:</b> {@link #solve} used to return {@code ResponseEntity<?>}, which
 * springdoc cannot resolve to a schema (the generated OpenAPI operation had no {@code
 * responses.200.content} shape at all) - it picked between two ad hoc response records
 * ({@code StartSolveResponse}/{@code GreedySolveResponse}) at runtime depending on {@code
 * profile:"GREEDY"}. Both are merged into the single {@link SolveResponse} record below (nullable
 * greedy-only fields), returned as {@code ResponseEntity<SolveResponse>} for both branches - a
 * concrete generic type springdoc can generate a proper schema for, while still allowing the two
 * branches' different HTTP status codes (200 for the synchronous greedy result, 202 Accepted for an
 * async solve that has only just been scheduled).
 */
@RestController
public class SolveController {

    private static final String GREEDY_PROFILE = "GREEDY";

    private final SolveCoordinator solveCoordinator;
    private final SuggestDurationService suggestDurationService;

    public SolveController(SolveCoordinator solveCoordinator, SuggestDurationService suggestDurationService) {
        this.solveCoordinator = solveCoordinator;
        this.suggestDurationService = suggestDurationService;
    }

    @PostMapping("/api/plans/{planId}/solve")
    public ResponseEntity<SolveResponse> solve(@PathVariable String planId, @RequestBody(required = false) SolveRequest request) {
        String profileStr = request == null ? null : request.profile();
        if (profileStr != null && GREEDY_PROFILE.equalsIgnoreCase(profileStr.strip())) {
            // Synchronous, so the FULL outcome is already known - surface it honestly (M6b review
            // fix F6): greedy routinely produces hard violations by design (spec §16.7's naive
            // baseline), and a bare {runId, FINISHED} would let the UI read a hard-violating
            // baseline as a success.
            SolveCoordinator.GreedyResult result = solveCoordinator.runGreedy(planId);
            return ResponseEntity.ok(new SolveResponse(
                    result.runId(), "FINISHED", result.score().toString(), result.hardViolations(), result.feasible()));
        }
        SolveProfile profile = SolveProfile.fromString(profileStr);
        Integer durationSeconds = request == null ? null : request.durationSeconds();
        if (profile == SolveProfile.CUSTOM) {
            // Fail fast on the HTTP request (400) before ever touching the assembler/SolverManager;
            // SolveCoordinator re-validates defensively too (see its own javadoc).
            SolveProfile.requireValidCustomDuration(durationSeconds);
        }
        OptimizeSelection optimize = optimizeOf(request);
        BlockingOptions blocking = blockingOf(request);
        String runId = solveCoordinator.startSolve(planId, profile, durationSeconds, optimize, blocking);
        // Report the ACTUAL SolverManager status (SOLVING_SCHEDULED until a solver thread picks the
        // job up, then SOLVING_ACTIVE) rather than a hardcoded value (review fix 8).
        String status = solveCoordinator.status(planId).status();
        return ResponseEntity.status(HttpStatus.ACCEPTED).body(new SolveResponse(runId, status, null, null, null));
    }

    /**
     * v0.2.0 (SUGGESTED OPTIMIZATION TIME): proposes a {@code durationSeconds} for the {@code CUSTOM}
     * profile instead of making the user pick blind — see {@link SuggestDurationService}'s javadoc
     * for the full formula/rationale. 409 while a solve is active for this plan (the hardware
     * benchmark competes for CPU).
     */
    @PostMapping("/api/plans/{planId}/solve/suggest-duration")
    public SuggestDurationService.SuggestDurationResponse suggestDuration(@PathVariable String planId) {
        return suggestDurationService.suggest(planId);
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

    private static OptimizeSelection optimizeOf(SolveRequest request) {
        if (request == null || request.optimize() == null) {
            return OptimizeSelection.ALL;
        }
        OptimizeRequest o = request.optimize();
        return new OptimizeSelection(
                o.players() == null || o.players(), o.schedule() == null || o.schedule(), o.coaches() == null || o.coaches());
    }

    private static BlockingOptions blockingOf(SolveRequest request) {
        if (request == null || request.blocking() == null) {
            return BlockingOptions.NONE;
        }
        BlockingRequest b = request.blocking();
        return new BlockingOptions(
                Boolean.TRUE.equals(b.blockPlayers()),
                Boolean.TRUE.equals(b.blockCoaches()),
                Boolean.TRUE.equals(b.blockCourts()),
                Boolean.TRUE.equals(b.conflictsAsWarnings()));
    }

    /** {@code durationSeconds} (v0.2.0) is REQUIRED (10..900, 400 outside) exactly when {@code
     * profile == "CUSTOM"}; ignored for FAST/NORMAL/THOROUGH/GREEDY (no error if present, matching
     * this record's existing lenient-unknown-fields convention). */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record SolveRequest(String profile, Integer durationSeconds, OptimizeRequest optimize, BlockingRequest blocking) {
    }

    /** §15.5 "Optimera endast X" — a {@code null} field (or an absent {@code optimize} object
     * entirely) means "optimize everything", matching M6a's behavior. */
    public record OptimizeRequest(Boolean players, Boolean schedule, Boolean coaches) {
    }

    /** §14.4 cross-plan blocking checkboxes — all default {@code false} (no blocking) when absent. */
    public record BlockingRequest(Boolean blockPlayers, Boolean blockCoaches, Boolean blockCourts, Boolean conflictsAsWarnings) {
    }

    /**
     * Unified {@code POST .../solve} response (M8 task item 3 - see class javadoc): {@code runId}/
     * {@code status} are always present; {@code score}/{@code hardViolations}/{@code feasible} are
     * {@code null} for an async NORMAL/FAST/THOROUGH solve (the outcome isn't known yet — poll
     * {@code GET .../solve/status}) and always non-null for the synchronous {@code GREEDY} branch
     * (M6b review fix F6: greedy routinely produces hard violations by design, spec §16.7's naive
     * baseline, so the response must surface that honestly rather than reading as a clean success).
     */
    public record SolveResponse(String runId, String status, String score, Long hardViolations, Boolean feasible) {
    }

    public record CancelSolveResponse(String finalRunId) {
    }
}
