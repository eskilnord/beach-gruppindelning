package se.klubb.groupplanner.explain;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import se.klubb.groupplanner.explain.ExplanationService.RunContext;
import se.klubb.groupplanner.solver.constraints.ConstraintKeys;
import se.klubb.groupplanner.solver.domain.CoachSlot;
import se.klubb.groupplanner.solver.domain.CoachWish;
import se.klubb.groupplanner.solver.domain.CoachWishType;
import se.klubb.groupplanner.solver.domain.Group;
import se.klubb.groupplanner.solver.domain.PersonPairWish;
import se.klubb.groupplanner.solver.domain.PlayerAssignment;
import se.klubb.groupplanner.solver.domain.WishType;

/**
 * M-E2 "unmet wishes" model: for a PLACED player, derives which of their wishes did not come true,
 * plus which OTHER group(s) — purely from facts, never from a {@link MoveProbe} — would have
 * satisfied each one. Deliberately independent of {@link ExplanationService#classifyParticipantMatches}
 * (which reads {@code ConstraintJustification} matches): a {@code .reward(...)} constraint (e.g.
 * {@code coachPreferenceSoft}, WANT coach wishes) produces NO match at all when its wish is unmet —
 * there is nothing to "break" — so this resolver instead replicates each constraint's own satisfaction
 * check directly against the {@code GroupPlanSolution} facts (personRelation/coachRelation wishes,
 * time preferences, previous-group continuity), the same facts {@code GroupPlanConstraintProvider}
 * itself joins against. This keeps every {@code unmetWishes[]} entry provably data-derived (kravspec
 * §17.4), and keeps the EXISTING {@code brokenWishes[]}/{@code WISH_KEYS}/{@code negativeFactors}
 * machinery byte-compatible — {@code timePreferenceSoft}/{@code previousGroupContinuity} are
 * "promoted" to wishes ONLY here, never added to {@link ExplanationService#WISH_KEYS} itself.
 */
final class UnmetWishResolver {

    private UnmetWishResolver() {
    }

    /**
     * One unmet wish, resolved from data.
     *
     * @param key the {@link ConstraintKeys} constant this wish maps to
     * @param wishKind {@code TIME|FRIEND|AVOID|PREVGROUP|COACH} — drives {@code CausalNarrator}'s
     *     sentence templates
     * @param otherParticipantSolverId nullable — the friend/avoided person's solver-internal id
     *     (FRIEND/AVOID only)
     * @param coachPersonSolverId nullable — the wished coach's solver-internal person id (COACH only)
     * @param candidateGroups every group (from DATA, never probed) that would satisfy this wish,
     *     group-order ascending; empty means NO_CANDIDATE
     * @param friendWaitlisted true only for a FRIEND wish whose target person is themselves
     *     unassigned (candidateGroups is always empty in that case — see {@link
     *     ExplanationService#addWaitlistedFriendNotices}'s javadoc for the same join fact)
     * @param wishOwnedByTarget M-E2 review fix (BLOCKER, directed-wish attribution): true when {@code
     *     target} is the field OWNER of this wish ({@link PersonPairWish#aParticipantProfileId()} —
     *     the participant whose {@code personRelation} custom-field VALUE actually names {@code
     *     otherParticipantSolverId}), false when {@code target} is only the wish's TARGET (the {@code
     *     b}-side named BY someone else's field value). Always {@code true} for TIME/PREVGROUP/COACH
     *     (not directed pair wishes — {@code target} is trivially the "owner" of their own preference).
     *     {@code CausalNarrator} uses this to never write "«Namn» vill helst/måste ..." on the drawer
     *     of the person who is only the TARGET of someone else's wish (proven wrong before this fix).
     */
    record UnmetWish(
            String key,
            String wishKind,
            Long otherParticipantSolverId,
            Long coachPersonSolverId,
            List<Group> candidateGroups,
            boolean friendWaitlisted,
            boolean wishOwnedByTarget) {
    }

    static List<UnmetWish> resolve(RunContext ctx, PlayerAssignment target, Group selectedGroup) {
        List<UnmetWish> out = new ArrayList<>();
        List<Group> allGroupsAscending = ctx.solution().getGroups().stream()
                .sorted(java.util.Comparator.comparingInt(Group::groupOrder))
                .toList();

        resolveTime(ctx, target, selectedGroup, allGroupsAscending, out);
        resolveFriendAndAvoid(ctx, target, selectedGroup, allGroupsAscending, out);
        resolvePreviousGroup(ctx, target, selectedGroup, allGroupsAscending, out);
        resolveCoach(ctx, target, selectedGroup, allGroupsAscending, out);
        return out;
    }

    /** TIME: {@code timePreferenceSoft} is broken when the player expressed a preference and their
     * CURRENT group's slot is not one of the preferred slots — candidates are every OTHER group whose
     * OWN slot the player prefers (the exact set {@code timePreferenceSoft}'s own {@code
     * PlayerAssignment.prefers(...)} check would accept). */
    private static void resolveTime(
            RunContext ctx, PlayerAssignment target, Group selectedGroup, List<Group> allGroupsAscending, List<UnmetWish> out) {
        if (!target.hasPreferences()) {
            return;
        }
        Long currentSlot = timeSlotIdOf(ctx, selectedGroup);
        if (currentSlot != null && target.prefers(currentSlot)) {
            return; // wish already satisfied.
        }
        List<Group> candidates = new ArrayList<>();
        for (Group g : allGroupsAscending) {
            if (g == selectedGroup) {
                continue;
            }
            Long slot = timeSlotIdOf(ctx, g);
            if (slot != null && target.prefers(slot)) {
                candidates.add(g);
            }
        }
        out.add(new UnmetWish(ConstraintKeys.TIME_PREFERENCE_SOFT, "TIME", null, null, candidates, false, true));
    }

    /** FRIEND (MUST_SAME/WANT_SAME) and AVOID (MUST_DIFFERENT/WANT_DIFFERENT), both directions of any
     * {@link PersonPairWish} that NAMES {@code target} on either side. A FRIEND wish is broken
     * whenever the wished partner is NOT in {@code selectedGroup} (including when they are themselves
     * unassigned — the {@code friendWaitlisted} edge, candidates forced empty, mirroring {@link
     * ExplanationService#addWaitlistedFriendNotices}). An AVOID wish is broken whenever the avoided
     * partner IS in {@code selectedGroup} (the only way {@code differentGroupHard/Soft} can fire, per
     * that constraint's own join) — candidates are every OTHER group (any group but the shared one).
     *
     * <p>M-E2 review fix (BLOCKER, dedupe): a MUTUAL wish (both participants have the SAME field
     * pointing at each other, e.g. both filled in "playWith" for one another) previously produced TWO
     * near-identical {@code UnmetWish} entries for the exact same broken pairing — deduped here by
     * {@code (FRIEND|AVOID, otherParticipantId)}, {@link LinkedHashSet} like {@link #resolveCoach}'s
     * own {@code seenCoachPersonIds} dedupe, keeping only the FIRST-encountered direction (the
     * solver's own deterministic {@code PersonPairWish} order — see {@code SolverInputAssembler}). */
    private static void resolveFriendAndAvoid(
            RunContext ctx, PlayerAssignment target, Group selectedGroup, List<Group> allGroupsAscending, List<UnmetWish> out) {
        Set<String> seenPairs = new LinkedHashSet<>();
        for (PersonPairWish wish : ctx.solution().getPersonPairWishes()) {
            if (wish.aParticipantProfileId() != target.getId() && wish.bParticipantProfileId() != target.getId()) {
                continue;
            }
            boolean targetIsOwner = wish.aParticipantProfileId() == target.getId();
            long otherId = targetIsOwner ? wish.bParticipantProfileId() : wish.aParticipantProfileId();
            boolean sameGroupWish = wish.type() == WishType.MUST_SAME || wish.type() == WishType.WANT_SAME;
            String dedupeKey = (sameGroupWish ? "FRIEND:" : "AVOID:") + otherId;
            if (!seenPairs.add(dedupeKey)) {
                continue; // mutual wish already recorded from the other direction - one entry, not two.
            }
            PlayerAssignment other = playerById(ctx, otherId);
            if (sameGroupWish) {
                if (other != null && other.getGroup() == selectedGroup) {
                    continue; // already satisfied.
                }
                String key = wish.type() == WishType.MUST_SAME ? ConstraintKeys.SAME_GROUP_HARD : ConstraintKeys.SAME_GROUP_SOFT;
                boolean waitlisted = other == null || other.getGroup() == null;
                List<Group> candidates = waitlisted || other.getGroup() == null
                        ? List.of()
                        : List.of(other.getGroup());
                out.add(new UnmetWish(key, "FRIEND", otherId, null, candidates, waitlisted, targetIsOwner));
            } else {
                if (other == null || other.getGroup() != selectedGroup) {
                    continue; // not currently together - nothing to avoid-break.
                }
                String key = wish.type() == WishType.MUST_DIFFERENT ? ConstraintKeys.DIFFERENT_GROUP_HARD : ConstraintKeys.DIFFERENT_GROUP_SOFT;
                List<Group> candidates = allGroupsAscending.stream().filter(g -> g != other.getGroup()).toList();
                out.add(new UnmetWish(key, "AVOID", otherId, null, candidates, false, targetIsOwner));
            }
        }
    }

    /** PREVGROUP: {@code previousGroupContinuity} is broken whenever the player's current group order
     * is not exactly their {@code previousGroupOrder} — candidate is the (at most one) group whose
     * {@code groupOrder} matches, if that group still exists this term.
     *
     * <p>MINOR review fix: defensively excludes {@code selectedGroup} itself from the candidate match
     * (a duplicate {@code groupOrder} across two {@link Group} facts would otherwise be a data anomaly
     * this resolver has no business trusting) — an empty result after that filter falls through to
     * {@code CausalNarrator}'s existing NO_CANDIDATE branch exactly like any other empty candidate set,
     * never a thrown exception. */
    private static void resolvePreviousGroup(
            RunContext ctx, PlayerAssignment target, Group selectedGroup, List<Group> allGroupsAscending, List<UnmetWish> out) {
        Integer previousOrder = target.getPreviousGroupOrder();
        if (previousOrder == null || selectedGroup.groupOrder() == previousOrder) {
            return;
        }
        List<Group> candidates = allGroupsAscending.stream()
                .filter(g -> g.groupOrder() == previousOrder && g != selectedGroup)
                .toList();
        out.add(new UnmetWish(ConstraintKeys.PREVIOUS_GROUP_CONTINUITY, "PREVGROUP", null, null, candidates, false, true));
    }

    /** COACH (MUST/WANT only — {@code coachWishRequired}/{@code coachPreferenceSoft}; a broken CANNOT
     * wish is an AVOID-shaped case this milestone does not promote to {@code unmetWishes[]}, matching
     * the brief's own enumeration). Broken whenever {@code selectedGroup}'s filled {@code CoachSlot}
     * (if any) does not hold the wished coach — candidates are every group whose FILLED slot does. */
    private static void resolveCoach(
            RunContext ctx, PlayerAssignment target, Group selectedGroup, List<Group> allGroupsAscending, List<UnmetWish> out) {
        Set<Long> seenCoachPersonIds = new LinkedHashSet<>();
        for (CoachWish wish : ctx.solution().getCoachWishes()) {
            if (wish.participantProfileId() != target.getId()) {
                continue;
            }
            if (wish.type() != CoachWishType.MUST && wish.type() != CoachWishType.WANT) {
                continue;
            }
            if (!seenCoachPersonIds.add(wish.coachPersonId())) {
                continue; // dedupe: MUST+WANT for the same coach (unusual, but keep one entry).
            }
            if (currentGroupHasCoach(ctx, selectedGroup, wish.coachPersonId())) {
                continue; // already satisfied.
            }
            String key = wish.type() == CoachWishType.MUST ? ConstraintKeys.COACH_WISH_REQUIRED : ConstraintKeys.COACH_PREFERENCE_SOFT;
            List<Group> candidates = allGroupsAscending.stream()
                    .filter(g -> g != selectedGroup && currentGroupHasCoach(ctx, g, wish.coachPersonId()))
                    .toList();
            out.add(new UnmetWish(key, "COACH", null, wish.coachPersonId(), candidates, false, true));
        }
    }

    private static boolean currentGroupHasCoach(RunContext ctx, Group group, long coachPersonId) {
        for (CoachSlot slot : ctx.solution().getCoachSlots()) {
            if (slot.getGroup() == group && slot.getCoach() != null && slot.getCoach().personId() == coachPersonId) {
                return true;
            }
        }
        return false;
    }

    private static Long timeSlotIdOf(RunContext ctx, Group group) {
        return ctx.solution().getGroupSchedules().stream()
                .filter(gs -> gs.getGroup() == group && gs.getTrainingBlock() != null)
                .map(gs -> gs.getTrainingBlock().timeSlotId())
                .findFirst()
                .orElse(null);
    }

    private static PlayerAssignment playerById(RunContext ctx, long solverId) {
        return ctx.solution().getPlayerAssignments().stream().filter(pa -> pa.getId() == solverId).findFirst().orElse(null);
    }
}
