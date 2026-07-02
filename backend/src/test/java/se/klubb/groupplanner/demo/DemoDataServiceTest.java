package se.klubb.groupplanner.demo;

import static org.assertj.core.api.Assertions.assertThat;

import ai.timefold.solver.core.api.score.buildin.hardmediumsoftlong.HardMediumSoftLongScore;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import se.klubb.groupplanner.domain.CoachProfile;
import se.klubb.groupplanner.domain.CustomFieldValue;
import se.klubb.groupplanner.domain.FieldDefinition;
import se.klubb.groupplanner.domain.ParticipantProfile;
import se.klubb.groupplanner.domain.Person;
import se.klubb.groupplanner.domain.TimeSlot;
import se.klubb.groupplanner.domain.TrainingGroup;
import se.klubb.groupplanner.repo.CoachProfileRepository;
import se.klubb.groupplanner.repo.CustomFieldValueRepository;
import se.klubb.groupplanner.repo.FieldDefinitionRepository;
import se.klubb.groupplanner.repo.ParticipantProfileRepository;
import se.klubb.groupplanner.repo.PersonRepository;
import se.klubb.groupplanner.repo.TimeSlotRepository;
import se.klubb.groupplanner.repo.TrainingGroupRepository;
import se.klubb.groupplanner.solver.TestSolverFactory;
import se.klubb.groupplanner.solver.assemble.SolverInputAssembler;
import se.klubb.groupplanner.solver.domain.GroupPlanSolution;

/**
 * WI-4 (v0.3.0 user feedback: "Ha demo-data för beachvolley så att man kan dema det utan att
 * importera en excelfil.") — {@link DemoDataService} must produce a complete, realistic, obviously-
 * fictional plan that (a) never leaks anything personnummer/contact-info-shaped, (b) carries exactly
 * the wish/comment shape the class javadoc documents, (c) is idempotent-safe to call repeatedly, and
 * (d) actually solves hard-feasible.
 */
@SpringBootTest
class DemoDataServiceTest {

    private static final int STEP_COUNT_LIMIT = 20_000;

    @TempDir
    static Path dataDir;

    @DynamicPropertySource
    static void appDataDir(DynamicPropertyRegistry registry) {
        registry.add("app.data-dir", () -> dataDir.toString());
    }

    @Autowired
    private DemoDataService demoDataService;
    @Autowired
    private PersonRepository personRepository;
    @Autowired
    private ParticipantProfileRepository participantProfileRepository;
    @Autowired
    private CoachProfileRepository coachProfileRepository;
    @Autowired
    private TimeSlotRepository timeSlotRepository;
    @Autowired
    private TrainingGroupRepository trainingGroupRepository;
    @Autowired
    private FieldDefinitionRepository fieldDefinitionRepository;
    @Autowired
    private CustomFieldValueRepository customFieldValueRepository;
    @Autowired
    private SolverInputAssembler solverInputAssembler;

    @Test
    void createsExpectedCountsOfEverything() {
        DemoDataService.DemoResult result = demoDataService.createDemoSeason();

        List<ParticipantProfile> participants = participantProfileRepository.findByActivityPlanId(result.planId());
        List<CoachProfile> coaches = coachProfileRepository.findByActivityPlanId(result.planId());
        List<TimeSlot> slots = timeSlotRepository.findByActivityPlanId(result.planId());
        List<TrainingGroup> groups = trainingGroupRepository.findByActivityPlanId(result.planId());

        assertThat(participants).hasSize(DemoDataService.PLAYER_COUNT);
        assertThat(coaches).hasSize(DemoDataService.COACH_COUNT);
        assertThat(slots).hasSize(2);
        assertThat(groups).as("GroupGenerator must have produced groups").isNotEmpty();

        Map<String, Integer> wishCounts = countCustomFieldValuesByKey(result.planId(), participants);
        assertThat(wishCounts.getOrDefault("playWith", 0)).isEqualTo(25);
        assertThat(wishCounts.getOrDefault("mustPlayWith", 0)).isEqualTo(6);
        assertThat(wishCounts.getOrDefault("avoidPlayWith", 0)).isEqualTo(6);
        assertThat(wishCounts.getOrDefault("wantsCoach", 0)).isEqualTo(8);
        assertThat(wishCounts.getOrDefault("mustHaveCoach", 0)).isEqualTo(2);
        assertThat(wishCounts.getOrDefault("cannotHaveCoach", 0)).isEqualTo(1);
        assertThat(wishCounts.getOrDefault("cannotTimes", 0)).isEqualTo(1);
        assertThat(wishCounts.getOrDefault("newToClub", 0)).isEqualTo(2);
    }

    @Test
    void noPersonHasAnyPersonnummerOrRealLookingContactInfo() {
        DemoDataService.DemoResult result = demoDataService.createDemoSeason();

        List<ParticipantProfile> participants = participantProfileRepository.findByActivityPlanId(result.planId());
        List<CoachProfile> coaches = coachProfileRepository.findByActivityPlanId(result.planId());

        for (ParticipantProfile p : participants) {
            assertNoContactInfo(personRepository.findById(p.personId()).orElseThrow());
        }
        for (CoachProfile c : coaches) {
            assertNoContactInfo(personRepository.findById(c.personId()).orElseThrow());
        }
    }

    private static void assertNoContactInfo(Person person) {
        // The `person` table has no personnummer column at all (verified against V1__core.sql) - this
        // asserts the next-closest thing: no email/phone/external id that could look like real contact
        // info or a source-system identifier ever gets set for demo data.
        assertThat(person.email()).isNull();
        assertThat(person.phone()).isNull();
        assertThat(person.externalId()).isNull();
    }

    @Test
    void commentsOnlyOnExpectedPlayersAndInternalNoteNeverSet() {
        DemoDataService.DemoResult result = demoDataService.createDemoSeason();
        List<ParticipantProfile> participants = participantProfileRepository.findByActivityPlanId(result.planId());

        long withComment = participants.stream().filter(p -> p.importedComment() != null).count();
        assertThat(withComment).isEqualTo(8);
        assertThat(participants).allSatisfy(p -> assertThat(p.internalNote()).isNull());
    }

    @Test
    void secondInvocationCreatesASecondSeasonWithoutFailing() {
        DemoDataService.DemoResult first = demoDataService.createDemoSeason();
        DemoDataService.DemoResult second = demoDataService.createDemoSeason();

        assertThat(second.seasonId()).isNotEqualTo(first.seasonId());
        assertThat(second.planId()).isNotEqualTo(first.planId());
        assertThat(participantProfileRepository.findByActivityPlanId(second.planId())).hasSize(DemoDataService.PLAYER_COUNT);
    }

    @Test
    void createdPlanSolvesHardFeasible() {
        DemoDataService.DemoResult result = demoDataService.createDemoSeason();

        GroupPlanSolution assembled = solverInputAssembler.assemble(result.planId()).solution();
        GroupPlanSolution solved = TestSolverFactory.solve(assembled, STEP_COUNT_LIMIT);

        HardMediumSoftLongScore score = solved.getScore();
        assertThat(score.hardScore())
                .as("the demo plan must always be solvable to hardScore == 0 (see DemoDataService's coach-capacity-math javadoc)")
                .isZero();
        // At least the deliberately always-unavailable participant (and, realistically, a few more
        // from raw capacity pressure - 8 groups x max 12 = 96 seats for 100 players) end up on the
        // waitlist: this demonstrates the waitlist/explainability feature by construction.
        assertThat(score.mediumScore()).as("at least the always-unavailable participant must be waitlisted").isLessThan(0);
    }

    private Map<String, Integer> countCustomFieldValuesByKey(String planId, List<ParticipantProfile> participants) {
        Map<String, FieldDefinition> fieldById = new HashMap<>();
        for (FieldDefinition field : fieldDefinitionRepository.findVisibleToPlan(planId)) {
            fieldById.put(field.id(), field);
        }
        Map<String, Integer> counts = new HashMap<>();
        for (ParticipantProfile p : participants) {
            for (CustomFieldValue value : customFieldValueRepository.findByEntity(CustomFieldValue.ENTITY_TYPE_PARTICIPANT, p.id())) {
                FieldDefinition field = fieldById.get(value.fieldDefinitionId());
                if (field == null) {
                    continue;
                }
                counts.merge(field.key(), 1, Integer::sum);
            }
        }
        return counts;
    }
}
