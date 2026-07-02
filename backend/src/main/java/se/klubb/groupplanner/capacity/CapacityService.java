package se.klubb.groupplanner.capacity;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.stereotype.Service;
import se.klubb.groupplanner.api.error.NotFoundException;
import se.klubb.groupplanner.capacity.CapacityResponse.TimeSlotCapacityView;
import se.klubb.groupplanner.domain.ActivityPlan;
import se.klubb.groupplanner.domain.CoachProfile;
import se.klubb.groupplanner.domain.CoachTimeSlot;
import se.klubb.groupplanner.domain.ParticipantProfile;
import se.klubb.groupplanner.domain.TimeSlot;
import se.klubb.groupplanner.repo.ActivityPlanRepository;
import se.klubb.groupplanner.repo.CoachProfileRepository;
import se.klubb.groupplanner.repo.CoachTimeSlotRepository;
import se.klubb.groupplanner.repo.ParticipantProfileRepository;
import se.klubb.groupplanner.repo.TimeSlotRepository;
import se.klubb.groupplanner.repo.TrainingBlockRepository;

/**
 * Pre-solve capacity analysis (spec §12.4) — {@code GET /api/plans/{planId}/capacity}.
 *
 * <h2>waitlistRisk</h2>
 * {@code participantCount} ("Antal anmälda", spec §12.4 — ALL registered participants, including
 * already-waitlisted ones; the waitlisted subset is reported separately as {@code waitlistedCount},
 * M5 review fix) vs. {@code targetCapacity = activeBlocks × targetSize} and {@code maxCapacity =
 * activeBlocks × maxSize} (both from the plan's default group sizes): {@code NONE} (fits target),
 * {@code OVER_TARGET} (fits max but not target — spec §12.4's own worked example: 122/11
 * blocks/target 10/max 12 -&gt; 110/132 -&gt; "Möjligt, men grupperna blir större än target"),
 * {@code OVER_MAX} ("Fler anmälda än maxkapacitet — kölista krävs"), or {@code UNKNOWN} if the plan
 * has no default target/max size configured yet.
 *
 * <h2>coachShortageRisk (MVP estimate, docs/plan.md M5 row)</h2>
 * {@code groupsRequiringCoachEstimate = activeTrainingBlockCount} (MVP: 1 coach per group/block).
 * This is a full bipartite-matching problem in general (coach availability per slot × per-coach
 * daily/weekly caps); a full solver-grade feasibility check is out of scope for a pre-solve
 * <em>estimate</em>, so two simpler, complementary signals are combined instead (an accepted MVP
 * approximation, not exact — documented in backend/docs/m5-notes.md):
 * <ol>
 *   <li><b>Per-slot signal</b>: for any time slot with active blocks, are there at least as many
 *       coaches NOT marked {@code UNAVAILABLE} at that slot as there are active blocks? Neutral/
 *       unlisted counts as available (M5 review fix) — the same semantics the solver will use, whose
 *       CoachFact carries only {@code unavailableTimeSlotIds} (docs/design/04-solver.md). Catches
 *       "coaches exist, but not at the times that need them" without false-positives for coaches who
 *       simply haven't filled in availability yet.</li>
 *   <li><b>Aggregate signal</b>: sum each coach's effective weekly capacity ({@code
 *       maxGroupsPerWeek} if set, else {@code maxGroupsPerDay × distinct days with active blocks} if
 *       set, else unlimited) against {@code groupsRequiringCoachEstimate}. Catches "coaches are at
 *       the right times, but their combined max-groups limits are too low".</li>
 * </ol>
 */
@Service
public class CapacityService {

    private final ActivityPlanRepository activityPlanRepository;
    private final ParticipantProfileRepository participantProfileRepository;
    private final TrainingBlockRepository trainingBlockRepository;
    private final TimeSlotRepository timeSlotRepository;
    private final CoachProfileRepository coachProfileRepository;
    private final CoachTimeSlotRepository coachTimeSlotRepository;

    public CapacityService(
            ActivityPlanRepository activityPlanRepository,
            ParticipantProfileRepository participantProfileRepository,
            TrainingBlockRepository trainingBlockRepository,
            TimeSlotRepository timeSlotRepository,
            CoachProfileRepository coachProfileRepository,
            CoachTimeSlotRepository coachTimeSlotRepository) {
        this.activityPlanRepository = activityPlanRepository;
        this.participantProfileRepository = participantProfileRepository;
        this.trainingBlockRepository = trainingBlockRepository;
        this.timeSlotRepository = timeSlotRepository;
        this.coachProfileRepository = coachProfileRepository;
        this.coachTimeSlotRepository = coachTimeSlotRepository;
    }

    public CapacityResponse compute(String activityPlanId) {
        ActivityPlan plan = activityPlanRepository.findById(activityPlanId)
                .orElseThrow(() -> new NotFoundException("Activity plan not found: " + activityPlanId));

        List<ParticipantProfile> participants = participantProfileRepository.findByActivityPlanId(activityPlanId);
        int participantCount = participants.size(); // "Antal anmälda" - includes waitlisted (M5 review fix).
        int waitlistedCount = (int) participants.stream().filter(ParticipantProfile::waitlisted).count();
        int activeBlocks = trainingBlockRepository.countActiveByActivityPlanId(activityPlanId);
        Integer targetSize = plan.defaultGroupTargetSize();
        Integer maxSize = plan.defaultGroupMaxSize();
        Integer targetCapacity = targetSize != null ? activeBlocks * targetSize : null;
        Integer maxCapacity = maxSize != null ? activeBlocks * maxSize : null;

        String waitlistRisk;
        String waitlistMessage;
        if (targetCapacity == null || maxCapacity == null) {
            waitlistRisk = CapacityResponse.RISK_UNKNOWN;
            waitlistMessage = "Standardstorlekar (target/max) saknas för planen - kapacitet kan inte beräknas";
        } else if (participantCount > maxCapacity) {
            waitlistRisk = CapacityResponse.RISK_OVER_MAX;
            waitlistMessage = "Fler anmälda än maxkapacitet — kölista krävs";
        } else if (participantCount > targetCapacity) {
            waitlistRisk = CapacityResponse.RISK_OVER_TARGET;
            waitlistMessage = "Möjligt, men grupperna blir större än target";
        } else {
            waitlistRisk = CapacityResponse.RISK_NONE;
            waitlistMessage = "Kapacitet räcker till alla anmälda";
        }

        List<CoachProfile> coaches = coachProfileRepository.findByActivityPlanId(activityPlanId);
        int coachCount = coaches.size();
        int groupsRequiringCoachEstimate = activeBlocks;

        List<TimeSlot> slots = timeSlotRepository.findByActivityPlanId(activityPlanId);
        Map<String, Integer> activeBlocksBySlot = trainingBlockRepository.countActiveByTimeSlotForPlan(activityPlanId);
        Map<String, Integer> coachesUnavailableBySlot =
                coachTimeSlotRepository.countKindByTimeSlotForPlan(activityPlanId, CoachTimeSlot.UNAVAILABLE);
        Map<String, Integer> coachesPreferredBySlot =
                coachTimeSlotRepository.countKindByTimeSlotForPlan(activityPlanId, CoachTimeSlot.PREFERRED);

        List<TimeSlotCapacityView> perSlot = new ArrayList<>();
        List<String> deficientSlotLabels = new ArrayList<>();
        Set<String> distinctActiveDayKeys = new HashSet<>();
        for (TimeSlot slot : slots) {
            int blocksAtSlot = activeBlocksBySlot.getOrDefault(slot.id(), 0);
            // Neutral/unlisted = available (see class Javadoc): available = all coaches minus the
            // ones explicitly marked UNAVAILABLE at this slot.
            int coachesAtSlot = coachCount - coachesUnavailableBySlot.getOrDefault(slot.id(), 0);
            int preferredAtSlot = coachesPreferredBySlot.getOrDefault(slot.id(), 0);
            perSlot.add(new TimeSlotCapacityView(slot.id(), slot.label(), blocksAtSlot, coachesAtSlot, preferredAtSlot));
            if (blocksAtSlot > 0) {
                distinctActiveDayKeys.add(slot.dayOfWeek() != null ? slot.dayOfWeek() : slot.date());
                if (coachesAtSlot < blocksAtSlot) {
                    deficientSlotLabels.add(slot.label());
                }
            }
        }
        int distinctActiveDays = Math.max(distinctActiveDayKeys.size(), 1);

        long aggregateCoachCapacity = 0;
        for (CoachProfile coach : coaches) {
            aggregateCoachCapacity += effectiveWeeklyCapacity(coach, distinctActiveDays, activeBlocks);
        }
        boolean aggregateShortage = groupsRequiringCoachEstimate > 0 && aggregateCoachCapacity < groupsRequiringCoachEstimate;
        boolean perSlotShortage = !deficientSlotLabels.isEmpty();
        boolean coachShortageRisk = aggregateShortage || perSlotShortage;

        String coachShortageMessage;
        if (!coachShortageRisk) {
            coachShortageMessage = "Tillräckligt med tränare för samtliga grupper";
        } else {
            List<String> reasons = new ArrayList<>();
            if (perSlotShortage) {
                reasons.add("för få tillgängliga tränare vid: " + String.join(", ", deficientSlotLabels));
            }
            if (aggregateShortage) {
                reasons.add("tränarnas sammanlagda maxantal grupper (" + aggregateCoachCapacity
                        + ") räcker inte till de " + groupsRequiringCoachEstimate + " grupper som behövs");
            }
            coachShortageMessage = "Risk för tränarbrist: " + String.join("; ", reasons);
        }

        return new CapacityResponse(
                participantCount, waitlistedCount, activeBlocks, targetSize, maxSize, targetCapacity, maxCapacity,
                waitlistRisk, waitlistMessage, coachCount, groupsRequiringCoachEstimate,
                coachShortageRisk, coachShortageMessage, perSlot);
    }

    /**
     * A coach's estimated total usable groups across the plan's active days: {@code
     * maxGroupsPerWeek} if configured; else {@code maxGroupsPerDay × distinctActiveDays} if
     * configured; else "unlimited", modeled as {@code totalActiveBlocks} (a safe sentinel - a coach
     * can never usefully exceed the total number of groups that exist).
     */
    private static long effectiveWeeklyCapacity(CoachProfile coach, int distinctActiveDays, int totalActiveBlocks) {
        if (coach.maxGroupsPerWeek() != null) {
            return coach.maxGroupsPerWeek();
        }
        if (coach.maxGroupsPerDay() != null) {
            return (long) coach.maxGroupsPerDay() * distinctActiveDays;
        }
        return totalActiveBlocks;
    }
}
