package se.klubb.groupplanner.level;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import se.klubb.groupplanner.domain.ParticipantProfile;

/**
 * Pure unit tests for the estimatedLevel fallback chain (spec §11.2) — {@link LevelService#compute}
 * needs no database, so these run without a Spring context.
 */
class LevelServiceTest {

    private final LevelService levelService = new LevelService(null);

    private static ParticipantProfile profile(Double manualLevelScore, Double rankingPoints, Double previousGroupLevel) {
        return new ParticipantProfile(
                "p1", "person1", "plan1", rankingPoints, null, null, previousGroupLevel,
                null, null, manualLevelScore, null, null, false, false);
    }

    @Test
    void manualLevelScoreTakesPriorityOverEverythingElse() {
        LevelService.LevelResult result = levelService.compute(profile(500.0, 900.0, 3.0));

        assertThat(result.estimatedLevel()).isEqualTo(500.0);
        assertThat(result.levelConfidence()).isEqualTo(LevelService.CONFIDENCE_HIGH);
        assertThat(result.forceManualReview()).isFalse();
    }

    @Test
    void rankingPointsUsedWhenNoManualLevelScore() {
        LevelService.LevelResult result = levelService.compute(profile(null, 720.0, 3.0));

        assertThat(result.estimatedLevel()).isEqualTo(720.0);
        assertThat(result.levelConfidence()).isEqualTo(LevelService.CONFIDENCE_MEDIUM);
        assertThat(result.forceManualReview()).isFalse();
    }

    @Test
    void previousGroupLevelOneMapsToTopLevelScore() {
        LevelService.LevelResult result = levelService.compute(profile(null, null, 1.0));

        assertThat(result.estimatedLevel()).isEqualTo(1000.0);
        assertThat(result.levelConfidence()).isEqualTo(LevelService.CONFIDENCE_LOW);
        assertThat(result.forceManualReview()).isFalse();
    }

    @Test
    void previousGroupLevelTwelveMapsToLowestDocumentedLevelScore() {
        LevelService.LevelResult result = levelService.compute(profile(null, null, 12.0));

        // 1000 - (12 - 1) * 75 = 1000 - 825 = 175.
        assertThat(result.estimatedLevel()).isEqualTo(175.0);
        assertThat(result.levelConfidence()).isEqualTo(LevelService.CONFIDENCE_LOW);
    }

    @Test
    void previousGroupLevelMappingClampsToZeroForOutOfRangeDirtyData() {
        // A dirty/out-of-range group number (e.g. 100) must never yield a negative level score.
        LevelService.LevelResult result = levelService.compute(profile(null, null, 100.0));

        assertThat(result.estimatedLevel()).isEqualTo(0.0);
        assertThat(result.levelConfidence()).isEqualTo(LevelService.CONFIDENCE_LOW);
    }

    @Test
    void previousGroupLevelMappingClampsToOneThousandForBelowRangeDirtyData() {
        // groupLevel=0 would compute to 1075 without clamping - must clamp to the 1000 ceiling.
        LevelService.LevelResult result = levelService.compute(profile(null, null, 0.0));

        assertThat(result.estimatedLevel()).isEqualTo(1000.0);
    }

    /** M4 review fix: ALL sources clamp to [0, 1000], not just the previous-group mapping — a dirty
     * imported ranking or fat-fingered manual score must never leave the level scale. */
    @Test
    void manualLevelScoreClampsToLevelScaleBounds() {
        assertThat(levelService.compute(profile(1200.0, null, null)).estimatedLevel()).isEqualTo(1000.0);
        assertThat(levelService.compute(profile(-50.0, null, null)).estimatedLevel()).isEqualTo(0.0);
        // Exact boundary values pass through unchanged.
        assertThat(levelService.compute(profile(0.0, null, null)).estimatedLevel()).isEqualTo(0.0);
        assertThat(levelService.compute(profile(1000.0, null, null)).estimatedLevel()).isEqualTo(1000.0);
    }

    @Test
    void rankingPointsClampToLevelScaleBounds() {
        assertThat(levelService.compute(profile(null, 1500.0, null)).estimatedLevel()).isEqualTo(1000.0);
        assertThat(levelService.compute(profile(null, -1.0, null)).estimatedLevel()).isEqualTo(0.0);
        assertThat(levelService.compute(profile(null, 0.0, null)).estimatedLevel()).isEqualTo(0.0);
        assertThat(levelService.compute(profile(null, 1000.0, null)).estimatedLevel()).isEqualTo(1000.0);
    }

    @Test
    void noSourceAtAllYieldsNullLevelZeroConfidenceAndForcesManualReview() {
        LevelService.LevelResult result = levelService.compute(profile(null, null, null));

        assertThat(result.estimatedLevel()).isNull();
        assertThat(result.levelConfidence()).isEqualTo(LevelService.CONFIDENCE_NONE);
        assertThat(result.forceManualReview()).isTrue();
    }
}
