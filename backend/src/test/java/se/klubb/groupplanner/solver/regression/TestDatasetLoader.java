package se.klubb.groupplanner.solver.regression;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import se.klubb.groupplanner.domain.ActivityPlan;
import se.klubb.groupplanner.domain.CoachProfile;
import se.klubb.groupplanner.domain.CoachTimeSlot;
import se.klubb.groupplanner.domain.FieldDefinition;
import se.klubb.groupplanner.domain.ParticipantProfile;
import se.klubb.groupplanner.domain.Person;
import se.klubb.groupplanner.domain.SeasonPlan;
import se.klubb.groupplanner.domain.TimeSlot;
import se.klubb.groupplanner.level.LevelService;
import se.klubb.groupplanner.repo.ActivityPlanRepository;
import se.klubb.groupplanner.repo.CoachProfileRepository;
import se.klubb.groupplanner.repo.CoachTimeSlotRepository;
import se.klubb.groupplanner.repo.CustomFieldValueRepository;
import se.klubb.groupplanner.repo.FieldDefinitionRepository;
import se.klubb.groupplanner.repo.ParticipantProfileRepository;
import se.klubb.groupplanner.repo.PersonRepository;
import se.klubb.groupplanner.repo.PlayerAssignmentRepository;
import se.klubb.groupplanner.repo.SeasonPlanRepository;
import se.klubb.groupplanner.repo.TimeSlotRepository;
import se.klubb.groupplanner.resources.TrainingBlockGenerationService;
import se.klubb.groupplanner.solver.assemble.GroupGenerator;
import se.klubb.groupplanner.util.Uuid7;

/**
 * Loads one of the committed anonymized {@code test-data/datasets/*} fixtures into a real DB via
 * the same repositories/services production code uses (never hand-built solver POJOs) — so {@code
 * SolverRegressionTest} exercises the FULL pipeline: import-shaped data -&gt; {@code
 * SolverInputAssembler} -&gt; solve, matching what a real user's plan would go through.
 *
 * <p>CSV shape (test-data/generator/README.md): {@code participants.csv} (id, first_name,
 * last_name, email, ranking_points, previous_group_name, previous_group_level, manual_level_score,
 * priority, unavailable_time_slot_ids, preferred_time_slot_ids, wants_with_ids, must_with_ids,
 * not_with_ids, wants_coach_id, new_to_club, needs_review — semicolon-separated for multi-value
 * columns), {@code coaches.csv}, {@code timeslots.csv}, {@code config.csv} (key,value: category,
 * target_size, min_size, max_size). No confidential real file is ever read (CLAUDE.md) — only these
 * committed, already-anonymized fixtures.
 */
public final class TestDatasetLoader {

    private final SeasonPlanRepository seasonPlanRepository;
    private final ActivityPlanRepository activityPlanRepository;
    private final PersonRepository personRepository;
    private final ParticipantProfileRepository participantProfileRepository;
    private final PlayerAssignmentRepository playerAssignmentRepository;
    private final CoachProfileRepository coachProfileRepository;
    private final CoachTimeSlotRepository coachTimeSlotRepository;
    private final TimeSlotRepository timeSlotRepository;
    private final TrainingBlockGenerationService trainingBlockGenerationService;
    private final FieldDefinitionRepository fieldDefinitionRepository;
    private final CustomFieldValueRepository customFieldValueRepository;
    private final LevelService levelService;
    private final GroupGenerator groupGenerator;

    public TestDatasetLoader(
            SeasonPlanRepository seasonPlanRepository,
            ActivityPlanRepository activityPlanRepository,
            PersonRepository personRepository,
            ParticipantProfileRepository participantProfileRepository,
            PlayerAssignmentRepository playerAssignmentRepository,
            CoachProfileRepository coachProfileRepository,
            CoachTimeSlotRepository coachTimeSlotRepository,
            TimeSlotRepository timeSlotRepository,
            TrainingBlockGenerationService trainingBlockGenerationService,
            FieldDefinitionRepository fieldDefinitionRepository,
            CustomFieldValueRepository customFieldValueRepository,
            LevelService levelService,
            GroupGenerator groupGenerator) {
        this.seasonPlanRepository = seasonPlanRepository;
        this.activityPlanRepository = activityPlanRepository;
        this.personRepository = personRepository;
        this.participantProfileRepository = participantProfileRepository;
        this.playerAssignmentRepository = playerAssignmentRepository;
        this.coachProfileRepository = coachProfileRepository;
        this.coachTimeSlotRepository = coachTimeSlotRepository;
        this.timeSlotRepository = timeSlotRepository;
        this.trainingBlockGenerationService = trainingBlockGenerationService;
        this.fieldDefinitionRepository = fieldDefinitionRepository;
        this.customFieldValueRepository = customFieldValueRepository;
        this.levelService = levelService;
        this.groupGenerator = groupGenerator;
    }

    /** Loads {@code test-data/datasets/{name}} and returns the created plan's id, fully ready for
     * {@code SolverInputAssembler.assemble} (groups generated, levels recomputed). */
    public String load(String datasetName) {
        Path dir = Path.of("..", "test-data", "datasets", datasetName);
        Map<String, String> config = readKeyValueCsv(dir.resolve("config.csv"));
        String category = config.get("category");
        Integer target = Integer.valueOf(config.get("target_size"));
        Integer min = Integer.valueOf(config.get("min_size"));
        Integer max = Integer.valueOf(config.get("max_size"));

        Instant now = Instant.now();
        SeasonPlan season = seasonPlanRepository.insert(new SeasonPlan(Uuid7.generate(), "VT-test", null, null, "active", now, now));
        ActivityPlan plan = activityPlanRepository.insert(
                new ActivityPlan(Uuid7.generate(), season.id(), category, category, "draft", target, min, max, now, now));
        String planId = plan.id();

        Map<String, String> timeSlotIdByShortId = loadTimeSlotsAndBlocks(dir.resolve("timeslots.csv"), planId);
        Map<String, String> participantIdByShortId = new HashMap<>();
        Map<String, String> personIdByParticipantShortId = new HashMap<>();
        loadParticipants(dir.resolve("participants.csv"), planId, timeSlotIdByShortId, participantIdByShortId, personIdByParticipantShortId);
        loadCoaches(dir.resolve("coaches.csv"), planId, timeSlotIdByShortId, participantIdByShortId, personIdByParticipantShortId);

        levelService.recomputeForPlan(planId);
        groupGenerator.generate(planId);

        return planId;
    }

    private Map<String, String> loadTimeSlotsAndBlocks(Path csv, String planId) {
        Map<String, String> timeSlotIdByShortId = new HashMap<>();
        List<Map<String, String>> rows = readCsv(csv);
        for (Map<String, String> row : rows) {
            String label = row.get("label");
            String dayOfWeek = java.time.DayOfWeek.of(Integer.parseInt(row.get("day_of_week"))).name();
            TimeSlot slot = timeSlotRepository.insert(new TimeSlot(
                    Uuid7.generate(), planId, dayOfWeek, null, row.get("start_time"), row.get("end_time"), null, label));
            timeSlotIdByShortId.put(row.get("id"), slot.id());
            int courtsPerSlot = Integer.parseInt(row.get("courts_per_slot"));
            trainingBlockGenerationService.generateForSlot(slot, courtsPerSlot);
        }
        return timeSlotIdByShortId;
    }

    private void loadParticipants(
            Path csv, String planId, Map<String, String> timeSlotIdByShortId,
            Map<String, String> participantIdByShortId, Map<String, String> personIdByParticipantShortId) {
        List<Map<String, String>> rows = readCsv(csv);
        Instant now = Instant.now();

        // Pass 1: create person/participant rows so relation fields (which may reference any other
        // participant regardless of file order) can always resolve.
        for (Map<String, String> row : rows) {
            Person person = personRepository.insert(new Person(
                    Uuid7.generate(), row.get("first_name"), row.get("last_name"), null, row.get("email"), null, null,
                    true, false, null, now, now));
            boolean needsReview = "1".equals(row.get("needs_review"));
            ParticipantProfile profile = participantProfileRepository.insert(new ParticipantProfile(
                    Uuid7.generate(), person.id(), planId,
                    parseDouble(row.get("ranking_points")), blankToNull(row.get("ranking_points")) == null ? null : "seriespel",
                    blankToNull(row.get("previous_group_name")), parseDouble(row.get("previous_group_level")),
                    null, null, parseDouble(row.get("manual_level_score")), null, null, needsReview, false));
            participantIdByShortId.put(row.get("id"), profile.id());
            personIdByParticipantShortId.put(row.get("id"), person.id());
            playerAssignmentRepository.insertImportedIfAbsent(profile.id());
        }

        // Pass 2: relation/priority/time custom field values (needs the full id map from pass 1).
        String priorityFieldId = requireGlobalField("priority").id();
        String cannotTimesFieldId = requireGlobalField("cannotTimes").id();
        String preferTimesFieldId = requireGlobalField("preferTimes").id();
        String playWithFieldId = requireGlobalField("playWith").id();
        String mustPlayWithFieldId = requireGlobalField("mustPlayWith").id();
        String mustNotPlayWithFieldId = requireGlobalField("mustNotPlayWith").id();

        for (Map<String, String> row : rows) {
            String participantId = participantIdByShortId.get(row.get("id"));
            String priority = blankToNull(row.get("priority"));
            if (priority != null) {
                customFieldValueRepository.upsert(priorityFieldId, "participant", participantId, priority);
            }
            writeTimeSlotArray(cannotTimesFieldId, participantId, row.get("unavailable_time_slot_ids"), timeSlotIdByShortId);
            writeTimeSlotArray(preferTimesFieldId, participantId, row.get("preferred_time_slot_ids"), timeSlotIdByShortId);
            writeIdArray(playWithFieldId, participantId, row.get("wants_with_ids"), participantIdByShortId);
            writeIdArray(mustPlayWithFieldId, participantId, row.get("must_with_ids"), participantIdByShortId);
            writeIdArray(mustNotPlayWithFieldId, participantId, row.get("not_with_ids"), participantIdByShortId);
            // wants_coach_id references a coach short id, resolved once coaches are loaded (pass
            // deferred to loadCoaches via the raw short id stored as a plain text marker is
            // unnecessary complexity here - coaches.csv is small and independent, so instead we
            // simply re-scan participants after coaches load, see loadCoaches's second pass).
        }
    }

    private void loadCoaches(
            Path csv, String planId, Map<String, String> timeSlotIdByShortId,
            Map<String, String> participantIdByShortId, Map<String, String> personIdByParticipantShortId) {
        List<Map<String, String>> rows = readCsv(csv);
        Instant now = Instant.now();
        Map<String, String> coachProfileIdByShortId = new HashMap<>();

        for (Map<String, String> row : rows) {
            String alsoPlaysParticipantShortId = blankToNull(row.get("also_plays_participant_id"));
            Person person;
            if (alsoPlaysParticipantShortId != null) {
                String existingPersonId = personIdByParticipantShortId.get(alsoPlaysParticipantShortId);
                person = personRepository.findById(existingPersonId).orElseThrow();
                Person updated = new Person(
                        person.id(), person.firstName(), person.lastName(), person.displayName(), person.email(),
                        person.phone(), person.externalId(), person.canBeParticipant(), true, person.notes(),
                        person.createdAt(), now);
                personRepository.update(updated);
            } else {
                person = personRepository.insert(new Person(
                        Uuid7.generate(), row.get("first_name"), row.get("last_name"), null, null, null, null,
                        false, true, null, now, now));
            }
            CoachProfile coach = coachProfileRepository.insert(new CoachProfile(
                    Uuid7.generate(), person.id(), planId,
                    parseDouble(row.get("coach_level")), parseDouble(row.get("can_coach_min")), parseDouble(row.get("can_coach_max")),
                    parseInt(row.get("max_groups_per_day")), parseInt(row.get("max_groups_per_week")),
                    alsoPlaysParticipantShortId != null, null));
            coachProfileIdByShortId.put(row.get("id"), coach.id());

            // A slot can legitimately appear in BOTH columns in these fixtures (e.g. large-120's c05:
            // "available t01-t04, EXCEPT unavailable t04") - the coach_time_slot UNIQUE(coach, slot)
            // constraint means only one row per pair is possible, so UNAVAILABLE wins (the more
            // restrictive, carve-out signal - matching its role as the sole thing that actually
            // blocks a coach, per the M5 semantic decision that unlisted/AVAILABLE both mean
            // available anyway).
            List<String> unavailableShortIds = splitMulti(row.get("unavailable_time_slot_ids"));
            for (String shortSlotId : splitMulti(row.get("available_time_slot_ids"))) {
                if (unavailableShortIds.contains(shortSlotId)) {
                    continue;
                }
                String slotId = timeSlotIdByShortId.get(shortSlotId);
                if (slotId != null) {
                    coachTimeSlotRepository.insert(new CoachTimeSlot(Uuid7.generate(), coach.id(), slotId, CoachTimeSlot.AVAILABLE));
                }
            }
            for (String shortSlotId : unavailableShortIds) {
                String slotId = timeSlotIdByShortId.get(shortSlotId);
                if (slotId != null) {
                    coachTimeSlotRepository.insert(new CoachTimeSlot(Uuid7.generate(), coach.id(), slotId, CoachTimeSlot.UNAVAILABLE));
                }
            }
        }

        // Deferred pass: participants.csv's wants_coach_id column, now that coach ids are known.
        String wantsCoachFieldId = requireGlobalField("wantsCoach").id();
        for (Map<String, String> row : readCsv(Path.of("..", "test-data", "datasets", datasetNameOf(csv), "participants.csv"))) {
            String coachShortId = blankToNull(row.get("wants_coach_id"));
            if (coachShortId == null) {
                continue;
            }
            String coachProfileId = coachProfileIdByShortId.get(coachShortId);
            String participantId = participantIdByShortId.get(row.get("id"));
            if (coachProfileId != null && participantId != null) {
                customFieldValueRepository.upsert(wantsCoachFieldId, "participant", participantId, "[\"" + coachProfileId + "\"]");
            }
        }
    }

    private static String datasetNameOf(Path coachesCsv) {
        // coaches.csv lives directly under test-data/datasets/{name}/ - the parent directory name.
        return coachesCsv.getParent().getFileName().toString();
    }

    private void writeTimeSlotArray(String fieldId, String participantId, String rawShortIds, Map<String, String> timeSlotIdByShortId) {
        List<String> shortIds = splitMulti(rawShortIds);
        if (shortIds.isEmpty()) {
            return;
        }
        List<String> resolved = new ArrayList<>();
        for (String shortId : shortIds) {
            String slotId = timeSlotIdByShortId.get(shortId);
            if (slotId != null) {
                resolved.add(slotId);
            }
        }
        customFieldValueRepository.upsert(fieldId, "participant", participantId, toJsonArray(resolved));
    }

    private void writeIdArray(String fieldId, String participantId, String rawShortIds, Map<String, String> idByShortId) {
        List<String> shortIds = splitMulti(rawShortIds);
        if (shortIds.isEmpty()) {
            return;
        }
        List<String> resolved = new ArrayList<>();
        for (String shortId : shortIds) {
            String id = idByShortId.get(shortId);
            if (id != null) {
                resolved.add(id);
            }
        }
        if (!resolved.isEmpty()) {
            customFieldValueRepository.upsert(fieldId, "participant", participantId, toJsonArray(resolved));
        }
    }

    private FieldDefinition requireGlobalField(String key) {
        return fieldDefinitionRepository.findGlobalByKey(key)
                .orElseThrow(() -> new IllegalStateException("Missing seeded standard field: " + key));
    }

    private static String toJsonArray(List<String> values) {
        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < values.size(); i++) {
            if (i > 0) {
                sb.append(',');
            }
            sb.append('"').append(values.get(i)).append('"');
        }
        return sb.append(']').toString();
    }

    private static List<String> splitMulti(String raw) {
        if (raw == null || raw.isBlank()) {
            return List.of();
        }
        List<String> out = new ArrayList<>();
        for (String token : raw.split(";")) {
            if (!token.isBlank()) {
                out.add(token.strip());
            }
        }
        return out;
    }

    private static String blankToNull(String s) {
        return (s == null || s.isBlank()) ? null : s;
    }

    private static Double parseDouble(String s) {
        return blankToNull(s) == null ? null : Double.valueOf(s);
    }

    private static Integer parseInt(String s) {
        return blankToNull(s) == null ? null : Integer.valueOf(s);
    }

    private static Map<String, String> readKeyValueCsv(Path path) {
        Map<String, String> map = new HashMap<>();
        List<String> lines = readLines(path);
        for (String line : lines.subList(1, lines.size())) {
            String[] cols = line.split(",", -1);
            map.put(cols[0], cols[1]);
        }
        return map;
    }

    private static List<Map<String, String>> readCsv(Path path) {
        List<String> lines = readLines(path);
        String[] header = lines.get(0).split(",", -1);
        List<Map<String, String>> rows = new ArrayList<>();
        for (int i = 1; i < lines.size(); i++) {
            if (lines.get(i).isBlank()) {
                continue;
            }
            String[] cols = lines.get(i).split(",", -1);
            Map<String, String> row = new HashMap<>();
            for (int c = 0; c < header.length && c < cols.length; c++) {
                row.put(header[c], cols[c]);
            }
            rows.add(row);
        }
        return rows;
    }

    private static List<String> readLines(Path path) {
        try {
            return Files.readAllLines(path, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to read test dataset CSV: " + path, e);
        }
    }
}
