package se.klubb.groupplanner.exporter;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.springframework.stereotype.Component;
import se.klubb.groupplanner.api.error.NotFoundException;
import se.klubb.groupplanner.domain.ActivityPlan;
import se.klubb.groupplanner.domain.CoachAssignment;
import se.klubb.groupplanner.domain.CoachProfile;
import se.klubb.groupplanner.domain.Court;
import se.klubb.groupplanner.domain.CustomFieldValue;
import se.klubb.groupplanner.domain.FieldDefinition;
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
import se.klubb.groupplanner.repo.CustomFieldValueRepository;
import se.klubb.groupplanner.repo.FieldDefinitionRepository;
import se.klubb.groupplanner.repo.ParticipantProfileRepository;
import se.klubb.groupplanner.repo.PersonRepository;
import se.klubb.groupplanner.repo.PlayerAssignmentRepository;
import se.klubb.groupplanner.repo.TimeSlotRepository;
import se.klubb.groupplanner.repo.TrainingBlockRepository;
import se.klubb.groupplanner.repo.TrainingGroupRepository;

/**
 * Builds {@link ExportData} from an {@link ActivityPlan}'s current persisted state (spec §20) — the
 * shared input for both {@link GroupedXlsxWriter} and {@link FlatExporter}. {@code run} (an optional
 * {@code optimization_run} id per the export endpoint's query parameter) is deliberately NOT used
 * here: warnings ({@link ExportWarnings}) are computed from persisted domain data only, so export
 * works identically whether or not a specific run is named — the parameter exists for a future
 * per-run explanation cross-reference, out of scope for this milestone's export content.
 *
 * <p><b>Privacy (CLAUDE.md, spec §20.3):</b> {@code includeComments=false} means {@link
 * ParticipantProfile#importedComment()}/{@link ParticipantProfile#internalNote()} are never even
 * READ into an {@link ExportPlayer#comment()} field, let alone written by a downstream writer — the
 * definitive leak test ({@code CommentLeakExportTest}) scans the produced file's raw bytes, so this
 * class's contract is "structurally absent when off", not "filtered at render time".
 */
@Component
public class ExportDataAssembler {

    private final ActivityPlanRepository activityPlanRepository;
    private final TrainingGroupRepository trainingGroupRepository;
    private final ParticipantProfileRepository participantProfileRepository;
    private final PlayerAssignmentRepository playerAssignmentRepository;
    private final CoachProfileRepository coachProfileRepository;
    private final CoachAssignmentRepository coachAssignmentRepository;
    private final TrainingBlockRepository trainingBlockRepository;
    private final TimeSlotRepository timeSlotRepository;
    private final CourtRepository courtRepository;
    private final PersonRepository personRepository;
    private final CustomFieldValueRepository customFieldValueRepository;
    private final FieldDefinitionRepository fieldDefinitionRepository;
    private final ObjectMapper objectMapper;

    public ExportDataAssembler(
            ActivityPlanRepository activityPlanRepository,
            TrainingGroupRepository trainingGroupRepository,
            ParticipantProfileRepository participantProfileRepository,
            PlayerAssignmentRepository playerAssignmentRepository,
            CoachProfileRepository coachProfileRepository,
            CoachAssignmentRepository coachAssignmentRepository,
            TrainingBlockRepository trainingBlockRepository,
            TimeSlotRepository timeSlotRepository,
            CourtRepository courtRepository,
            PersonRepository personRepository,
            CustomFieldValueRepository customFieldValueRepository,
            FieldDefinitionRepository fieldDefinitionRepository,
            ObjectMapper objectMapper) {
        this.activityPlanRepository = activityPlanRepository;
        this.trainingGroupRepository = trainingGroupRepository;
        this.participantProfileRepository = participantProfileRepository;
        this.playerAssignmentRepository = playerAssignmentRepository;
        this.coachProfileRepository = coachProfileRepository;
        this.coachAssignmentRepository = coachAssignmentRepository;
        this.trainingBlockRepository = trainingBlockRepository;
        this.timeSlotRepository = timeSlotRepository;
        this.courtRepository = courtRepository;
        this.personRepository = personRepository;
        this.customFieldValueRepository = customFieldValueRepository;
        this.fieldDefinitionRepository = fieldDefinitionRepository;
        this.objectMapper = objectMapper;
    }

    public ExportData assemble(String activityPlanId, boolean includeComments) {
        ActivityPlan plan = activityPlanRepository.findById(activityPlanId)
                .orElseThrow(() -> new NotFoundException("Activity plan not found: " + activityPlanId));

        List<TrainingGroup> groups = trainingGroupRepository.findByActivityPlanId(activityPlanId);
        List<ParticipantProfile> participants = participantProfileRepository.findByActivityPlanId(activityPlanId);
        List<PlayerAssignment> playerAssignments = playerAssignmentRepository.findByActivityPlanId(activityPlanId);
        List<CoachProfile> coachProfiles = coachProfileRepository.findByActivityPlanId(activityPlanId);
        List<CoachAssignment> coachAssignments = coachAssignmentRepository.findByActivityPlanId(activityPlanId);

        Map<String, ParticipantProfile> participantById = indexBy(participants, ParticipantProfile::id);
        Map<String, CoachProfile> coachProfileById = indexBy(coachProfiles, CoachProfile::id);
        Map<String, Person> personCache = new HashMap<>();
        Map<String, TrainingBlock> blockCache = new HashMap<>();
        Map<String, TimeSlot> slotCache = new HashMap<>();
        Map<String, Court> courtCache = new HashMap<>();

        Map<String, List<PlayerAssignment>> playersByGroup = new HashMap<>();
        for (PlayerAssignment pa : playerAssignments) {
            if (pa.groupId() != null) {
                playersByGroup.computeIfAbsent(pa.groupId(), k -> new ArrayList<>()).add(pa);
            }
        }
        Map<String, List<CoachAssignment>> coachesByGroup = new HashMap<>();
        for (CoachAssignment ca : coachAssignments) {
            coachesByGroup.computeIfAbsent(ca.groupId(), k -> new ArrayList<>()).add(ca);
        }
        Map<String, PlayerAssignment> assignmentByParticipant = indexBy(playerAssignments, PlayerAssignment::participantProfileId);

        List<ExportGroup> exportGroups = new ArrayList<>();
        for (TrainingGroup g : groups) {
            List<PlayerAssignment> playersInGroup = playersByGroup.getOrDefault(g.id(), List.of());
            List<CoachAssignment> coachesInGroup = coachesByGroup.getOrDefault(g.id(), List.of());
            List<String> groupWarnings = ExportWarnings.forGroup(g, playersInGroup.size(), coachesInGroup.size());

            List<ExportPlayer> exportPlayers = new ArrayList<>();
            for (PlayerAssignment pa : playersInGroup) {
                ParticipantProfile participant = participantById.get(pa.participantProfileId());
                if (participant == null) {
                    continue;
                }
                exportPlayers.add(new ExportPlayer(
                        displayNameOf(person(participant.personId(), personCache)),
                        participant.rankingPoints(),
                        participant.estimatedLevel(),
                        participant.previousGroupName(),
                        includeComments ? participant.importedComment() : null,
                        ExportWarnings.combine(ExportWarnings.forParticipant(participant), groupWarnings)));
            }
            exportPlayers.sort(java.util.Comparator.comparing(ExportPlayer::displayName, String.CASE_INSENSITIVE_ORDER));

            String coachNames = coachNamesOf(coachesInGroup, coachProfileById, personCache);
            ResolvedSchedule schedule = resolveSchedule(g, blockCache, slotCache).orElse(null);
            Court court = schedule != null && schedule.block().courtId() != null
                    ? courtCache.computeIfAbsent(schedule.block().courtId(), id -> courtRepository.findById(id).orElse(null))
                    : null;

            exportGroups.add(new ExportGroup(
                    g.name(), g.groupOrder(), schedule != null ? schedule.slot().label() : null,
                    court != null ? court.name() : null, coachNames, exportPlayers));
        }

        List<ExportWaitlistEntry> waitlist = new ArrayList<>();
        for (ParticipantProfile p : participants) {
            PlayerAssignment pa = assignmentByParticipant.get(p.id());
            if (pa != null && pa.groupId() != null) {
                continue;
            }
            waitlist.add(new ExportWaitlistEntry(
                    displayNameOf(person(p.personId(), personCache)), p.rankingPoints(), p.estimatedLevel(), priorityOf(p.id())));
        }
        waitlist.sort(java.util.Comparator
                .comparing((ExportWaitlistEntry w) -> w.priority() == null ? Integer.MAX_VALUE : w.priority())
                .thenComparing(ExportWaitlistEntry::displayName, String.CASE_INSENSITIVE_ORDER));

        return new ExportData(plan.name(), plan.category(), exportGroups, waitlist);
    }

    private String coachNamesOf(
            List<CoachAssignment> coachesInGroup, Map<String, CoachProfile> coachProfileById, Map<String, Person> personCache) {
        if (coachesInGroup.isEmpty()) {
            return null;
        }
        List<String> names = new ArrayList<>();
        for (CoachAssignment ca : coachesInGroup) {
            CoachProfile coach = coachProfileById.get(ca.coachProfileId());
            if (coach != null) {
                names.add(displayNameOf(person(coach.personId(), personCache)));
            }
        }
        return names.isEmpty() ? null : String.join("; ", names);
    }

    private Person person(String personId, Map<String, Person> personCache) {
        return personCache.computeIfAbsent(personId, id -> personRepository.findById(id).orElse(null));
    }

    private Optional<ResolvedSchedule> resolveSchedule(TrainingGroup group, Map<String, TrainingBlock> blockCache, Map<String, TimeSlot> slotCache) {
        if (group.assignedTrainingBlockId() == null) {
            return Optional.empty();
        }
        TrainingBlock block = blockCache.computeIfAbsent(group.assignedTrainingBlockId(), id -> trainingBlockRepository.findById(id).orElse(null));
        if (block == null) {
            return Optional.empty();
        }
        TimeSlot slot = slotCache.computeIfAbsent(block.timeSlotId(), id -> timeSlotRepository.findById(id).orElse(null));
        if (slot == null) {
            return Optional.empty();
        }
        return Optional.of(new ResolvedSchedule(block, slot));
    }

    /** Same {@code priority} custom-field read pattern as {@code savedplan.SavedPlanService
     * #priorityOf} - kept as its own small copy rather than a shared utility since the two callers
     * live in different packages and the logic is a handful of lines. */
    private Integer priorityOf(String participantProfileId) {
        Optional<FieldDefinition> priorityField = fieldDefinitionRepository.findGlobalByKey("priority");
        if (priorityField.isEmpty()) {
            return null;
        }
        for (CustomFieldValue cfv : customFieldValueRepository.findByEntity(CustomFieldValue.ENTITY_TYPE_PARTICIPANT, participantProfileId)) {
            if (cfv.fieldDefinitionId().equals(priorityField.get().id()) && cfv.valueJson() != null) {
                JsonNode node = readTree(cfv.valueJson());
                return node.isNumber() ? node.asInt() : null;
            }
        }
        return null;
    }

    private JsonNode readTree(String json) {
        try {
            return objectMapper.readTree(json);
        } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
            throw new IllegalStateException("Corrupt custom_field_value JSON: " + json, e);
        }
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

    private static <T> Map<String, T> indexBy(List<T> items, java.util.function.Function<T, String> idOf) {
        Map<String, T> map = new HashMap<>();
        items.forEach(item -> map.put(idOf.apply(item), item));
        return map;
    }

    private record ResolvedSchedule(TrainingBlock block, TimeSlot slot) {
    }
}
