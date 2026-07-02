package se.klubb.groupplanner.savedplan;

import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import se.klubb.groupplanner.api.error.NotFoundException;
import se.klubb.groupplanner.domain.ActivityPlan;
import se.klubb.groupplanner.domain.CoachAssignment;
import se.klubb.groupplanner.domain.CoachProfile;
import se.klubb.groupplanner.domain.ParticipantProfile;
import se.klubb.groupplanner.domain.PlayerAssignment;
import se.klubb.groupplanner.domain.SavedPlan;
import se.klubb.groupplanner.domain.SavedPlanResourceUsage;
import se.klubb.groupplanner.domain.TimeSlot;
import se.klubb.groupplanner.domain.TrainingBlock;
import se.klubb.groupplanner.domain.TrainingGroup;
import se.klubb.groupplanner.repo.ActivityPlanRepository;
import se.klubb.groupplanner.repo.CoachAssignmentRepository;
import se.klubb.groupplanner.repo.CoachProfileRepository;
import se.klubb.groupplanner.repo.ParticipantProfileRepository;
import se.klubb.groupplanner.repo.PlayerAssignmentRepository;
import se.klubb.groupplanner.repo.SavedPlanRepository;
import se.klubb.groupplanner.repo.SavedPlanResourceUsageRepository;
import se.klubb.groupplanner.repo.TimeSlotRepository;
import se.klubb.groupplanner.repo.TrainingBlockRepository;
import se.klubb.groupplanner.repo.TrainingGroupRepository;
import se.klubb.groupplanner.util.Uuid7;

/**
 * Minimal internal materialization service (docs/design/04-solver.md §6.2, spec §14.1/§14.3): turns
 * an {@link ActivityPlan}'s CURRENT persisted assignments (whatever they are right now — solved,
 * manually edited, or partially placed) into one {@link SavedPlan} row plus its {@link
 * SavedPlanResourceUsage} rows, so cross-plan blocking (§10.24) and season conflict reporting have
 * something to read.
 *
 * <p><b>Scope note (M6b, per the milestone brief):</b> this is deliberately NOT the full "spara
 * plan" flow spec §14.1 describes (snapshot JSON of groups/weights/locks/score, status-transition
 * UI, re-open/restore) — that is M8. This service exists purely so {@code SolverInputAssembler} and
 * {@code ConflictService}-adjacent tests can exercise real {@code saved_plan}/{@code
 * saved_plan_resource_usage} rows instead of hand-built solver facts. {@code snapshot_json}/{@code
 * score}/{@code optimization_run_id} are left {@code null} here; M8 fills them in.
 */
@Service
public class SavedPlanService {

    private final ActivityPlanRepository activityPlanRepository;
    private final TrainingGroupRepository trainingGroupRepository;
    private final PlayerAssignmentRepository playerAssignmentRepository;
    private final CoachAssignmentRepository coachAssignmentRepository;
    private final ParticipantProfileRepository participantProfileRepository;
    private final CoachProfileRepository coachProfileRepository;
    private final TrainingBlockRepository trainingBlockRepository;
    private final TimeSlotRepository timeSlotRepository;
    private final SavedPlanRepository savedPlanRepository;
    private final SavedPlanResourceUsageRepository savedPlanResourceUsageRepository;

    public SavedPlanService(
            ActivityPlanRepository activityPlanRepository,
            TrainingGroupRepository trainingGroupRepository,
            PlayerAssignmentRepository playerAssignmentRepository,
            CoachAssignmentRepository coachAssignmentRepository,
            ParticipantProfileRepository participantProfileRepository,
            CoachProfileRepository coachProfileRepository,
            TrainingBlockRepository trainingBlockRepository,
            TimeSlotRepository timeSlotRepository,
            SavedPlanRepository savedPlanRepository,
            SavedPlanResourceUsageRepository savedPlanResourceUsageRepository) {
        this.activityPlanRepository = activityPlanRepository;
        this.trainingGroupRepository = trainingGroupRepository;
        this.playerAssignmentRepository = playerAssignmentRepository;
        this.coachAssignmentRepository = coachAssignmentRepository;
        this.participantProfileRepository = participantProfileRepository;
        this.coachProfileRepository = coachProfileRepository;
        this.trainingBlockRepository = trainingBlockRepository;
        this.timeSlotRepository = timeSlotRepository;
        this.savedPlanRepository = savedPlanRepository;
        this.savedPlanResourceUsageRepository = savedPlanResourceUsageRepository;
    }

    /** Materializes a NEW {@link SavedPlan} row (status defaults to {@link SavedPlan#STATUS_SAVED}
     * when {@code status} is blank) from {@code activityPlanId}'s current assignments. Only a
     * player/coach whose group has an actually-scheduled training block produces a usage row —
     * anyone still unplaced/unscheduled contributes nothing to block, matching the "usage" concept
     * (you can't block a time you never occupied). */
    @Transactional
    public SavedPlan materialize(String activityPlanId, String name, String status) {
        ActivityPlan plan = activityPlanRepository.findById(activityPlanId)
                .orElseThrow(() -> new NotFoundException("Activity plan not found: " + activityPlanId));

        Instant now = Instant.now();
        SavedPlan saved = new SavedPlan(
                Uuid7.generate(),
                activityPlanId,
                (name != null && !name.isBlank()) ? name : plan.name(),
                (status != null && !status.isBlank()) ? status : SavedPlan.STATUS_SAVED,
                null,
                null,
                null,
                now.toString(),
                now.toString());
        savedPlanRepository.insert(saved);

        Map<String, TrainingGroup> groupById = new HashMap<>();
        trainingGroupRepository.findByActivityPlanId(activityPlanId).forEach(g -> groupById.put(g.id(), g));
        Map<String, ParticipantProfile> participantById = new HashMap<>();
        participantProfileRepository.findByActivityPlanId(activityPlanId).forEach(p -> participantById.put(p.id(), p));
        Map<String, CoachProfile> coachProfileById = new HashMap<>();
        coachProfileRepository.findByActivityPlanId(activityPlanId).forEach(c -> coachProfileById.put(c.id(), c));

        for (PlayerAssignment pa : playerAssignmentRepository.findByActivityPlanId(activityPlanId)) {
            if (pa.groupId() == null) {
                continue; // waitlisted: no time/court occupied, nothing to block.
            }
            resolveSchedule(groupById.get(pa.groupId())).ifPresent(schedule -> {
                ParticipantProfile participant = participantById.get(pa.participantProfileId());
                if (participant != null) {
                    insertUsage(saved.id(), participant.personId(), SavedPlanResourceUsage.ROLE_PLAYER, schedule);
                }
            });
        }

        for (CoachAssignment ca : coachAssignmentRepository.findByActivityPlanId(activityPlanId)) {
            resolveSchedule(groupById.get(ca.groupId())).ifPresent(schedule -> {
                CoachProfile coach = coachProfileById.get(ca.coachProfileId());
                if (coach != null) {
                    insertUsage(saved.id(), coach.personId(), SavedPlanResourceUsage.ROLE_COACH, schedule);
                }
            });
        }

        return saved;
    }

    private java.util.Optional<ResolvedSchedule> resolveSchedule(TrainingGroup group) {
        if (group == null || group.assignedTrainingBlockId() == null) {
            return java.util.Optional.empty();
        }
        return trainingBlockRepository.findById(group.assignedTrainingBlockId())
                .flatMap(block -> timeSlotRepository.findById(block.timeSlotId())
                        .map(slot -> new ResolvedSchedule(block, slot)));
    }

    private void insertUsage(String savedPlanId, String personId, String role, ResolvedSchedule schedule) {
        TimeSlot slot = schedule.slot();
        TrainingBlock block = schedule.block();
        savedPlanResourceUsageRepository.insert(new SavedPlanResourceUsage(
                Uuid7.generate(), savedPlanId, personId, role, slot.dayOfWeek(), slot.date(), slot.startTime(),
                slot.endTime(), block.courtId()));
    }

    private record ResolvedSchedule(TrainingBlock block, TimeSlot slot) {
    }
}
