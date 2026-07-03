package se.klubb.groupplanner.explain;

import ai.timefold.solver.core.api.score.stream.ConstraintJustification;
import se.klubb.groupplanner.solver.constraints.Justifications;
import se.klubb.groupplanner.solver.domain.Group;

/**
 * Renders every {@link ConstraintJustification} record in {@code se.klubb.groupplanner.solver
 * .constraints.Justifications} (19 record types, covering all 33 code-implemented constraints via
 * shared shapes — e.g. one {@code PairWishBrokenJustification} covers both {@code sameGroupHard} and
 * {@code differentGroupHard} via its {@code type} field) into the exact Swedish sentence shapes
 * kravspec §17.2/§17.3 show ("Kalles nivåscore 640 matchar Grupp Y:s spann 600-690", "Kompisönskemål
 * Kalle-Lisa brutet", "Grupp C är full: 12/12", "Grupp C blir 12, max är 11").
 *
 * <p>This is the ONLY place a {@code ConstraintJustification} is turned into user-facing text — every
 * field in every justification record is an id/name/count/level (never free text a council member
 * wrote), so this formatter can never leak a comment (CLAUDE.md confidentiality rule; see
 * backend/docs/m7-notes.md's leak-assertion test).
 *
 * <p>"Positive factor" sentences that are NOT derived from a constraint match at all (e.g. "Kalles
 * nivåscore 640 matchar Grupp Y:s spann 600-690", a statement computed by ABSENCE of a levelBalance
 * match plus a direct level/band comparison, per design §11.2) are built separately in {@link
 * ExplanationService} — this class only ever formats an ACTUAL {@link ConstraintJustification}
 * instance Timefold produced.
 */
final class JustificationMessages {

    private JustificationMessages() {
    }

    /** Same rendering as {@link #toSwedish}, except for the "wish" justification family (pair
     * wishes, coach wishes) where a "newlyFixed" match means the wish just became SATISFIED — the
     * plain {@link #toSwedish} text ("...brutet") would misleadingly describe a state that has just
     * stopped being true, so this variant rephrases those specific cases as fulfillment (matching
     * design §12.2's own example: {@code "Kompisönskemål med Lisa uppfylls"}). Every other
     * justification type reuses {@link #toSwedish} verbatim: describing the current match's DATA
     * (e.g. a level-spread number) is equally informative in either direction. */
    static String toSwedishAsFixed(ConstraintJustification justification, SolutionIndex idx) {
        return switch (justification) {
            case Justifications.PairWishBrokenJustification j -> pairWishFulfilledMessage(j.aParticipantId(), j.bParticipantId(), j.type(), idx);
            case Justifications.PairWishSoftJustification j -> pairWishFulfilledMessage(j.aParticipantId(), j.bParticipantId(), j.type(), idx);
            case Justifications.CoachWishJustification j -> coachWishFulfilledMessage(j, idx);
            default -> toSwedish(justification, idx);
        };
    }

    private static String pairWishFulfilledMessage(long aId, long bId, String type, SolutionIndex idx) {
        String a = idx.participantName(aId);
        String b = idx.participantName(bId);
        boolean sameGroup = "MUST_SAME".equals(type) || "WANT_SAME".equals(type);
        return sameGroup
                ? "Kompisönskemål med %s uppfylls (%s och %s hamnar i samma grupp)".formatted(b, a, b)
                : "Önskemål om olika grupper uppfylls (%s och %s hamnar INTE i samma grupp)".formatted(a, b);
    }

    private static String coachWishFulfilledMessage(Justifications.CoachWishJustification j, SolutionIndex idx) {
        String participant = idx.participantName(j.participantId());
        String coach = idx.personName(j.coachPersonId());
        return "CANNOT".equals(j.type())
                ? "%s slipper förbjuden tränare %s".formatted(participant, coach)
                : "%s får tränare %s".formatted(participant, coach);
    }

    static String toSwedish(ConstraintJustification justification, SolutionIndex idx) {
        return switch (justification) {
            case Justifications.BlockDoubleBookedJustification j -> "%s och %s krockar på samma tid/bana"
                    .formatted(idx.groupName(j.groupIdA()), idx.groupName(j.groupIdB()));

            case Justifications.GroupOverMaxJustification j -> "%s blir %d, max är %d"
                    .formatted(idx.groupName(j.groupId()), j.size(), j.maxSize());

            case Justifications.TimeUnavailableJustification j -> "%s kan inte träna på %s (grupp %s)"
                    .formatted(idx.participantName(j.participantId()), idx.timeSlotLabel(j.timeSlotId()), idx.groupName(j.groupId()));

            case Justifications.PairWishBrokenJustification j -> pairWishMessage(j.aParticipantId(), j.bParticipantId(), j.type(), true, idx);

            case Justifications.CoachDoubleBookedJustification j -> "%s dubbelbokad: grupp %s och grupp %s (%s)"
                    .formatted(idx.personName(j.coachPersonId()), idx.groupName(j.groupIdA()), idx.groupName(j.groupIdB()), j.timeLabel());

            case Justifications.PlayerDoubleBookedJustification j -> "%s dubbelbokad: grupp %s och grupp %s"
                    .formatted(idx.personName(j.personId()), idx.groupName(j.groupIdA()), idx.groupName(j.groupIdB()));

            case Justifications.TrainAndCoachClashJustification j -> "%s coachar grupp %s samtidigt som personen själv tränar i grupp %s (%s)"
                    .formatted(idx.personName(j.personId()), idx.groupName(j.coachGroupId()), idx.groupName(j.playGroupId()), j.timeLabel());

            case Justifications.CoachUnavailableJustification j -> "%s är inte tillgänglig på %s (grupp %s)"
                    .formatted(idx.personName(j.coachPersonId()), idx.timeSlotLabel(j.timeSlotId()), idx.groupName(j.groupId()));

            case Justifications.MissingCoachJustification j -> "%s saknar tränare (plats %d)"
                    .formatted(idx.groupName(j.groupId()), j.slotIndex() + 1);

            case Justifications.CoachOverloadedJustification j -> "%s är tilldelad %d grupper, max är %d"
                    .formatted(idx.personName(j.coachPersonId()), j.groups(), j.maxGroups());

            case Justifications.CoachWishJustification j -> coachWishMessage(j, idx);

            case Justifications.SavedPlanClashJustification j -> "Krockar med sparad plan \"%s\" (%s): %s"
                    .formatted(j.sourcePlanName(), j.timeLabel(), idx.groupName(j.groupId()));

            case Justifications.UnassignedPlayerJustification j -> "%s är oplacerad (kölista), prioritet %d"
                    .formatted(j.displayName(), j.priority());

            case Justifications.GroupSizeDeviationJustification j -> "%s har %d spelare, målstorlek är %d"
                    .formatted(idx.groupName(j.groupId()), j.size(), j.targetSize());

            case Justifications.GroupUnderMinJustification j -> "%s har %d spelare, minsta storlek är %d"
                    .formatted(idx.groupName(j.groupId()), j.size(), j.minSize());

            case Justifications.LevelSpreadJustification j -> "Nivåspridning i %s är %d poäng (nivåsnitt %s)"
                    .formatted(idx.groupName(j.groupId()), j.spreadPoints(), formatLevel(j.meanScaled()));

            case Justifications.GroupOrderInversionJustification j -> "%s har inte högre nivå än %s (skillnad %d poäng)"
                    .formatted(idx.groupName(j.higherGroupId()), idx.groupName(j.lowerGroupId()), j.meanDiffPoints());

            case Justifications.ContinuityJustification j -> "%s flyttades %d steg från sin tidigare grupp"
                    .formatted(idx.participantName(j.participantId()), Math.abs(j.newGroupOrder() - j.previousGroupOrder()));

            case Justifications.TimePreferenceMissedJustification j -> "%s fick inte sin föredragna tid i %s (tid: %s)"
                    .formatted(idx.participantName(j.participantId()), idx.groupName(j.groupId()), idx.timeSlotLabel(j.timeSlotId()));

            case Justifications.PairWishSoftJustification j -> pairWishMessage(j.aParticipantId(), j.bParticipantId(), j.type(), false, idx);

            case Justifications.CoachLevelMismatchJustification j -> "%s:s nivåspann (%s-%s) passar inte %s:s nivåsnitt %s"
                    .formatted(idx.personName(j.coachPersonId()), formatLevel(j.canMinScaled()), formatLevel(j.canMaxScaled()),
                            idx.groupName(j.groupId()), formatLevel(j.groupMeanScaled()));

            case Justifications.LateTimeJustification j -> "TOP_LATE_PENALIZED".equals(j.direction())
                    ? "%s hamnade på en sen tid (%s)".formatted(idx.groupName(j.groupId()), j.timeLabel())
                    : "%s fick en sen tid som passar en lägre grupp (%s)".formatted(idx.groupName(j.groupId()), j.timeLabel());

            case Justifications.CoachPreferredTimeSlotJustification j -> "%s fick sin föredragna tid för %s"
                    .formatted(idx.personName(j.coachPersonId()), idx.groupName(j.groupId()));

            case Justifications.CoachUnknownTimeSlotJustification j -> "%s har inte angett tillgänglighet för %s (grupp %s)"
                    .formatted(idx.personName(j.coachPersonId()), idx.timeSlotLabel(j.timeSlotId()), idx.groupName(j.groupId()));

            default -> justification.toString();
        };
    }

    /** Uses "brutet" uniformly for hard AND soft pair wishes (kravspec §17.2's own worked example
     * phrases a SOFT friend wish exactly this way: "Kompisönskemål Kalle-Lisa brutet") — hard/soft
     * is already visible separately via the match's constraint key/level, so the sentence itself
     * doesn't need to hedge. */
    private static String pairWishMessage(long aId, long bId, String type, boolean hard, SolutionIndex idx) {
        String a = idx.participantName(aId);
        String b = idx.participantName(bId);
        String verb = switch (type) {
            case "MUST_SAME", "WANT_SAME" -> "Kompisönskemål";
            default -> "Önskemål om olika grupper";
        };
        return "%s %s–%s brutet".formatted(verb, a, b);
    }

    private static String coachWishMessage(Justifications.CoachWishJustification j, SolutionIndex idx) {
        String participant = idx.participantName(j.participantId());
        String coach = idx.personName(j.coachPersonId());
        return switch (j.type()) {
            case "MUST" -> "%s måste ha tränare %s, men fick det inte".formatted(participant, coach);
            case "CANNOT" -> "%s fick förbjuden tränare %s".formatted(participant, coach);
            default -> j.fulfilled()
                    ? "%s fick önskad tränare %s".formatted(participant, coach)
                    : "%s fick inte önskad tränare %s".formatted(participant, coach);
        };
    }

    /** Renders a x100-scaled level int back to the 0-1000 display scale with one decimal, e.g.
     * {@code 64000 -> "640,0"} - Swedish decimal comma, matching the UI's own level formatting
     * convention (CLAUDE.md determinism rule: this is display-layer formatting from an already
     * scaled int, never a new floating-point computation). */
    static String formatLevel(long scaled) {
        long whole = scaled / 100;
        long frac = Math.abs(scaled % 100) / 10;
        return whole + "," + frac;
    }

    /** Group fact convenience used by {@link ExplanationService} for "full" narratives. */
    static boolean isFull(Group group, int currentSize) {
        return currentSize >= group.maxSize();
    }
}
