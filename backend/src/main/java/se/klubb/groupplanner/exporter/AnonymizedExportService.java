package se.klubb.groupplanner.exporter;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.springframework.stereotype.Service;
import se.klubb.groupplanner.api.error.NotFoundException;
import se.klubb.groupplanner.domain.ActivityPlan;
import se.klubb.groupplanner.domain.CoachAssignment;
import se.klubb.groupplanner.domain.CoachProfile;
import se.klubb.groupplanner.domain.Court;
import se.klubb.groupplanner.domain.CustomFieldValue;
import se.klubb.groupplanner.domain.FieldDefinition;
import se.klubb.groupplanner.domain.ParticipantProfile;
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
import se.klubb.groupplanner.repo.PlayerAssignmentRepository;
import se.klubb.groupplanner.repo.TimeSlotRepository;
import se.klubb.groupplanner.repo.TrainingBlockRepository;
import se.klubb.groupplanner.repo.TrainingGroupRepository;

/**
 * {@code GET /api/plans/{planId}/export/anonymized} (spec §21.3): the "open-source-debugging
 * dataset" — names/emails/phones/comments/personnummer-like identifiers replaced with stable
 * per-plan anonymized ids ({@code Spelare 001}, {@code Tränare 01}), while keeping ranking, level,
 * wish-structure (re-expressed over the SAME anonymized ids, so friend/coach-wish shape survives),
 * group sizes, and times/courts.
 *
 * <p>Deliberately NOT built on top of {@link ExportDataAssembler}/{@link ExportData} — those types
 * carry {@link se.klubb.groupplanner.domain.Person#displayName()}-derived real names by design (the
 * normal export's whole point); reusing them here would mean relying on every future edit to that
 * pipeline to keep never routing a real name into this one. Building the anonymized rows from a
 * fully separate, from-scratch pass over the repositories is a structural guarantee, not a
 * discipline: {@link se.klubb.groupplanner.domain.Person} is never even loaded in this class.
 */
@Service
public class AnonymizedExportService {

    private static final List<String> HEADERS = List.of(
            "Grupp", "Tid", "Bana", "Tränare", "Spelare", "Ranking", "Nivåscore", "Tidigare grupp",
            "Vill spela med", "Måste spela med", "Får ej spela med", "Varningar");

    private final ActivityPlanRepository activityPlanRepository;
    private final TrainingGroupRepository trainingGroupRepository;
    private final ParticipantProfileRepository participantProfileRepository;
    private final PlayerAssignmentRepository playerAssignmentRepository;
    private final CoachProfileRepository coachProfileRepository;
    private final CoachAssignmentRepository coachAssignmentRepository;
    private final TrainingBlockRepository trainingBlockRepository;
    private final TimeSlotRepository timeSlotRepository;
    private final CourtRepository courtRepository;
    private final CustomFieldValueRepository customFieldValueRepository;
    private final FieldDefinitionRepository fieldDefinitionRepository;
    private final ObjectMapper objectMapper;

    public AnonymizedExportService(
            ActivityPlanRepository activityPlanRepository,
            TrainingGroupRepository trainingGroupRepository,
            ParticipantProfileRepository participantProfileRepository,
            PlayerAssignmentRepository playerAssignmentRepository,
            CoachProfileRepository coachProfileRepository,
            CoachAssignmentRepository coachAssignmentRepository,
            TrainingBlockRepository trainingBlockRepository,
            TimeSlotRepository timeSlotRepository,
            CourtRepository courtRepository,
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
        this.customFieldValueRepository = customFieldValueRepository;
        this.fieldDefinitionRepository = fieldDefinitionRepository;
        this.objectMapper = objectMapper;
    }

    /** {@code format} must be {@code xlsx} or {@code csv} — an unknown value is a 400 (M8 review
     * fix, finding 6: previously any unknown format silently fell through to xlsx, unlike the
     * normal export endpoint's explicit validation in {@link ExportService#export}). A blank/null
     * format defaults to xlsx, matching the controller's own {@code defaultValue}. */
    public byte[] export(String activityPlanId, String format) {
        String resolved = (format == null || format.isBlank()) ? "xlsx" : format.toLowerCase(java.util.Locale.ROOT);
        if (!"xlsx".equals(resolved) && !"csv".equals(resolved)) {
            throw new se.klubb.groupplanner.api.error.BadRequestException(
                    "format must be 'xlsx' or 'csv', was: " + format);
        }
        List<List<String>> rows = rows(activityPlanId);
        return "csv".equals(resolved) ? TabularSheetWriter.csv(rows) : TabularSheetWriter.xlsx("Anonymiserat", rows);
    }

    private List<List<String>> rows(String activityPlanId) {
        activityPlanRepository.findById(activityPlanId)
                .orElseThrow(() -> new NotFoundException("Activity plan not found: " + activityPlanId));

        List<TrainingGroup> groups = trainingGroupRepository.findByActivityPlanId(activityPlanId);
        List<ParticipantProfile> participants = participantProfileRepository.findByActivityPlanId(activityPlanId);
        List<PlayerAssignment> playerAssignments = playerAssignmentRepository.findByActivityPlanId(activityPlanId);
        List<CoachProfile> coaches = coachProfileRepository.findByActivityPlanId(activityPlanId);
        List<CoachAssignment> coachAssignments = coachAssignmentRepository.findByActivityPlanId(activityPlanId);

        // Stable, deterministic anonymized ids: repositories already return participants/coaches
        // ordered by their own stable UUIDv7 id (ADR-007 determinism), so "Spelare 001" always means
        // the same real person across repeated exports of the same plan state.
        Map<String, String> playerAnonId = assignAnonIds(participants.stream().map(ParticipantProfile::id).toList(), "Spelare", 3);
        Map<String, String> coachAnonId = assignAnonIds(coaches.stream().map(CoachProfile::id).toList(), "Tränare", 2);

        Map<String, ParticipantProfile> participantById = indexBy(participants, ParticipantProfile::id);
        Map<String, CoachProfile> coachById = indexBy(coaches, CoachProfile::id);
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

        String playWithFieldId = fieldDefinitionRepository.findGlobalByKey("playWith").map(FieldDefinition::id).orElse(null);
        String mustPlayWithFieldId = fieldDefinitionRepository.findGlobalByKey("mustPlayWith").map(FieldDefinition::id).orElse(null);
        String mustNotPlayWithFieldId = fieldDefinitionRepository.findGlobalByKey("mustNotPlayWith").map(FieldDefinition::id).orElse(null);

        List<List<String>> rows = new ArrayList<>();
        rows.add(HEADERS);

        for (TrainingGroup g : groups) {
            List<PlayerAssignment> playersInGroup = playersByGroup.getOrDefault(g.id(), List.of());
            List<CoachAssignment> coachesInGroup = coachesByGroup.getOrDefault(g.id(), List.of());
            List<String> groupWarnings = ExportWarnings.forGroup(g, playersInGroup.size(), coachesInGroup.size());

            Optional<ResolvedSchedule> schedule = resolveSchedule(g, blockCache, slotCache);
            String timeLabel = schedule.map(s -> s.slot().label()).orElse("");
            Court court = schedule.map(s -> s.block().courtId()).filter(java.util.Objects::nonNull)
                    .map(id -> courtCache.computeIfAbsent(id, cid -> courtRepository.findById(cid).orElse(null)))
                    .orElse(null);
            String coachAnon = anonymizedCoachNames(coachesInGroup, coachAnonId);

            for (PlayerAssignment pa : playersInGroup) {
                ParticipantProfile participant = participantById.get(pa.participantProfileId());
                if (participant == null) {
                    continue;
                }
                rows.add(anonymizedRow(
                        g.name(), timeLabel, court != null ? court.name() : "", coachAnon, playerAnonId.get(participant.id()),
                        participant, playWithFieldId, mustPlayWithFieldId, mustNotPlayWithFieldId, playerAnonId,
                        ExportWarnings.combine(ExportWarnings.forParticipant(participant), groupWarnings)));
            }
        }

        for (ParticipantProfile p : participants) {
            PlayerAssignment pa = assignmentByParticipant.get(p.id());
            if (pa != null && pa.groupId() != null) {
                continue;
            }
            rows.add(anonymizedRow(
                    "Kölista", "", "", "", playerAnonId.get(p.id()), p, playWithFieldId, mustPlayWithFieldId,
                    mustNotPlayWithFieldId, playerAnonId, ExportWarnings.forParticipant(p)));
        }

        return rows;
    }

    private static String anonymizedCoachNames(List<CoachAssignment> coachesInGroup, Map<String, String> coachAnonId) {
        List<String> names = new ArrayList<>();
        for (CoachAssignment ca : coachesInGroup) {
            String anon = coachAnonId.get(ca.coachProfileId());
            if (anon != null) {
                names.add(anon);
            }
        }
        return String.join("; ", names);
    }

    private List<String> anonymizedRow(
            String groupName, String timeLabel, String courtName, String coachAnonNames, String playerAnon,
            ParticipantProfile participant, String playWithFieldId, String mustPlayWithFieldId, String mustNotPlayWithFieldId,
            Map<String, String> playerAnonId, List<String> warnings) {
        return List.of(
                orEmpty(groupName), orEmpty(timeLabel), orEmpty(courtName), orEmpty(coachAnonNames),
                orEmpty(playerAnon), numberOrEmpty(participant.rankingPoints()), numberOrEmpty(participant.estimatedLevel()),
                orEmpty(participant.previousGroupName()),
                String.join("; ", anonymizedWishIds(participant.id(), playWithFieldId, playerAnonId)),
                String.join("; ", anonymizedWishIds(participant.id(), mustPlayWithFieldId, playerAnonId)),
                String.join("; ", anonymizedWishIds(participant.id(), mustNotPlayWithFieldId, playerAnonId)),
                String.join("; ", warnings));
    }

    /** Resolves one personRelation custom field's raw participant-id array to the SAME anonymized
     * ids used throughout this export - "wish-structure by anonymized ids" (spec §21.3). Any id not
     * present in {@code playerAnonId} (e.g. referencing a participant outside this plan - shouldn't
     * happen per {@code FieldValueService}'s own validation, but defensive) is silently dropped
     * rather than ever falling back to printing the raw internal id. */
    private List<String> anonymizedWishIds(String participantProfileId, String fieldId, Map<String, String> playerAnonId) {
        if (fieldId == null) {
            return List.of();
        }
        for (CustomFieldValue cfv : customFieldValueRepository.findByEntity(CustomFieldValue.ENTITY_TYPE_PARTICIPANT, participantProfileId)) {
            if (!cfv.fieldDefinitionId().equals(fieldId) || cfv.valueJson() == null) {
                continue;
            }
            JsonNode node = readTree(cfv.valueJson());
            if (!node.isArray()) {
                return List.of();
            }
            List<String> anonIds = new ArrayList<>();
            node.forEach(n -> {
                String anon = playerAnonId.get(n.asText());
                if (anon != null) {
                    anonIds.add(anon);
                }
            });
            return anonIds;
        }
        return List.of();
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

    /** Sequential, zero-padded, 1-based anonymized ids in the SAME order the repository already
     * returns rows (stable UUIDv7 id order, ADR-007) — deterministic across repeated exports of an
     * unchanged plan. */
    private static Map<String, String> assignAnonIds(List<String> realIds, String prefix, int pad) {
        Map<String, String> map = new LinkedHashMap<>();
        int n = 1;
        for (String id : realIds) {
            map.put(id, prefix + " " + String.format("%0" + pad + "d", n));
            n++;
        }
        return map;
    }

    private JsonNode readTree(String json) {
        try {
            return objectMapper.readTree(json);
        } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
            throw new IllegalStateException("Corrupt custom_field_value JSON: " + json, e);
        }
    }

    private static <T> Map<String, T> indexBy(List<T> items, java.util.function.Function<T, String> idOf) {
        Map<String, T> map = new HashMap<>();
        items.forEach(item -> map.put(idOf.apply(item), item));
        return map;
    }

    private static String orEmpty(String s) {
        return s == null ? "" : s;
    }

    private static String numberOrEmpty(Double value) {
        return value == null ? "" : String.valueOf(value);
    }

    private record ResolvedSchedule(TrainingBlock block, TimeSlot slot) {
    }
}
