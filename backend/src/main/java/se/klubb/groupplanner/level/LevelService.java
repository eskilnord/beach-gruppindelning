package se.klubb.groupplanner.level;

import java.util.List;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import se.klubb.groupplanner.domain.ParticipantProfile;
import se.klubb.groupplanner.repo.ParticipantProfileRepository;

/**
 * The {@code estimatedLevel}/{@code levelConfidence} fallback chain (spec §11.2/§11.4). A plain
 * deterministic service outside the solver (docs/plan.md solver-design section: "Plain
 * deterministic services outside the solver ... LevelService"), so ordinary {@code double} math is
 * fine here — CLAUDE.md's no-float rule scopes only to {@code solver.domain}/{@code
 * solver.constraints}. All levels live on the same 0-1000 scale as {@code rankingPoints}.
 *
 * <h2>Chain (spec §11.2, first source found wins)</h2>
 * <ol>
 *   <li>{@code manualLevelScore} present -&gt; used directly, confidence {@link #CONFIDENCE_HIGH}
 *       (the council's own explicit judgement).</li>
 *   <li>else {@code rankingPoints} present -&gt; used directly (spec: "ranking normaliserat"; MVP
 *       normalization is the identity mapping since ranking is already on the 0-1000 scale, per the
 *       real file's observed {@code Rank 0-1000} column), confidence {@link #CONFIDENCE_MEDIUM}.</li>
 *   <li>else {@code previousGroupLevel} present -&gt; mapped from a 1-12 group-tier number to a
 *       0-1000 level score via {@link #GROUP_LEVEL_BASE} - (groupLevel - 1) * {@link
 *       #GROUP_LEVEL_STEP} (group 1 = the top group -&gt; level 1000; group 12 -&gt; level 175);
 *       confidence {@link #CONFIDENCE_LOW}. This mapping is an M4 design decision (the spec only
 *       says "omvandla ... till nivåscore" without a formula) — documented in
 *       backend/docs/m4-notes.md.</li>
 *   <li>else -&gt; {@code estimatedLevel = null}, confidence {@link #CONFIDENCE_NONE}, and {@code
 *       manualReviewFlag} is forced on (spec: "flagga för manuell bedömning").</li>
 * </ol>
 *
 * <p><b>Clamping:</b> the result of every source (not just the previous-group mapping) is clamped
 * to [0, 1000] — a dirty imported ranking of 1200 or a fat-fingered manual score of -50 must never
 * push an out-of-scale value into the solver's level math (M4 review fix; the stored source column
 * itself is left untouched so the original input remains visible/correctable in the UI).
 */
@Service
public class LevelService {

    /** Numeric confidence levels (spec §11.4 allows "hög/medel/låg" or 0.0-1.0; this service always
     * uses the numeric form, at these four fixed points). */
    public static final double CONFIDENCE_HIGH = 1.0;
    public static final double CONFIDENCE_MEDIUM = 0.6;
    public static final double CONFIDENCE_LOW = 0.3;
    public static final double CONFIDENCE_NONE = 0.0;

    /** previousGroupLevel=1 (the top group) maps to this level score. */
    static final double GROUP_LEVEL_BASE = 1000.0;
    /** Level-score points subtracted per group tier below the top (group 12 -> 1000 - 11*75 = 175). */
    static final double GROUP_LEVEL_STEP = 75.0;

    private static final double LEVEL_MIN = 0.0;
    private static final double LEVEL_MAX = 1000.0;

    private final ParticipantProfileRepository participantProfileRepository;

    public LevelService(ParticipantProfileRepository participantProfileRepository) {
        this.participantProfileRepository = participantProfileRepository;
    }

    /** Computes the chain for one participant without persisting anything. */
    public LevelResult compute(ParticipantProfile profile) {
        if (profile.manualLevelScore() != null) {
            return new LevelResult(clamp(profile.manualLevelScore()), CONFIDENCE_HIGH, false);
        }
        if (profile.rankingPoints() != null) {
            return new LevelResult(clamp(profile.rankingPoints()), CONFIDENCE_MEDIUM, false);
        }
        if (profile.previousGroupLevel() != null) {
            double mapped = GROUP_LEVEL_BASE - (profile.previousGroupLevel() - 1) * GROUP_LEVEL_STEP;
            return new LevelResult(clamp(mapped), CONFIDENCE_LOW, false);
        }
        return new LevelResult(null, CONFIDENCE_NONE, true);
    }

    /**
     * Recomputes and persists {@code estimatedLevel}/{@code levelConfidence} (and, only when no
     * source at all exists, forces {@code manualReviewFlag} on) for every participant of a plan.
     * Called both from {@code POST /api/plans/{planId}/participants/recompute-levels} and
     * automatically after an import commit (see {@code ImportCommitService}).
     *
     * @return the number of participants recomputed.
     */
    @Transactional
    public int recomputeForPlan(String activityPlanId) {
        List<ParticipantProfile> profiles = participantProfileRepository.findByActivityPlanId(activityPlanId);
        for (ParticipantProfile profile : profiles) {
            LevelResult result = compute(profile);
            participantProfileRepository.updateComputedLevel(
                    profile.id(), result.estimatedLevel(), result.levelConfidence(), result.forceManualReview());
        }
        return profiles.size();
    }

    private static double clamp(double level) {
        return Math.max(LEVEL_MIN, Math.min(LEVEL_MAX, level));
    }

    /**
     * @param estimatedLevel  {@code null} only when no level source exists at all.
     * @param levelConfidence one of the {@code CONFIDENCE_*} constants.
     * @param forceManualReview true only in the "no source at all" case (spec §11.2).
     */
    public record LevelResult(Double estimatedLevel, double levelConfidence, boolean forceManualReview) {
    }
}
