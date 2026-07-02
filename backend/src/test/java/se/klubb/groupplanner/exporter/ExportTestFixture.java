package se.klubb.groupplanner.exporter;

import java.util.List;
import se.klubb.groupplanner.domain.ParticipantProfile;
import se.klubb.groupplanner.domain.TrainingBlock;
import se.klubb.groupplanner.domain.TrainingGroup;
import se.klubb.groupplanner.repo.CoachAssignmentRepository;
import se.klubb.groupplanner.repo.CoachProfileRepository;
import se.klubb.groupplanner.repo.ParticipantProfileRepository;
import se.klubb.groupplanner.repo.PlayerAssignmentRepository;
import se.klubb.groupplanner.repo.TrainingBlockRepository;
import se.klubb.groupplanner.repo.TrainingGroupRepository;

/**
 * Shared test-only scheduling helper for the exporter test suite: loads {@code small-10} (via the
 * caller-owned {@code TestDatasetLoader}) then deterministically places some — but deliberately NOT
 * all — participants, so every export test has a real Kölista section to assert on (unlike {@code
 * season.ConflictServiceTest}'s {@code scheduleEvenly}, which places everyone).
 */
final class ExportTestFixture {

    private ExportTestFixture() {
    }

    /** Places the first 8 of small-10's 10 participants into 2 groups (4 each, both groups
     * scheduled + coached); the last 2 participants are left unassigned (Kölista). Returns the
     * unassigned participants' ids, for tests that want to name them explicitly. */
    static List<String> scheduleSomeLeaveOthersWaitlisted(
            String planId,
            TrainingGroupRepository trainingGroupRepository,
            TrainingBlockRepository trainingBlockRepository,
            ParticipantProfileRepository participantProfileRepository,
            PlayerAssignmentRepository playerAssignmentRepository,
            CoachProfileRepository coachProfileRepository,
            CoachAssignmentRepository coachAssignmentRepository) {
        List<TrainingGroup> groups = trainingGroupRepository.findByActivityPlanId(planId);
        List<TrainingBlock> blocks = trainingBlockRepository.findByActivityPlanId(planId);
        for (int i = 0; i < groups.size() && i < blocks.size(); i++) {
            trainingGroupRepository.lockToBlock(groups.get(i).id(), blocks.get(i).id());
        }
        List<se.klubb.groupplanner.domain.CoachProfile> coaches = coachProfileRepository.findByActivityPlanId(planId);
        for (int i = 0; i < coaches.size(); i++) {
            coachAssignmentRepository.lockToGroup(coaches.get(i).id(), groups.get(i % groups.size()).id());
        }

        List<ParticipantProfile> participants = participantProfileRepository.findByActivityPlanId(planId);
        int placedCount = Math.min(8, participants.size());
        for (int i = 0; i < placedCount; i++) {
            playerAssignmentRepository.lockToGroup(participants.get(i).id(), groups.get(i % groups.size()).id());
        }
        List<String> waitlistedIds = new java.util.ArrayList<>();
        for (int i = placedCount; i < participants.size(); i++) {
            playerAssignmentRepository.insertImportedIfAbsent(participants.get(i).id());
            waitlistedIds.add(participants.get(i).id());
        }
        return waitlistedIds;
    }
}
