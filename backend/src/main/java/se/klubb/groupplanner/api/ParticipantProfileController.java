package se.klubb.groupplanner.api;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.List;
import java.util.Map;
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
import se.klubb.groupplanner.groups.PreviousGroupNormalizer;
import se.klubb.groupplanner.groups.PreviousGroupRef;
import se.klubb.groupplanner.level.LevelService;
import se.klubb.groupplanner.repo.ActivityPlanRepository;
import se.klubb.groupplanner.repo.ParticipantProfileRepository;
import se.klubb.groupplanner.repo.PersonRepository;
import se.klubb.groupplanner.repo.PlayerAssignmentRepository;
import se.klubb.groupplanner.util.Uuid7;

/**
 * CRUD for {@code ParticipantProfile} (spec §7.2), nested under its {@code ActivityPlan}
 * (docs/design/02-product-data-ui.md §8: {@code /api/plans/{planId}/participants}).
 *
 * <h2>PATCH null-vs-absent semantics (docs/plan.md M4 row; M1 review finding)</h2>
 *
 * {@code PATCH /api/participants/{id}} takes a raw {@code Map<String, JsonNode>} body rather than a
 * typed request record, specifically so it can distinguish "field omitted -&gt; keep the current
 * value" from "field explicitly {@code null} -&gt; clear it" — a plain record can't make that
 * distinction (Jackson resolves every record/constructor parameter through the same
 * missing-vs-explicit-null fallback, so both cases collapse to the same value; verified empirically
 * while implementing this). A JSON object naturally keeps only the keys that were actually present,
 * so {@code body.containsKey(field)} is a reliable presence check and {@code body.get(field)
 * .isNull()} is a reliable explicit-null check. This matters most for {@code importedComment}/{@code
 * internalNote} (spec §21.2: comments must be clearable) and the other nullable columns below;
 * {@code manualReviewFlag}/{@code waitlisted}/{@code reviewedDone} are {@code NOT NULL} columns, so
 * an explicit {@code null} for any of them is rejected with a 400 instead of silently coercing to
 * {@code false}.
 */
@RestController
public class ParticipantProfileController {

    private final ParticipantProfileRepository participantProfileRepository;
    private final ActivityPlanRepository activityPlanRepository;
    private final PersonRepository personRepository;
    private final LevelService levelService;
    private final PlayerAssignmentRepository playerAssignmentRepository;

    public ParticipantProfileController(
            ParticipantProfileRepository participantProfileRepository,
            ActivityPlanRepository activityPlanRepository,
            PersonRepository personRepository,
            LevelService levelService,
            PlayerAssignmentRepository playerAssignmentRepository) {
        this.participantProfileRepository = participantProfileRepository;
        this.activityPlanRepository = activityPlanRepository;
        this.personRepository = personRepository;
        this.levelService = levelService;
        this.playerAssignmentRepository = playerAssignmentRepository;
    }

    @GetMapping("/api/plans/{planId}/participants")
    public List<ParticipantProfileView> listForPlan(@PathVariable String planId) {
        requirePlanExists(planId);
        return participantProfileRepository.findByActivityPlanId(planId).stream()
                .map(ParticipantProfileView::of)
                .toList();
    }

    @PostMapping("/api/plans/{planId}/participants")
    @ResponseStatus(HttpStatus.CREATED)
    public ParticipantProfileView create(@PathVariable String planId, @RequestBody CreateParticipantProfileRequest request) {
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
                nullIfBlank(request.previousGroupName()),
                request.previousGroupLevel(),
                request.estimatedLevel(),
                request.levelConfidence(),
                request.manualLevelScore(),
                request.importedComment(),
                request.internalNote(),
                request.manualReviewFlag() != null && request.manualReviewFlag(),
                request.waitlisted() != null && request.waitlisted(),
                request.reviewedDone() != null && request.reviewedDone());
        ParticipantProfile created = participantProfileRepository.insert(profile);
        // M8 (found by the M8 jar E2E): a participant without a player_assignment row is invisible
        // to GET .../assignments AND - much worse - to SolveCoordinator#persistResult's writeback
        // (updateGroupAndSource is an UPDATE scoped to an existing row, so the solver's in-memory
        // placement for such a participant was silently dropped on write). The import-commit path
        // has always created this "awaiting placement" row (docs/design/02-product-data-ui.md §2
        // step 8); the REST create path now does the same.
        playerAssignmentRepository.insertImportedIfAbsent(created.id());
        // M7 review fix M2: a new participant changes what any already-computed explanation for this
        // plan describes (probe results, waitlist reasoning) - see AssignmentController#move's javadoc
        // for the shared invalidation-surface rationale.
        activityPlanRepository.bumpRevision(planId);
        return ParticipantProfileView.of(created);
    }

    @GetMapping("/api/participants/{id}")
    public ParticipantProfileView get(@PathVariable String id) {
        return ParticipantProfileView.of(findOrThrow(id));
    }

    /** The exact set of JSON keys {@code PATCH /api/participants/{id}} accepts. Anything else is a
     * 400: silently ignoring an unknown key would make a typo (e.g. {@code "imortedComment": null})
     * return 200 while clearing nothing — a privacy foot-gun for the §21.2 comment-clearing flow. */
    private static final java.util.Set<String> PATCHABLE_FIELDS = java.util.Set.of(
            "rankingPoints", "rankingSource", "previousGroupName", "previousGroupLevel",
            "estimatedLevel", "levelConfidence", "manualLevelScore", "importedComment",
            "internalNote", "manualReviewFlag", "waitlisted", "reviewedDone");

    @PatchMapping("/api/participants/{id}")
    public ParticipantProfileView update(@PathVariable String id, @RequestBody(required = false) Map<String, JsonNode> body) {
        ParticipantProfile existing = findOrThrow(id);
        if (body == null || body.isEmpty()) {
            return ParticipantProfileView.of(existing);
        }
        for (String key : body.keySet()) {
            if (!PATCHABLE_FIELDS.contains(key)) {
                throw new BadRequestException("Unknown field: '" + key + "' - patchable fields are " + PATCHABLE_FIELDS);
            }
        }
        // importedComment arrives only via the import pipeline (spec §2.2/§8.5 - it is the audit
        // trail of what the member actually wrote); this endpoint may only CLEAR it (spec §21.2),
        // never write arbitrary text into it. internalNote (the council's own note) stays freely
        // writable.
        JsonNode importedCommentNode = body.get("importedComment");
        if (importedCommentNode != null && !importedCommentNode.isNull()) {
            throw new BadRequestException(
                    "importedComment can only be cleared (null) here - comment text is only ever written by the import");
        }
        ParticipantProfile updated = new ParticipantProfile(
                existing.id(),
                existing.personId(),
                existing.activityPlanId(),
                nullableDouble(body, "rankingPoints", existing.rankingPoints()),
                nullableString(body, "rankingSource", existing.rankingSource()),
                nullIfBlank(nullableString(body, "previousGroupName", existing.previousGroupName())),
                nullableDouble(body, "previousGroupLevel", existing.previousGroupLevel()),
                nullableDouble(body, "estimatedLevel", existing.estimatedLevel()),
                nullableDouble(body, "levelConfidence", existing.levelConfidence()),
                nullableDouble(body, "manualLevelScore", existing.manualLevelScore()),
                nullableString(body, "importedComment", existing.importedComment()),
                nullableString(body, "internalNote", existing.internalNote()),
                requiredBoolean(body, "manualReviewFlag", existing.manualReviewFlag()),
                requiredBoolean(body, "waitlisted", existing.waitlisted()),
                requiredBoolean(body, "reviewedDone", existing.reviewedDone()));
        ParticipantProfile saved = participantProfileRepository.update(updated);
        // WP3: reviewedDone is a council workflow marker with no effect on solver input or
        // explanations, so bumping on a PATCH that only flips it (or that changes nothing at all -
        // a full no-op PATCH also lands here, a deliberate improvement) must not invalidate cached
        // explanations. The bump is SKIPPED whenever the PATCH produces no effective change other
        // than reviewedDone, and happens as usual whenever any other field changed too (record
        // equals lets us detect that cleanly by comparing against "existing with only reviewedDone
        // substituted").
        ParticipantProfile existingWithNewReviewedDone = new ParticipantProfile(
                existing.id(), existing.personId(), existing.activityPlanId(), existing.rankingPoints(),
                existing.rankingSource(), existing.previousGroupName(), existing.previousGroupLevel(),
                existing.estimatedLevel(), existing.levelConfidence(), existing.manualLevelScore(),
                existing.importedComment(), existing.internalNote(), existing.manualReviewFlag(),
                existing.waitlisted(), updated.reviewedDone());
        if (!updated.equals(existingWithNewReviewedDone)) {
            // M7 (docs/design/04-solver.md §11.6): a level/priority-affecting edit invalidates any
            // already-computed explanation for this plan - see AssignmentController#move's javadoc
            // for the full "invalidation surface" rationale shared by every bump call site.
            activityPlanRepository.bumpRevision(existing.activityPlanId());
        }
        return ParticipantProfileView.of(saved);
    }

    @DeleteMapping("/api/participants/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@PathVariable String id) {
        ParticipantProfile existing = findOrThrow(id);
        participantProfileRepository.deleteById(id);
        activityPlanRepository.bumpRevision(existing.activityPlanId()); // M7 review fix M2.
    }

    /**
     * Plan-wide {@code estimatedLevel}/{@code levelConfidence} recompute (spec §11.2,
     * docs/plan.md M4 row) — also runs automatically after every import commit
     * ({@code ImportCommitService}); this endpoint lets the council re-trigger it after manually
     * editing {@code manualLevelScore}/{@code rankingPoints}/{@code previousGroupLevel} values.
     */
    @PostMapping("/api/plans/{planId}/participants/recompute-levels")
    public RecomputeLevelsResult recomputeLevels(@PathVariable String planId) {
        requirePlanExists(planId);
        int count = levelService.recomputeForPlan(planId);
        // M7 review fix M2: recomputed estimated levels feed levelScaled directly - every level-based
        // sentence in an already-cached explanation may now be false.
        activityPlanRepository.bumpRevision(planId);
        return new RecomputeLevelsResult(count);
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

    /** Absent key -&gt; keep {@code current}; JSON {@code null} -&gt; clear (returns {@code null}); otherwise parsed as a number. */
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

    /** Absent key -&gt; keep {@code current}; JSON {@code null} -&gt; clear (returns {@code null}); otherwise parsed as text. */
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

    /** Absent key -&gt; keep {@code current}; JSON {@code null} -&gt; rejected (column is NOT NULL); otherwise parsed as a boolean. */
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

    /**
     * FIX4 (B5 review, DECIDED: drop write normalization): {@code previousGroupName} is stored
     * VERBATIM - no {@link PreviousGroupNormalizer#parse(String)}-based collapsing on write. Rationale:
     * hand-typed free text is the admin's own data, and the solver only ever parses {@code
     * previousGroupName} at READ time ({@link PreviousGroupNormalizer#parse(String)} again, via the
     * derived {@link ParticipantProfileView#previousGroupOrder()}/{@link
     * ParticipantProfileView#previousGroupParseWarning()} fields below) - so the value's STORAGE form
     * never affects scoring, and silently rewriting what the admin typed (e.g. collapsing a pipe-
     * separated paste down to one segment) is a surprise with no upside here. The import pipeline
     * remains the only place that normalizes on write ({@code ImportedValueNormalizer#previousGroupName}
     * / {@code ImportCommitService}) - a FILE is the actual source of truth for pipe-history there, a
     * hand-typed API value is not.
     *
     * <p>The one thing this DOES still do is the pre-B5 blank-vs-null behavior: whitespace-only input
     * becomes {@code null} rather than being stored as a blank/whitespace string (MINOR 11, matches the
     * import path's {@code ExtractedRow.isNonBlank} treatment of a blank cell as "no value").
     */
    private static String nullIfBlank(String raw) {
        return (raw == null || raw.isBlank()) ? null : raw;
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
            Boolean waitlisted,
            Boolean reviewedDone) {
    }

    public record RecomputeLevelsResult(int recomputedCount) {
    }

    /**
     * Read view of a {@link ParticipantProfile} for the API surface (B5): adds two derived,
     * read-only fields on top of every stored column so the frontend doesn't have to re-implement
     * {@link PreviousGroupNormalizer#parse(String)} client-side just to show group continuity status.
     *
     * @param previousGroupOrder the parsed group order (see {@link
     *     se.klubb.groupplanner.groups.PreviousGroupRef#groupOrder()}), or {@code null} when {@code
     *     previousGroupName} is blank or does not parse to one.
     * @param previousGroupParseWarning the same Swedish "kan inte tolkas" sentence used by the import
     *     wizard's row-validation warning ({@link PreviousGroupNormalizer#parseWarningSv(String)}),
     *     or {@code null} when {@code previousGroupName} is blank or parses successfully.
     */
    // FIX5 (MAJOR, B5 review): pin the generated OpenAPI schema name to "ParticipantProfile" - without
    // this, springdoc keys components.schemas by simple class name, so this record would generate as
    // "ParticipantProfileView" instead and silently break the frontend's openapi-typegen narrowing
    // (components["schemas"]["ParticipantProfile"]). The domain-level se.klubb.groupplanner.domain
    // .ParticipantProfile entity is no longer exposed on any endpoint (this view replaced it
    // everywhere), so there is no name clash to worry about - see OpenApiSchemaTest.
    @io.swagger.v3.oas.annotations.media.Schema(name = "ParticipantProfile")
    public record ParticipantProfileView(
            String id,
            String personId,
            String activityPlanId,
            Double rankingPoints,
            String rankingSource,
            String previousGroupName,
            Double previousGroupLevel,
            Double estimatedLevel,
            Double levelConfidence,
            Double manualLevelScore,
            String importedComment,
            String internalNote,
            boolean manualReviewFlag,
            boolean waitlisted,
            boolean reviewedDone,
            Integer previousGroupOrder,
            String previousGroupParseWarning) {

        public static ParticipantProfileView of(ParticipantProfile p) {
            PreviousGroupRef ref = PreviousGroupNormalizer.parse(p.previousGroupName());
            Integer previousGroupOrder = ref != null ? ref.groupOrder() : null;
            String previousGroupParseWarning = PreviousGroupNormalizer.parseWarningSv(p.previousGroupName());
            return new ParticipantProfileView(
                    p.id(), p.personId(), p.activityPlanId(), p.rankingPoints(), p.rankingSource(),
                    p.previousGroupName(), p.previousGroupLevel(), p.estimatedLevel(), p.levelConfidence(),
                    p.manualLevelScore(), p.importedComment(), p.internalNote(), p.manualReviewFlag(),
                    p.waitlisted(), p.reviewedDone(), previousGroupOrder, previousGroupParseWarning);
        }
    }
}
