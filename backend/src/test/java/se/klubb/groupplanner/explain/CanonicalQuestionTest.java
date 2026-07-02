package se.klubb.groupplanner.explain;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import se.klubb.groupplanner.explain.ExplanationDtos.AlternativeGroupView;
import se.klubb.groupplanner.explain.ExplanationDtos.PersonExplanationResponse;
import se.klubb.groupplanner.repo.ActivityPlanRepository;
import se.klubb.groupplanner.repo.CoachAssignmentRepository;
import se.klubb.groupplanner.repo.CoachProfileRepository;
import se.klubb.groupplanner.repo.CustomFieldValueRepository;
import se.klubb.groupplanner.repo.FieldDefinitionRepository;
import se.klubb.groupplanner.repo.OptimizationRunRepository;
import se.klubb.groupplanner.repo.ParticipantProfileRepository;
import se.klubb.groupplanner.repo.PersonRepository;
import se.klubb.groupplanner.repo.PlayerAssignmentRepository;
import se.klubb.groupplanner.repo.SeasonPlanRepository;
import se.klubb.groupplanner.repo.TimeSlotRepository;
import se.klubb.groupplanner.repo.TrainingGroupRepository;
import se.klubb.groupplanner.resources.TrainingBlockGenerationService;

/**
 * THE CANONICAL QUESTION TEST (M-S3 gate b, this milestone's explicit gate): "Varför hamnade Kalle i
 * grupp Y och inte i grupp C med sin kompis?" answered end-to-end from data — Kalle has a friend-wish
 * for Lisa; Lisa's group (Grupp C) is full at maxSize with every member's priority &gt;= Kalle's.
 *
 * <p>Also covers the "waitlisted-friend edge" (docs/design/05-solver-verification.md amendment (c)):
 * when Lisa herself is unassigned instead, Kalle's broken-wish entry must read "Lisa är oplacerad
 * (kölista)" with a link (her participant id) to her own explanation — since neither side of a
 * personRelation join can produce a Timefold match once one side has no group.
 */
@SpringBootTest
class CanonicalQuestionTest {

    @TempDir
    static Path dataDir;

    @DynamicPropertySource
    static void appDataDir(DynamicPropertyRegistry registry) {
        registry.add("app.data-dir", () -> dataDir.toString());
    }

    @Autowired
    private SeasonPlanRepository seasonPlanRepository;
    @Autowired
    private ActivityPlanRepository activityPlanRepository;
    @Autowired
    private PersonRepository personRepository;
    @Autowired
    private ParticipantProfileRepository participantProfileRepository;
    @Autowired
    private PlayerAssignmentRepository playerAssignmentRepository;
    @Autowired
    private TrainingGroupRepository trainingGroupRepository;
    @Autowired
    private TimeSlotRepository timeSlotRepository;
    @Autowired
    private TrainingBlockGenerationService trainingBlockGenerationService;
    @Autowired
    private FieldDefinitionRepository fieldDefinitionRepository;
    @Autowired
    private CustomFieldValueRepository customFieldValueRepository;
    @Autowired
    private OptimizationRunRepository optimizationRunRepository;
    @Autowired
    private CoachProfileRepository coachProfileRepository;
    @Autowired
    private CoachAssignmentRepository coachAssignmentRepository;
    @Autowired
    private ExplanationService explanationService;

    private ExplanationTestFixture newFixture() {
        return new ExplanationTestFixture(
                seasonPlanRepository, activityPlanRepository, personRepository, participantProfileRepository,
                playerAssignmentRepository, trainingGroupRepository, timeSlotRepository, trainingBlockGenerationService,
                fieldDefinitionRepository, customFieldValueRepository, optimizationRunRepository,
                coachProfileRepository, coachAssignmentRepository);
    }

    @Test
    void kalleIsExplainedWhyHeIsNotInGroupCWithHisFriendLisa() {
        ExplanationTestFixture fx = newFixture();
        List<String> blocks = fx.addTimeSlotWithBlocks("Torsdag 18.00-19.30", 2);
        String groupY = fx.addGroup("Grupp Y", 1, 1, 5, 5, blocks.get(0));
        String groupC = fx.addGroup("Grupp C", 2, 1, 5, 5, blocks.get(1));

        String kalle = fx.addParticipant("Kalle", "Karlsson", 640.0, 3);
        fx.place(kalle, groupY);

        String lisa = fx.addParticipant("Lisa", "Larsson", 650.0, 3);
        fx.place(lisa, groupC);
        // 4 fillers + Lisa = 5 members, bringing Grupp C to its maxSize of 5 (full), all priority
        // >= Kalle's (3).
        for (int i = 0; i < 4; i++) {
            String filler = fx.addParticipant("Filler" + i, "Fillersson", 655.0, 3);
            fx.place(filler, groupC);
        }

        fx.wish(kalle, lisa, "playWith"); // WANT_SAME -> sameGroupSoft

        String runId = fx.insertFinishedRun();

        PersonExplanationResponse response = explanationService.explainPerson(fx.planId, runId, kalle);

        // --- selected group with positive factors ---
        assertThat(response.selectedGroup()).isNotNull();
        assertThat(response.selectedGroup().groupId()).isEqualTo(groupY);
        assertThat(response.positiveFactors()).isNotEmpty();

        // --- the broken wish ---
        assertThat(response.brokenWishes())
                .anyMatch(w -> w.key().equals("sameGroupSoft") && w.messageSv().contains("Kompisönskemål") && w.messageSv().contains("brutet"));

        // --- alternative for group C: origin FRIEND_WISH, verdict WOULD_BREAK_HARD ---
        AlternativeGroupView groupCAlternative = response.alternatives().stream()
                .filter(a -> a.groupId().equals(groupC))
                .findFirst()
                .orElseThrow(() -> new AssertionError("No alternative for Grupp C in " + response.alternatives()));
        assertThat(groupCAlternative.origin()).contains("FRIEND_WISH");
        assertThat(groupCAlternative.verdict()).isEqualTo("WOULD_BREAK_HARD");
        assertThat(groupCAlternative.narrativeSv()).contains("Grupp C").contains("full").contains("5/5");

        // --- newlyBroken containing the groupMaxSizeHard message ---
        assertThat(groupCAlternative.newlyBroken())
                .anyMatch(m -> m.key().equals("groupMaxSizeHard") && m.messageSv().contains("Grupp C") && m.messageSv().contains("max"));

        // --- appliedWeights present ---
        assertThat(response.appliedWeights()).isNotEmpty();
        assertThat(response.appliedWeights()).anyMatch(w -> w.key().equals("sameGroupSoft"));

        System.out.println("CANONICAL QUESTION - Kalle's explanation JSON (trimmed): " + response);
    }

    @Test
    void waitlistedFriendEdgeLisaUnassignedProducesOplaceradNarrativeWithLink() {
        ExplanationTestFixture fx = newFixture();
        List<String> blocks = fx.addTimeSlotWithBlocks("Torsdag 18.00-19.30", 2);
        String groupY = fx.addGroup("Grupp Y", 1, 1, 5, 5, blocks.get(0));

        String kalle = fx.addParticipant("Kalle", "Karlsson", 640.0, 3);
        fx.place(kalle, groupY);

        String lisa = fx.addParticipant("Lisa", "Larsson", 650.0, 3);
        fx.place(lisa, null); // Lisa herself is unassigned/waitlisted

        fx.wish(kalle, lisa, "playWith");

        String runId = fx.insertFinishedRun();

        PersonExplanationResponse response = explanationService.explainPerson(fx.planId, runId, kalle);

        assertThat(response.brokenWishes())
                .anyMatch(w -> w.messageSv().contains("Lisa") && w.messageSv().contains("oplacerad")
                        && w.unassignedFriendParticipantProfileId() != null && w.unassignedFriendParticipantProfileId().equals(lisa));

        // The link target must itself be explainable (Lisa's own waitlist explanation).
        PersonExplanationResponse lisasOwnExplanation = explanationService.explainPerson(fx.planId, runId, lisa);
        assertThat(lisasOwnExplanation.selectedGroup()).isNull();
        assertThat(lisasOwnExplanation.waitlist()).isNotNull();
    }
}
