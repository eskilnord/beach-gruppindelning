package se.klubb.groupplanner.season;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
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
import se.klubb.groupplanner.domain.SavedPlan;
import se.klubb.groupplanner.domain.SavedPlanResourceUsage;
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
import se.klubb.groupplanner.repo.SavedPlanRepository;
import se.klubb.groupplanner.repo.SavedPlanResourceUsageRepository;
import se.klubb.groupplanner.repo.TimeSlotRepository;
import se.klubb.groupplanner.repo.TrainingBlockRepository;
import se.klubb.groupplanner.repo.TrainingGroupRepository;
import se.klubb.groupplanner.resources.TimeSlotLabelFormatter;

/**
 * Season-level conflict detection (docs/design/04-solver.md §6.3, spec §19.2/§14.4) — a plain
 * reporting service, no Timefold dependency.
 *
 * <p><b>M8 task item 1 ("ConflictService reads ALL statuses ... season view should show conflicts
 * between saved plans AND live plan state"):</b> for each {@link ActivityPlan} in the season, this
 * service picks exactly ONE usage source: the plan's most recently created NON-{@code archived}
 * {@code saved_plan} snapshot, of any status ({@link #effectiveSnapshotFor}) — unlike in-solver
 * cross-plan blocking, which only ever trusts {@code status='locked'} rows as hard facts (§10.24) —
 * or, if the plan has no non-archived snapshot at all, its CURRENT live persisted assignments (the
 * M6b/M7 behavior, unchanged). This directly answers the task's framing: plans the council has
 * already recorded (saved/locked/published) contribute what was ACTUALLY recorded (which is also
 * what really blocks other plans' solves once locked, per V6__soft_constraints_locks_saved_plan.sql's
 * "a later edit to the live plan does not retroactively change what an already-locked OTHER plan
 * blocks"), while plans still in active day-to-day editing (never yet saved) contribute their live
 * working state — "Säsongsvyn warns early" per this class's original M6b rationale, preserved.
 * Archiving a snapshot removes IT from consideration and re-exposes the next-newest non-archived
 * one (M8 review fix, finding 1 — e.g. "lock v1 → save v2 → archive v2" must keep reporting from
 * the still-locked v1, not fall back to live); only a plan whose EVERY snapshot is archived falls
 * back to live state (an explicit "retired everything" should not resurrect stale schedule data if
 * the underlying activity plan is still being reused).
 *
 * <p>Picking exactly one source per plan (rather than adding the snapshot as an ADDITIONAL usage set
 * on top of live data) is deliberate: shortly after a save, the two are identical, and sweeping both
 * would report every real conflict multiple times over — exactly the kind of duplicate-conflict bug
 * backend/docs/m6b-notes.md already documents once for the {@code collectGroupCourtUsages}/
 * {@code collectUsages} split ("11 duplicate COURT_DOUBLE_BOOKED entries for one real conflict").
 *
 * <p>Two persons/court double-booked ONLY within the same plan is not reported here — the solver's
 * own {@code trainingBlockCapacity}/{@code coachNoOverlap}/{@code playerNoOverlap} constraints
 * already prevent that inside a solved plan, and reporting an in-plan draft's transient
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
    private final SavedPlanRepository savedPlanRepository;
    private final SavedPlanResourceUsageRepository savedPlanResourceUsageRepository;
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
            SavedPlanRepository savedPlanRepository,
            SavedPlanResourceUsageRepository savedPlanResourceUsageRepository,
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
        this.savedPlanRepository = savedPlanRepository;
        this.savedPlanResourceUsageRepository = savedPlanResourceUsageRepository;
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
            Optional<SavedPlan> snapshot = effectiveSnapshotFor(plan);
            if (snapshot.isPresent()) {
                usages.addAll(personUsagesFromSnapshot(plan, snapshot.get()));
            } else {
                usages.addAll(personUsagesFromLive(plan));
            }
        }
        // Determinism: stable sort by (planId, kind, personId) so pairwise sweeps below are
        // reproducible regardless of any upstream iteration-order nuance.
        usages.sort(Comparator.comparing(ResourceUsage::activityPlanId)
                .thenComparing(ResourceUsage::kind)
                .thenComparing(ResourceUsage::personId));
        return usages;
    }

    private List<ResourceUsage> personUsagesFromLive(ActivityPlan plan) {
        List<ResourceUsage> usages = new ArrayList<>();
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
        return usages;
    }

    /** {@code saved_plan_resource_usage} has no group name (it is a flattened person/coach × time ×
     * court row, already stripped of the group it came from at materialization time) — {@code
     * groupName} is {@code null} for a snapshot-sourced usage, same as the existing court-sweep rows
     * below (the UI already renders a {@code null} group name blank for those). */
    private List<ResourceUsage> personUsagesFromSnapshot(ActivityPlan plan, SavedPlan snapshot) {
        List<ResourceUsage> usages = new ArrayList<>();
        for (SavedPlanResourceUsage row : savedPlanResourceUsageRepository.findBySavedPlanId(snapshot.id())) {
            if (row.personId() == null) {
                continue;
            }
            String kind = SavedPlanResourceUsage.ROLE_COACH.equals(row.role()) ? ResourceUsage.KIND_COACH : ResourceUsage.KIND_PLAYER;
            usages.add(new ResourceUsage(
                    plan.id(), plan.name(), kind, row.personId(), null,
                    TimeKey.of(row.dayOfWeek(), row.date(), row.startTime(), row.endTime()),
                    labelOfRaw(row.dayOfWeek(), row.date(), row.startTime(), row.endTime()), row.courtId(), null));
        }
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
            Optional<SavedPlan> snapshot = effectiveSnapshotFor(plan);
            if (snapshot.isPresent()) {
                usages.addAll(courtUsagesFromSnapshot(plan, snapshot.get()));
            } else {
                usages.addAll(courtUsagesFromLive(plan));
            }
        }
        usages.sort(Comparator.comparing(ResourceUsage::activityPlanId).thenComparing(ResourceUsage::groupName, Comparator.nullsFirst(Comparator.naturalOrder())));
        return usages;
    }

    private List<ResourceUsage> courtUsagesFromLive(ActivityPlan plan) {
        List<ResourceUsage> usages = new ArrayList<>();
        Map<String, TrainingBlock> blockCache = new HashMap<>();
        Map<String, TimeSlot> slotCache = new HashMap<>();
        for (TrainingGroup group : trainingGroupRepository.findByActivityPlanId(plan.id())) {
            resolveSchedule(group, blockCache, slotCache).ifPresent(schedule -> usages.add(new ResourceUsage(
                    plan.id(), plan.name(), ResourceUsage.KIND_PLAYER, null, group.name(),
                    TimeKey.of(schedule.slot().dayOfWeek(), schedule.slot().date(), schedule.slot().startTime(), schedule.slot().endTime()),
                    labelOf(schedule.slot()), schedule.block().courtId(), null)));
        }
        return usages;
    }

    /** Distinct (courtId, time) pairs derived from a snapshot's PLAYER+COACH usage rows — every
     * member of the same group shares the same court/time, so deduping across both roles recovers
     * exactly the "one row per group-with-a-court" shape {@link #courtUsagesFromLive} produces from
     * live {@code training_group} data, without needing per-group granularity in {@code
     * saved_plan_resource_usage} (which is deliberately flattened to person-level, see V6's schema
     * comment). */
    private List<ResourceUsage> courtUsagesFromSnapshot(ActivityPlan plan, SavedPlan snapshot) {
        Map<String, ResourceUsage> distinctByCourtAndTime = new LinkedHashMap<>();
        for (SavedPlanResourceUsage row : savedPlanResourceUsageRepository.findBySavedPlanId(snapshot.id())) {
            if (row.courtId() == null) {
                continue;
            }
            TimeKey timeKey = TimeKey.of(row.dayOfWeek(), row.date(), row.startTime(), row.endTime());
            String key = row.courtId() + "|" + timeKey;
            distinctByCourtAndTime.computeIfAbsent(key, k -> new ResourceUsage(
                    plan.id(), plan.name(), ResourceUsage.KIND_PLAYER, null, null, timeKey,
                    labelOfRaw(row.dayOfWeek(), row.date(), row.startTime(), row.endTime()), row.courtId(), null));
        }
        return new ArrayList<>(distinctByCourtAndTime.values());
    }

    /** The plan's most recently created NON-ARCHIVED {@code saved_plan} snapshot (see class javadoc
     * for the full rationale) - {@link Optional#empty()} means "use live state", matching the
     * M6b/M7 behavior for a plan that has never been saved (or whose every snapshot is archived).
     * The archived filter is INSIDE the repository query, before its {@code LIMIT 1} (M8 review fix,
     * finding 1): filtering a plain latest-row result here made "lock v1 → save v2 → archive v2"
     * fall back to live state while v1 was still locked and still blocking other plans' solves. */
    private Optional<SavedPlan> effectiveSnapshotFor(ActivityPlan plan) {
        return savedPlanRepository.findLatestNonArchivedByActivityPlanId(plan.id());
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
        return labelOfRaw(slot.dayOfWeek(), slot.date(), slot.startTime(), slot.endTime());
    }

    /** Same label derivation as {@link #labelOf(TimeSlot)}, from the raw text fields a {@code
     * saved_plan_resource_usage} row stores directly (no {@code TimeSlot} row to join to - the usage
     * row is a frozen COPY, per V6's schema comment: "a later edit to the source plan's schedule
     * does not silently change what an already-locked saved_plan blocks"). */
    private String labelOfRaw(String dayOfWeek, String date, String startTime, String endTime) {
        return labelFormatter.autoLabel(
                dayOfWeek, date, labelFormatter.parseTime(startTime, "startTime"), labelFormatter.parseTime(endTime, "endTime"));
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
