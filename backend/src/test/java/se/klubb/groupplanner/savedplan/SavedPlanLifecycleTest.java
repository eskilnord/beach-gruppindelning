package se.klubb.groupplanner.savedplan;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import se.klubb.groupplanner.domain.SavedPlan;

/** The saved_plan status transition matrix (spec §14.2): {@code draft -> saved -> locked ->
 * published -> archived}, {@code archived} reachable from every non-terminal status, no other move
 * legal. Pure unit test of {@link SavedPlanLifecycle} - no Spring context needed. */
class SavedPlanLifecycleTest {

    @ParameterizedTest(name = "{0} -> {1} is legal")
    @CsvSource({
            "draft, saved",
            "saved, locked",
            "saved, archived",
            "locked, published",
            "locked, archived",
            "published, archived",
    })
    void legalTransitions(String from, String to) {
        assertThat(SavedPlanLifecycle.isLegalTransition(from, to)).isTrue();
    }

    @ParameterizedTest(name = "{0} -> {1} is illegal")
    @CsvSource({
            "draft, locked",
            "draft, published",
            "draft, archived",
            "saved, published",
            "saved, draft",
            "locked, saved",
            "locked, draft",
            "published, locked",
            "published, saved",
            "published, draft",
            "archived, draft",
            "archived, saved",
            "archived, locked",
            "archived, published",
    })
    void illegalTransitions(String from, String to) {
        assertThat(SavedPlanLifecycle.isLegalTransition(from, to)).isFalse();
    }

    @Test
    void archivedIsTerminal() {
        List<String> allStatuses = List.of(
                SavedPlan.STATUS_DRAFT, SavedPlan.STATUS_SAVED, SavedPlan.STATUS_LOCKED,
                SavedPlan.STATUS_PUBLISHED, SavedPlan.STATUS_ARCHIVED);
        for (String candidate : allStatuses) {
            assertThat(SavedPlanLifecycle.isLegalTransition(SavedPlan.STATUS_ARCHIVED, candidate)).isFalse();
        }
    }

    @Test
    void onlyDraftAndSavedAreDeletable() {
        assertThat(SavedPlanLifecycle.isDeletable(SavedPlan.STATUS_DRAFT)).isTrue();
        assertThat(SavedPlanLifecycle.isDeletable(SavedPlan.STATUS_SAVED)).isTrue();
        assertThat(SavedPlanLifecycle.isDeletable(SavedPlan.STATUS_LOCKED)).isFalse();
        assertThat(SavedPlanLifecycle.isDeletable(SavedPlan.STATUS_PUBLISHED)).isFalse();
        assertThat(SavedPlanLifecycle.isDeletable(SavedPlan.STATUS_ARCHIVED)).isFalse();
    }

    @Test
    void unknownStatusStringsAreRejected() {
        assertThat(SavedPlanLifecycle.isKnownStatus("bogus")).isFalse();
        assertThat(SavedPlanLifecycle.isKnownStatus(null)).isFalse();
        assertThat(SavedPlanLifecycle.isKnownStatus(SavedPlan.STATUS_LOCKED)).isTrue();
    }
}
