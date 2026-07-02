package se.klubb.groupplanner.season;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import org.springframework.stereotype.Service;
import se.klubb.groupplanner.common.time.TimeKey;
import se.klubb.groupplanner.domain.ActivityPlan;
import se.klubb.groupplanner.domain.CoachAssignment;
import se.klubb.groupplanner.domain.CoachProfile;
import se.klubb.groupplanner.domain.Court;
import se.klubb.groupplanner.domain.ParticipantProfile;
import se.klubb.groupplanner.domain.Person;
import se.klubb.groupplanner.domain.PlayerAssignment;
import se.klubb.groupplanner.domain.TimeSlot;
import se.klubb.groupplanner.domain.TrainingBlock;
import se.klubb.groupplanner.domain.TrainingGroup;
import se.klubb.groupplanner.repo.ActivityPlanRepository;
import se.klubb.groupplanner.repo.CoachAssignmentRepository;
import se.klubb.groupplanner.repo.CoachProfileRepository;
import se.klubb.groupplanner.repo.CourtRepository;
import se.klubb.groupplanner.repo.ParticipantProfileRepository;
import se.klubb.groupplanner.repo.PersonRepository;
import se.klubb.groupplanner.repo.PlayerAssignmentRepository;
import se.klubb.groupplanner.repo.TimeSlotRepository;
import se.klubb.groupplanner.repo.TrainingBlockRepository;
import se.klubb.groupplanner.repo.TrainingGroupRepository;
import se.klubb.groupplanner.resources.TimeSlotLabelFormatter;

/**
 * Season-level conflict detection (docs/design/04-solver.md §6.3, spec §19.2/§14.4) — a plain
 * reporting service, no Timefold dependency. Unlike in-solver cross-plan blocking (which only reads
 * LOCKED {@code saved_plan} snapshots, §10.24), this reads the CURRENT persisted assignments of
 * EVERY {@link ActivityPlan} in a season regardless of status (also {@code draft}s — the design's
 * own rationale: "Säsongsvyn warns early" and the §14.4 "visa konflikter" mode both need this wider
 * view). Two persons/court double-booked ONLY within the same plan is not reported here — the
 * solver's own {@code trainingBlockCapacity}/{@code coachNoOverlap}/{@code playerNoOverlap}
 * constraints already prevent that inside a solved plan, and reporting an in-plan draft's transient
 * inconsistency here would be noise, not a genuine season-level conflict.
 */
@Service
public class ConflictService {

    private final ActivityPlanRepository activityPlanRepository;
    private final TrainingGroupRepository trainingGroupRepository;
    private final PlayerAssignmentRepository playerAssignmentRepository;
    private final CoachAssignmentRepository coachAssignmentRepository;
    private final ParticipantProfileRepository participantProfileRepository;
    private final CoachProfileRepository coachProfileRepository;
    private final PersonRepository personRepository;
    private final TrainingBlockRepository trainingBlockRepository;
    private final TimeSlotRepository timeSlotRepository;
    private final CourtRepository courtRepository;
    private final TimeSlotLabelFormatter labelFormatter;

    public ConflictService(
            ActivityPlanRepository activityPlanRepository,
            TrainingGroupRepository trainingGroupRepository,
            PlayerAssignmentRepository playerAssignmentRepository,
            CoachAssignmentRepository coachAssignmentRepository,
            ParticipantProfileRepository participantProfileRepository,
            CoachProfileRepository coachProfileRepository,
            PersonRepository personRepository,
            TrainingBlockRepository trainingBlockRepository,
            TimeSlotRepository timeSlotRepository,
            CourtRepository courtRepository,
            TimeSlotLabelFormatter labelFormatter) {
        this.activityPlanRepository = activityPlanRepository;
        this.trainingGroupRepository = trainingGroupRepository;
        this.playerAssignmentRepository = playerAssignmentRepository;
        this.coachAssignmentRepository = coachAssignmentRepository;
        this.participantProfileRepository = participantProfileRepository;
        this.coachProfileRepository = coachProfileRepository;
        this.personRepository = personRepository;
        this.trainingBlockRepository = trainingBlockRepository;
        this.timeSlotRepository = timeSlotRepository;
        this.courtRepository = courtRepository;
        this.labelFormatter = labelFormatter;
    }

    public List<SeasonConflict> findConflicts(String seasonPlanId) {
        List<ResourceUsage> personUsages = collectUsages(seasonPlanId);
        List<ResourceUsage> groupCourtUsages = collectGroupCourtUsages(seasonPlanId);
        Map<String, Person> personCache = new HashMap<>();
        Map<String, Court> courtCache = new HashMap<>();

        List<SeasonConflict> conflicts = new ArrayList<>();
        conflicts.addAll(personConflicts(personUsages, personCache));
        conflicts.addAll(courtConflicts(groupCourtUsages, courtCache));
        return conflicts;
    }

    private List<ResourceUsage> collectUsages(String seasonPlanId) {
        List<ResourceUsage> usages = new ArrayList<>();
        for (ActivityPlan plan : activityPlanRepository.findBySeasonPlanId(seasonPlanId)) {
            Map<String, TrainingGroup> groupById = indexBy(trainingGroupRepository.findByActivityPlanId(plan.id()), TrainingGroup::id);
            Map<String, ParticipantProfile> participantById =
                    indexBy(participantProfileRepository.findByActivityPlanId(plan.id()), ParticipantProfile::id);
            Map<String, CoachProfile> coachById = indexBy(coachProfileRepository.findByActivityPlanId(plan.id()), CoachProfile::id);
            Map<String, TrainingBlock> blockCache = new HashMap<>();
            Map<String, TimeSlot> slotCache = new HashMap<>();

            for (PlayerAssignment pa : playerAssignmentRepository.findByActivityPlanId(plan.id())) {
                if (pa.groupId() == null) {
                    continue;
                }
                TrainingGroup group = groupById.get(pa.groupId());
                ParticipantProfile participant = participantById.get(pa.participantProfileId());
                if (participant == null) {
                    continue;
                }
                resolveSchedule(group, blockCache, slotCache).ifPresent(schedule -> usages.add(new ResourceUsage(
                        plan.id(), plan.name(), ResourceUsage.KIND_PLAYER, participant.personId(), group.name(),
                        TimeKey.of(schedule.slot().dayOfWeek(), schedule.slot().date(), schedule.slot().startTime(), schedule.slot().endTime()),
                        labelOf(schedule.slot()), schedule.block().courtId(), null)));
            }
            for (CoachAssignment ca : coachAssignmentRepository.findByActivityPlanId(plan.id())) {
                TrainingGroup group = groupById.get(ca.groupId());
                CoachProfile coach = coachById.get(ca.coachProfileId());
                if (coach == null) {
                    continue;
                }
                resolveSchedule(group, blockCache, slotCache).ifPresent(schedule -> usages.add(new ResourceUsage(
                        plan.id(), plan.name(), ResourceUsage.KIND_COACH, coach.personId(), group.name(),
                        TimeKey.of(schedule.slot().dayOfWeek(), schedule.slot().date(), schedule.slot().startTime(), schedule.slot().endTime()),
                        labelOf(schedule.slot()), schedule.block().courtId(), null)));
            }
        }
        // Determinism: stable sort by (planId, kind, personId) so pairwise sweeps below are
        // reproducible regardless of any upstream iteration-order nuance.
        usages.sort(Comparator.comparing(ResourceUsage::activityPlanId)
                .thenComparing(ResourceUsage::kind)
                .thenComparing(ResourceUsage::personId));
        return usages;
    }

    /**
     * ONE usage row per (plan, group-with-an-assigned-block) — deliberately NOT fanned out per
     * player/coach like {@link #collectUsages}, since a court is occupied by the GROUP as a whole
     * regardless of how many people are in it. Reusing the per-person list for the court sweep would
     * report the same court/time collision once per (player, opposing-usage) PAIR — e.g. an 11-player
     * group would produce 11 duplicate {@code COURT_DOUBLE_BOOKED} entries for one real conflict.
     */
    private List<ResourceUsage> collectGroupCourtUsages(String seasonPlanId) {
        List<ResourceUsage> usages = new ArrayList<>();
        for (ActivityPlan plan : activityPlanRepository.findBySeasonPlanId(seasonPlanId)) {
            Map<String, TrainingBlock> blockCache = new HashMap<>();
            Map<String, TimeSlot> slotCache = new HashMap<>();
            for (TrainingGroup group : trainingGroupRepository.findByActivityPlanId(plan.id())) {
                resolveSchedule(group, blockCache, slotCache).ifPresent(schedule -> usages.add(new ResourceUsage(
                        plan.id(), plan.name(), ResourceUsage.KIND_PLAYER, null, group.name(),
                        TimeKey.of(schedule.slot().dayOfWeek(), schedule.slot().date(), schedule.slot().startTime(), schedule.slot().endTime()),
                        labelOf(schedule.slot()), schedule.block().courtId(), null)));
            }
        }
        usages.sort(Comparator.comparing(ResourceUsage::activityPlanId).thenComparing(ResourceUsage::groupName));
        return usages;
    }

    private java.util.Optional<ResolvedSchedule> resolveSchedule(
            TrainingGroup group, Map<String, TrainingBlock> blockCache, Map<String, TimeSlot> slotCache) {
        if (group == null || group.assignedTrainingBlockId() == null) {
            return java.util.Optional.empty();
        }
        TrainingBlock block = blockCache.computeIfAbsent(
                group.assignedTrainingBlockId(), id -> trainingBlockRepository.findById(id).orElse(null));
        if (block == null) {
            return java.util.Optional.empty();
        }
        TimeSlot slot = slotCache.computeIfAbsent(block.timeSlotId(), id -> timeSlotRepository.findById(id).orElse(null));
        if (slot == null) {
            return java.util.Optional.empty();
        }
        return java.util.Optional.of(new ResolvedSchedule(block, slot));
    }

    private String labelOf(TimeSlot slot) {
        return labelFormatter.autoLabel(
                slot.dayOfWeek(), slot.date(),
                labelFormatter.parseTime(slot.startTime(), "startTime"),
                labelFormatter.parseTime(slot.endTime(), "endTime"));
    }

    /** Sweeps every person's usage list for cross-plan time overlaps. A pair where either usage is a
     * COACH commitment is {@link SeasonConflict#COACH_PLAYS_WHILE_COACHING} (spec §13.2's Anna
     * example); a pure PLAYER-PLAYER overlap is {@link SeasonConflict#PERSON_DOUBLE_BOOKED}. */
    private List<SeasonConflict> personConflicts(List<ResourceUsage> usages, Map<String, Person> personCache) {
        Map<String, List<ResourceUsage>> byPerson = new LinkedHashMap<>();
        for (ResourceUsage u : usages) {
            byPerson.computeIfAbsent(u.personId(), k -> new ArrayList<>()).add(u);
        }
        List<SeasonConflict> conflicts = new ArrayList<>();
        for (Map.Entry<String, List<ResourceUsage>> entry : byPerson.entrySet()) {
            List<ResourceUsage> personUsages = entry.getValue();
            for (int i = 0; i < personUsages.size(); i++) {
                for (int j = i + 1; j < personUsages.size(); j++) {
                    ResourceUsage a = personUsages.get(i);
                    ResourceUsage b = personUsages.get(j);
                    if (a.activityPlanId().equals(b.activityPlanId())) {
                        continue; // in-plan overlaps are the solver's own constraints' job, not season reporting.
                    }
                    if (!a.timeKey().overlaps(b.timeKey())) {
                        continue;
                    }
                    boolean involvesCoaching = ResourceUsage.KIND_COACH.equals(a.kind()) || ResourceUsage.KIND_COACH.equals(b.kind());
                    String type = involvesCoaching ? SeasonConflict.COACH_PLAYS_WHILE_COACHING : SeasonConflict.PERSON_DOUBLE_BOOKED;
                    Person person = personCache.computeIfAbsent(entry.getKey(), id -> personRepository.findById(id).orElse(null));
                    conflicts.add(new SeasonConflict(
                            type, SeasonConflict.SEVERITY_WARNING, entry.getKey(), displayNameOf(person), null, null,
                            List.of(usageOf(a), usageOf(b))));
                }
            }
        }
        return conflicts;
    }

    private List<SeasonConflict> courtConflicts(List<ResourceUsage> usages, Map<String, Court> courtCache) {
        Map<String, List<ResourceUsage>> byCourt = new LinkedHashMap<>();
        for (ResourceUsage u : usages) {
            if (u.courtId() != null) {
                byCourt.computeIfAbsent(u.courtId(), k -> new ArrayList<>()).add(u);
            }
        }
        List<SeasonConflict> conflicts = new ArrayList<>();
        for (Map.Entry<String, List<ResourceUsage>> entry : byCourt.entrySet()) {
            List<ResourceUsage> courtUsages = entry.getValue();
            for (int i = 0; i < courtUsages.size(); i++) {
                for (int j = i + 1; j < courtUsages.size(); j++) {
                    ResourceUsage a = courtUsages.get(i);
                    ResourceUsage b = courtUsages.get(j);
                    if (a.activityPlanId().equals(b.activityPlanId())) {
                        continue;
                    }
                    if (!a.timeKey().overlaps(b.timeKey())) {
                        continue;
                    }
                    Court court = courtCache.computeIfAbsent(entry.getKey(), id -> courtRepository.findById(id).orElse(null));
                    conflicts.add(new SeasonConflict(
                            SeasonConflict.COURT_DOUBLE_BOOKED, SeasonConflict.SEVERITY_WARNING, null, null,
                            entry.getKey(), court != null ? court.name() : null, List.of(usageOf(a), usageOf(b))));
                }
            }
        }
        return conflicts;
    }

    private static SeasonConflict.ConflictUsage usageOf(ResourceUsage u) {
        return new SeasonConflict.ConflictUsage(u.activityPlanId(), u.activityPlanName(), u.groupName(), u.timeLabel());
    }

    private static String displayNameOf(Person person) {
        if (person == null) {
            return "";
        }
        if (person.displayName() != null && !person.displayName().isBlank()) {
            return person.displayName();
        }
        return (person.firstName() + " " + person.lastName()).strip();
    }

    private static <T> Map<String, T> indexBy(List<T> items, Function<T, String> idOf) {
        Map<String, T> map = new HashMap<>();
        items.forEach(item -> map.put(idOf.apply(item), item));
        return map;
    }

    private record ResolvedSchedule(TrainingBlock block, TimeSlot slot) {
    }
}
