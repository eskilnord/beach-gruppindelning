package se.klubb.groupplanner.savedplan;

import java.util.Map;
import java.util.Set;
import se.klubb.groupplanner.domain.SavedPlan;

/**
 * The {@code saved_plan.status} transition rules (spec §14.2: {@code
 * draft -> saved -> locked -> published -> archived}), enforced by {@link SavedPlanService} before
 * any {@code PATCH} status change is persisted.
 *
 * <p>{@code archived} is a terminal sink reachable from every non-draft status (a council member can
 * decide to retire a plan at any point after it has actually been saved, not only once it has walked
 * the full pipeline to {@code published}) — everything else only moves strictly FORWARD one step at a
 * time; there is no "un-lock a saved_plan" or "un-publish" transition here (that is what {@code
 * DELETE} + re-save is for, and only while the row is still {@code draft}/{@code saved} — see {@link
 * #isDeletable}). {@code draft} itself is not currently reachable via {@code POST
 * /api/plans/{planId}/saved-plans} (which always creates {@code saved} - "spara plan" IS the save
 * action; there is no separate autosave-draft feature in this MVP) but stays modeled here for
 * symmetry with {@code activity_plan.status}'s identical five-value domain and so a future draft
 * producer needs no change to this class.
 */
public final class SavedPlanLifecycle {

    private static final Map<String, Set<String>> LEGAL_TRANSITIONS = Map.of(
            SavedPlan.STATUS_DRAFT, Set.of(SavedPlan.STATUS_SAVED),
            SavedPlan.STATUS_SAVED, Set.of(SavedPlan.STATUS_LOCKED, SavedPlan.STATUS_ARCHIVED),
            SavedPlan.STATUS_LOCKED, Set.of(SavedPlan.STATUS_PUBLISHED, SavedPlan.STATUS_ARCHIVED),
            SavedPlan.STATUS_PUBLISHED, Set.of(SavedPlan.STATUS_ARCHIVED),
            SavedPlan.STATUS_ARCHIVED, Set.of());

    private static final Set<String> ALL_STATUSES = Set.of(
            SavedPlan.STATUS_DRAFT, SavedPlan.STATUS_SAVED, SavedPlan.STATUS_LOCKED,
            SavedPlan.STATUS_PUBLISHED, SavedPlan.STATUS_ARCHIVED);

    /** Only {@code draft}/{@code saved} plans may be deleted (spec's own implicit ordering: once a
     * plan is {@code locked} it may already be blocking OTHER plans' solves via {@code
     * saved_plan_resource_usage} - deleting it out from under them would silently un-block a solve
     * that had already been run against it, invisibly changing a past result). A council member who
     * truly wants to retire a locked/published plan uses the {@code archived} status instead, which
     * keeps the row (and its history) intact. */
    private static final Set<String> DELETABLE_STATUSES = Set.of(SavedPlan.STATUS_DRAFT, SavedPlan.STATUS_SAVED);

    private SavedPlanLifecycle() {
    }

    public static boolean isKnownStatus(String status) {
        return status != null && ALL_STATUSES.contains(status);
    }

    public static boolean isLegalTransition(String from, String to) {
        return LEGAL_TRANSITIONS.getOrDefault(from, Set.of()).contains(to);
    }

    public static boolean isDeletable(String status) {
        return DELETABLE_STATUSES.contains(status);
    }
}
