package se.klubb.groupplanner.explain;

import java.util.HashMap;
import java.util.Map;
import se.klubb.groupplanner.solver.domain.CoachFact;
import se.klubb.groupplanner.solver.domain.Group;
import se.klubb.groupplanner.solver.domain.GroupPlanSolution;
import se.klubb.groupplanner.solver.domain.PlayerAssignment;
import se.klubb.groupplanner.solver.domain.TrainingBlock;

/**
 * Solver-long-id -&gt; human-readable-name lookups built once per {@link GroupPlanSolution}, shared by
 * {@link JustificationMessages} and {@link ExplanationService}/{@link WhatIfService} so a message
 * never has to re-scan the whole solution to resolve a participant/group/coach/time-slot id carried
 * inside a {@code ConstraintJustification} record (which, per design, carries ids only — never names,
 * so it survives serialization cleanly).
 */
final class SolutionIndex {

    private final Map<Long, PlayerAssignment> playerByParticipantId = new HashMap<>();
    private final Map<Long, String> displayNameByPersonId = new HashMap<>();
    private final Map<Long, Group> groupById = new HashMap<>();
    private final Map<Long, String> timeLabelBySlotId = new HashMap<>();

    private SolutionIndex() {
    }

    static SolutionIndex of(GroupPlanSolution solution) {
        SolutionIndex idx = new SolutionIndex();
        for (PlayerAssignment pa : solution.getPlayerAssignments()) {
            idx.playerByParticipantId.put(pa.getId(), pa);
            idx.displayNameByPersonId.put(pa.getPersonId(), pa.getDisplayName());
        }
        for (CoachFact coach : solution.getCoaches()) {
            idx.displayNameByPersonId.put(coach.personId(), coach.displayName());
        }
        for (Group group : solution.getGroups()) {
            idx.groupById.put(group.id(), group);
        }
        // One label per time slot, independent of which court's block happens to be picked: a
        // block's own label embeds its court name ("Torsdag 18.00-19.30, Bana 2"), so the court
        // suffix is stripped - the remaining prefix is the time-only portion, shared by every block
        // on that slot.
        for (TrainingBlock block : solution.getTrainingBlocks()) {
            idx.timeLabelBySlotId.computeIfAbsent(block.timeSlotId(), id -> timeOnlyLabel(block.label()));
        }
        return idx;
    }

    private static String timeOnlyLabel(String blockLabel) {
        int comma = blockLabel.indexOf(',');
        return comma < 0 ? blockLabel : blockLabel.substring(0, comma).strip();
    }

    PlayerAssignment player(long participantId) {
        return playerByParticipantId.get(participantId);
    }

    String participantName(long participantId) {
        PlayerAssignment pa = playerByParticipantId.get(participantId);
        return pa != null ? pa.getDisplayName() : ("okänd spelare #" + participantId);
    }

    String personName(long personId) {
        return displayNameByPersonId.getOrDefault(personId, "okänd person #" + personId);
    }

    Group group(long groupId) {
        return groupById.get(groupId);
    }

    String groupName(long groupId) {
        Group g = groupById.get(groupId);
        return g != null ? g.name() : ("okänd grupp #" + groupId);
    }

    String timeSlotLabel(long timeSlotId) {
        return timeLabelBySlotId.getOrDefault(timeSlotId, "okänd tid #" + timeSlotId);
    }
}
