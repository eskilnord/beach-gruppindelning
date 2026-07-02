package se.klubb.groupplanner.demo;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import java.time.Instant;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Random;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import se.klubb.groupplanner.domain.ActivityPlan;
import se.klubb.groupplanner.domain.CoachProfile;
import se.klubb.groupplanner.domain.CoachTimeSlot;
import se.klubb.groupplanner.domain.CustomFieldValue;
import se.klubb.groupplanner.domain.ParticipantProfile;
import se.klubb.groupplanner.domain.Person;
import se.klubb.groupplanner.domain.SeasonPlan;
import se.klubb.groupplanner.domain.TimeSlot;
import se.klubb.groupplanner.fields.FieldValueService;
import se.klubb.groupplanner.level.LevelService;
import se.klubb.groupplanner.repo.ActivityPlanRepository;
import se.klubb.groupplanner.repo.CoachProfileRepository;
import se.klubb.groupplanner.repo.CoachTimeSlotRepository;
import se.klubb.groupplanner.repo.ParticipantProfileRepository;
import se.klubb.groupplanner.repo.PersonRepository;
import se.klubb.groupplanner.repo.PlayerAssignmentRepository;
import se.klubb.groupplanner.repo.SeasonPlanRepository;
import se.klubb.groupplanner.repo.TimeSlotRepository;
import se.klubb.groupplanner.resources.TimeSlotLabelFormatter;
import se.klubb.groupplanner.resources.TrainingBlockGenerationService;
import se.klubb.groupplanner.solver.assemble.GroupGenerator;
import se.klubb.groupplanner.util.Uuid7;

/**
 * v0.3.0 user feedback ("Ha demo-data för beachvolley så att man kan dema det utan att importera en
 * excelfil."): one click creates a complete, obviously-fictional, ready-to-solve beach volleyball
 * season+plan so the council can demo the app (including to other clubs / for marketing) without
 * ever touching a real xlsx file.
 *
 * <h2>Why this writes through the same services the import wizard/REST controllers use</h2>
 *
 * Every row this class creates goes through the exact repository/service methods {@code
 * ParticipantProfileController}, {@code CoachController}, {@code TimeSlotController}, {@code
 * ParticipantFieldValueController} (via {@link FieldValueService}) and the import commit path all
 * use — never a hand-built solver POJO or raw SQL. That means the same invariants/validation apply
 * (e.g. {@link FieldValueService} rejects a relation id that doesn't belong to this plan), and —
 * critically for CLAUDE.md's confidentiality rule — the demo's "innocuous Swedish comments" land in
 * {@code participant_profile.imported_comment} through {@link ParticipantProfileRepository}, the
 * identical column/path the comment-leak tests (e.g. {@code ExplanationRecordLeakTest}) already
 * guard, so this feature can never become a new privacy hole.
 *
 * <h2>Determinism</h2>
 *
 * A single {@link Random} seeded with a fixed literal ({@link #RANDOM_SEED}), consumed in one fixed,
 * linear order (coach names, then participant names/levels), is the only source of "randomness" —
 * {@code java.util.Random} is specified to behave identically given the same seed and call sequence
 * on every platform (see its class javadoc), so every "Prova med demodata" click across machines/OSes
 * produces byte-identical rosters, wishes and levels. Every structural choice (who is unavailable,
 * who has a must-coach wish, which pairs are "impossible") is a fixed participant INDEX, not a random
 * draw, so those invariants are exact and assertable in tests.
 *
 * <h2>Coach-capacity math (why the plan is guaranteed hard-feasible)</h2>
 *
 * {@value #PLAYER_COUNT} players are split into {@value #TARGET_SIZE}-sized groups across two
 * consecutive Thursday time slots × {@value #COURTS_PER_SLOT} courts each ({@value
 * #COURTS_PER_SLOT} × 2 = 8 {@code TrainingBlock}s), so {@code GroupGenerator} always produces
 * exactly 8 groups (capped by block count, not by participant count) — 4 concurrent groups per slot.
 * With {@value #COACH_COUNT} coaches, 3 of them fully flexible (both slots, no cap) and only one
 * coach unavailable a slot / one coach capped at 2 groups/week, per-slot capacity is 5 (slot 1) and 4
 * (slot 2) against a need of 4 each — always enough for a perfect coach/group matching to exist, so
 * {@code coachRequirementHard} can always reach zero regardless of how the solver's search unfolds.
 * The plan is NOT capacity-infeasible: 8 blocks × max size {@value #MAX_SIZE} = 96 seats for
 * {@value #PLAYER_COUNT} players, so a handful of players naturally land on the waitlist (MEDIUM,
 * never HARD) purely from realistic capacity pressure, on top of the one participant this class
 * deliberately makes unavailable at every slot (see {@link #ALWAYS_UNAVAILABLE}) — both demonstrate
 * the waitlist/explainability features without ever making the plan impossible to solve cleanly.
 */
@Service
public class DemoDataService {

    private static final String SEASON_NAME_BASE = "Demo – Beachvolley VT27";
    private static final String PLAN_NAME = "Demodata torsdagsträning";
    private static final String CATEGORY = "Torsdagsträning";

    private static final int TARGET_SIZE = 10;
    private static final int MIN_SIZE = 8;
    private static final int MAX_SIZE = 12;
    private static final int COURTS_PER_SLOT = 4;

    static final int PLAYER_COUNT = 100;
    static final int COACH_COUNT = 5;

    private static final long RANDOM_SEED = 42L;
    private static final double LEVEL_MEAN = 550.0;
    private static final double LEVEL_STDDEV = 140.0;
    private static final double LEVEL_MIN = 150.0;
    private static final double LEVEL_MAX = 950.0;

    // --- fixed, deterministic participant roles (indices into the 0-based player list) ---------
    // A participant unavailable at every slot in this plan: no feasible placement exists for them,
    // so the solver always leaves them unassigned (MEDIUM unassignedPlayer cost only, never a HARD
    // violation) - demonstrates the "why is this person on the waitlist" explainability feature.
    static final int ALWAYS_UNAVAILABLE = 0;
    // The two "must have this coach" targets - also the B-end of the two WI-5 indirect-explanation
    // chains (A wants to play with B, B must have a specific coach -> A's own placement/explanation
    // indirectly references that coach).
    static final int MUST_HAVE_COACH_A = 1;
    static final int MUST_HAVE_COACH_B = 2;
    static final int CANNOT_HAVE_COACH = 3;
    static final int CHAIN_SOURCE_A = 4; // playWith -> MUST_HAVE_COACH_A
    static final int CHAIN_SOURCE_B = 5; // playWith -> MUST_HAVE_COACH_B
    // Wildly different levels, forced below, but they mutually want to play together: an
    // unsatisfiable-in-practice soft wish that trades off against levelBalance (explainability demo).
    static final int IMPOSSIBLE_PAIR_LOW = 6;
    static final int IMPOSSIBLE_PAIR_HIGH = 7;

    private static final int[][] MUST_PLAY_WITH_PAIRS = {{8, 9}, {10, 11}, {12, 13}};
    private static final int[][] AVOID_PLAY_WITH_PAIRS = {{14, 15}, {16, 17}, {18, 19}};
    private static final int[][] ORDINARY_PLAY_WITH_PAIRS =
            {{20, 21}, {22, 23}, {24, 25}, {26, 27}, {28, 29}, {30, 31}, {32, 33}, {34, 35}, {36, 37}};
    private static final int[][] ONE_DIRECTIONAL_PLAY_WITH = {{38, 39}, {40, 41}, {42, 43}};
    private static final int[] WANTS_COACH_INDICES = {44, 45, 46, 47, 48, 49, 50, 51};
    // Indices 52..59 each carry one of DEMO_COMMENTS below (imported_comment) - innocuous, obviously
    // fictional Swedish free text, purely to demo the comment-privacy machinery.
    private static final int FIRST_COMMENT_INDEX = 52;
    private static final int[] NEW_TO_CLUB_INDICES = {53, 57};

    private static final String[] DEMO_COMMENTS = {
        "Vill helst träna tidigt på kvällen.",
        "Ny i klubben, kommer från Sandslottets BK.",
        "Har lite ont i axeln just nu, tar det lugnt ett tag.",
        "Åker på landslagsläger första veckan i juni, kan missa ett pass.",
        "Vill gärna testa en mer tävlingsinriktad grupp i höst.",
        "Ny i klubben och känner ingen ännu.",
        "Kommer ofta några minuter sent till uppvärmningen.",
        "Frågade kansliet om att gå ner en nivå efter en skada i våras.",
    };

    private static final String[] FIRST_NAMES = {
        "Astrid", "Bengt", "Cecilia", "David", "Elsa", "Filip", "Greta", "Hampus", "Ida", "Jonas",
        "Klara", "Ludvig", "Maja", "Niklas", "Olivia", "Pontus", "Rebecka", "Samuel", "Tilda", "Urban",
        "Vera", "William", "Ylva", "Åke", "Örjan", "Åsa", "Björn", "Cornelia", "Sixten", "Wilma",
    };

    private static final String[] LAST_NAMES = {
        "Andersson", "Johansson", "Karlsson", "Nilsson", "Eriksson", "Larsson", "Olsson", "Persson",
        "Svensson", "Gustafsson", "Pettersson", "Jonsson", "Jansson", "Hansson", "Bengtsson",
        "Lindqvist", "Lindgren", "Åberg", "Öhman", "Sjögren", "Wallin", "Eklund", "Holmqvist", "Dahlström",
        "Nyström", "Bäckman", "Söderlund", "Hedlund", "Blomberg", "Åkesson",
    };

    private final SeasonPlanRepository seasonPlanRepository;
    private final ActivityPlanRepository activityPlanRepository;
    private final PersonRepository personRepository;
    private final ParticipantProfileRepository participantProfileRepository;
    private final PlayerAssignmentRepository playerAssignmentRepository;
    private final CoachProfileRepository coachProfileRepository;
    private final CoachTimeSlotRepository coachTimeSlotRepository;
    private final TimeSlotRepository timeSlotRepository;
    private final TrainingBlockGenerationService trainingBlockGenerationService;
    private final TimeSlotLabelFormatter labelFormatter;
    private final FieldValueService fieldValueService;
    private final LevelService levelService;
    private final GroupGenerator groupGenerator;
    private final ObjectMapper objectMapper;

    public DemoDataService(
            SeasonPlanRepository seasonPlanRepository,
            ActivityPlanRepository activityPlanRepository,
            PersonRepository personRepository,
            ParticipantProfileRepository participantProfileRepository,
            PlayerAssignmentRepository playerAssignmentRepository,
            CoachProfileRepository coachProfileRepository,
            CoachTimeSlotRepository coachTimeSlotRepository,
            TimeSlotRepository timeSlotRepository,
            TrainingBlockGenerationService trainingBlockGenerationService,
            TimeSlotLabelFormatter labelFormatter,
            FieldValueService fieldValueService,
            LevelService levelService,
            GroupGenerator groupGenerator,
            ObjectMapper objectMapper) {
        this.seasonPlanRepository = seasonPlanRepository;
        this.activityPlanRepository = activityPlanRepository;
        this.personRepository = personRepository;
        this.participantProfileRepository = participantProfileRepository;
        this.playerAssignmentRepository = playerAssignmentRepository;
        this.coachProfileRepository = coachProfileRepository;
        this.coachTimeSlotRepository = coachTimeSlotRepository;
        this.timeSlotRepository = timeSlotRepository;
        this.trainingBlockGenerationService = trainingBlockGenerationService;
        this.labelFormatter = labelFormatter;
        this.fieldValueService = fieldValueService;
        this.levelService = levelService;
        this.groupGenerator = groupGenerator;
        this.objectMapper = objectMapper;
    }

    /**
     * Creates a brand-new demo season+plan, fully populated and ready for "Generera grupper"/solve.
     * Safe to call repeatedly (the orchestrator/frontend button is always enabled, even with existing
     * data): the season name gets a numbered suffix ({@code "(2)"}, {@code "(3)"}, ...) rather than
     * colliding or failing when a demo season already exists.
     */
    @Transactional
    public DemoResult createDemoSeason() {
        Instant now = Instant.now();
        SeasonPlan season = seasonPlanRepository.insert(
                new SeasonPlan(Uuid7.generate(), uniqueSeasonName(), null, null, "active", now, now));
        ActivityPlan plan = activityPlanRepository.insert(new ActivityPlan(
                Uuid7.generate(), season.id(), PLAN_NAME, CATEGORY, "draft", TARGET_SIZE, MIN_SIZE, MAX_SIZE, null, now, now));
        String planId = plan.id();

        TimeSlot slot1 = insertSlot(planId, "THURSDAY", "18:00", "19:30");
        TimeSlot slot2 = insertSlot(planId, "THURSDAY", "19:30", "21:00");
        trainingBlockGenerationService.generateForSlot(slot1, COURTS_PER_SLOT);
        trainingBlockGenerationService.generateForSlot(slot2, COURTS_PER_SLOT);

        Random rng = new Random(RANDOM_SEED);
        List<String> coachIds = createCoaches(planId, slot1, slot2, rng);
        List<String> participantIds = createParticipants(planId, rng);
        wireRelations(planId, participantIds, coachIds, slot1, slot2);

        levelService.recomputeForPlan(planId);
        groupGenerator.generate(planId);
        activityPlanRepository.bumpRevision(planId);

        return new DemoResult(season.id(), planId);
    }

    private String uniqueSeasonName() {
        List<String> existingNames = seasonPlanRepository.findAll().stream().map(SeasonPlan::name).toList();
        if (!existingNames.contains(SEASON_NAME_BASE)) {
            return SEASON_NAME_BASE;
        }
        int suffix = 2;
        while (existingNames.contains(SEASON_NAME_BASE + " (" + suffix + ")")) {
            suffix++;
        }
        return SEASON_NAME_BASE + " (" + suffix + ")";
    }

    private TimeSlot insertSlot(String planId, String dayOfWeek, String startRaw, String endRaw) {
        LocalTime start = labelFormatter.parseTime(startRaw, "startTime");
        LocalTime end = labelFormatter.parseTime(endRaw, "endTime");
        int duration = labelFormatter.durationMinutes(start, end);
        String label = labelFormatter.autoLabel(dayOfWeek, null, start, end);
        return timeSlotRepository.insert(new TimeSlot(
                Uuid7.generate(), planId, dayOfWeek, null, labelFormatter.normalize(start), labelFormatter.normalize(end),
                duration, label));
    }

    /**
     * Five coaches with fixed (not RNG-derived) levels/availability/caps — see the class javadoc's
     * "Coach-capacity math" section for why exactly these numbers keep the plan hard-feasible. Only
     * the coaches' names are drawn from {@link #RANDOM_SEED}'s deterministic sequence.
     */
    private List<String> createCoaches(String planId, TimeSlot slot1, TimeSlot slot2, Random rng) {
        Instant now = Instant.now();
        CoachSpec[] specs = {
            // Fully flexible - the two mustHaveCoach chain anchors are pinned to these, so wherever
            // that participant's group lands (either slot), the coach is always available.
            new CoachSpec(500.0, 100.0, 900.0, null, null, null),
            new CoachSpec(650.0, 200.0, 1000.0, null, null, null),
            // Unavailable the later slot (demo: coachAvailabilityHard).
            new CoachSpec(350.0, 100.0, 700.0, slot2.id(), null, null),
            // Capped at 2 groups/week (demo: coachMaxGroups) - also the cannotHaveCoach target.
            new CoachSpec(800.0, 400.0, 1000.0, null, 1, 2),
            new CoachSpec(550.0, 150.0, 850.0, null, null, null),
        };
        List<String> ids = new ArrayList<>(specs.length);
        for (CoachSpec spec : specs) {
            String firstName = FIRST_NAMES[rng.nextInt(FIRST_NAMES.length)];
            String lastName = LAST_NAMES[rng.nextInt(LAST_NAMES.length)];
            Person person = personRepository.insert(new Person(
                    Uuid7.generate(), firstName, lastName, null, null, null, null, false, true, null, now, now));
            CoachProfile coach = coachProfileRepository.insert(new CoachProfile(
                    Uuid7.generate(), person.id(), planId, spec.level(), spec.minLevel(), spec.maxLevel(),
                    spec.maxGroupsPerDay(), spec.maxGroupsPerWeek(), false, null));
            insertAvailability(coach.id(), slot1.id(), spec.unavailableSlotId());
            insertAvailability(coach.id(), slot2.id(), spec.unavailableSlotId());
            ids.add(coach.id());
        }
        return ids;
    }

    private void insertAvailability(String coachProfileId, String slotId, String unavailableSlotId) {
        String kind = slotId.equals(unavailableSlotId) ? CoachTimeSlot.UNAVAILABLE : CoachTimeSlot.AVAILABLE;
        coachTimeSlotRepository.insert(new CoachTimeSlot(Uuid7.generate(), coachProfileId, slotId, kind));
    }

    /**
     * 100 fictional players: Swedish names drawn from the fixed pools above, levels spread bell-ish
     * across [{@value #LEVEL_MIN}, {@value #LEVEL_MAX}] via {@link Random#nextGaussian()} (mean
     * {@value #LEVEL_MEAN}, stddev {@value #LEVEL_STDDEV}, clamped) - except indices {@link
     * #IMPOSSIBLE_PAIR_LOW}/{@link #IMPOSSIBLE_PAIR_HIGH}, whose levels are forced to the extremes so
     * their mutual "vill spela med"-wish is a guaranteed, assertable trade-off regardless of what the
     * RNG would otherwise have drawn. No personnummer field exists on {@code person} (verified against
     * the schema) and no email/phone is set - nothing here resembles real contact information.
     */
    private List<String> createParticipants(String planId, Random rng) {
        Instant now = Instant.now();
        List<String> ids = new ArrayList<>(PLAYER_COUNT);
        for (int i = 0; i < PLAYER_COUNT; i++) {
            String firstName = FIRST_NAMES[rng.nextInt(FIRST_NAMES.length)];
            String lastName = LAST_NAMES[rng.nextInt(LAST_NAMES.length)];
            double rawLevel = LEVEL_MEAN + rng.nextGaussian() * LEVEL_STDDEV;
            double level = clamp(rawLevel, LEVEL_MIN, LEVEL_MAX);
            if (i == IMPOSSIBLE_PAIR_LOW) {
                level = LEVEL_MIN + 30.0;
            } else if (i == IMPOSSIBLE_PAIR_HIGH) {
                level = LEVEL_MAX - 30.0;
            }
            Double rankingPoints = (double) Math.round(level);
            String comment = commentForIndex(i);

            Person person = personRepository.insert(new Person(
                    Uuid7.generate(), firstName, lastName, null, null, null, null, true, false, null, now, now));
            ParticipantProfile profile = participantProfileRepository.insert(new ParticipantProfile(
                    Uuid7.generate(), person.id(), planId, rankingPoints, "seriespel", null, null, null, null, null,
                    comment, null, false, false));
            playerAssignmentRepository.insertImportedIfAbsent(profile.id());
            ids.add(profile.id());
        }
        return ids;
    }

    private static String commentForIndex(int index) {
        int commentIndex = index - FIRST_COMMENT_INDEX;
        if (commentIndex < 0 || commentIndex >= DEMO_COMMENTS.length) {
            return null;
        }
        return DEMO_COMMENTS[commentIndex];
    }

    /**
     * Wires every wish/coach-relation/time-constraint/info field via {@link FieldValueService} —
     * the same validated code path {@code ParticipantFieldValueController} uses — so a demo wish can
     * never reference an id outside this plan. See the class javadoc for the exact roster of
     * relationships (~25 playWith, 3 mustPlayWith pairs, 3 avoidPlayWith pairs, 8 wantsCoach, 2
     * mustHaveCoach, 1 cannotHaveCoach, including the two WI-5 indirect-explanation chains and one
     * impossible-levels mutual playWith pair).
     */
    private void wireRelations(String planId, List<String> participantIds, List<String> coachIds, TimeSlot slot1, TimeSlot slot2) {
        putValues(planId, participantIds.get(ALWAYS_UNAVAILABLE),
                Map.of("cannotTimes", idArray(slot1.id(), slot2.id())));

        putValues(planId, participantIds.get(MUST_HAVE_COACH_A), Map.of("mustHaveCoach", idArray(coachIds.get(0))));
        putValues(planId, participantIds.get(MUST_HAVE_COACH_B), Map.of("mustHaveCoach", idArray(coachIds.get(1))));
        putValues(planId, participantIds.get(CANNOT_HAVE_COACH), Map.of("cannotHaveCoach", idArray(coachIds.get(3))));

        // The two WI-5 indirect-explanation chains: A wants to play with B, and B must have a coach.
        putValues(planId, participantIds.get(CHAIN_SOURCE_A),
                Map.of("playWith", idArray(participantIds.get(MUST_HAVE_COACH_A))));
        putValues(planId, participantIds.get(CHAIN_SOURCE_B),
                Map.of("playWith", idArray(participantIds.get(MUST_HAVE_COACH_B))));

        mutualField(planId, participantIds, IMPOSSIBLE_PAIR_LOW, IMPOSSIBLE_PAIR_HIGH, "playWith");

        for (int[] pair : MUST_PLAY_WITH_PAIRS) {
            mutualField(planId, participantIds, pair[0], pair[1], "mustPlayWith");
        }
        for (int[] pair : AVOID_PLAY_WITH_PAIRS) {
            mutualField(planId, participantIds, pair[0], pair[1], "avoidPlayWith");
        }
        for (int[] pair : ORDINARY_PLAY_WITH_PAIRS) {
            mutualField(planId, participantIds, pair[0], pair[1], "playWith");
        }
        for (int[] edge : ONE_DIRECTIONAL_PLAY_WITH) {
            putValues(planId, participantIds.get(edge[0]), Map.of("playWith", idArray(participantIds.get(edge[1]))));
        }

        for (int i = 0; i < WANTS_COACH_INDICES.length; i++) {
            String coachId = coachIds.get(i % coachIds.size());
            putValues(planId, participantIds.get(WANTS_COACH_INDICES[i]), Map.of("wantsCoach", idArray(coachId)));
        }

        for (int index : NEW_TO_CLUB_INDICES) {
            putValues(planId, participantIds.get(index),
                    Map.of("newToClub", objectMapper.getNodeFactory().booleanNode(true)));
        }
    }

    private void mutualField(String planId, List<String> participantIds, int indexA, int indexB, String fieldKey) {
        putValues(planId, participantIds.get(indexA), Map.of(fieldKey, idArray(participantIds.get(indexB))));
        putValues(planId, participantIds.get(indexB), Map.of(fieldKey, idArray(participantIds.get(indexA))));
    }

    private void putValues(String planId, String participantId, Map<String, JsonNode> values) {
        fieldValueService.putValues(planId, CustomFieldValue.ENTITY_TYPE_PARTICIPANT, participantId, values);
    }

    private ArrayNode idArray(String... ids) {
        ArrayNode array = objectMapper.getNodeFactory().arrayNode(ids.length);
        for (String id : ids) {
            array.add(id);
        }
        return array;
    }

    private static double clamp(double value, double lo, double hi) {
        return Math.max(lo, Math.min(hi, value));
    }

    /** One coach's fixed (non-RNG) shape; only the {@link Person}'s name is drawn from the RNG. */
    private record CoachSpec(
            double level, double minLevel, double maxLevel, String unavailableSlotId,
            Integer maxGroupsPerDay, Integer maxGroupsPerWeek) {
    }

    public record DemoResult(String seasonId, String planId) {
    }
}
