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
import se.klubb.groupplanner.explain.ExplanationDtos.PersonExplanationResponse;
import se.klubb.groupplanner.explain.ExplanationDtos.WhatIfWhyNotResponse;
import se.klubb.groupplanner.repo.ActivityPlanRepository;
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
 * M7 Opus-review fixes, service-level coverage:
 * <ul>
 *   <li><b>M1</b> — level-band factor truthfulness: "matchar ... nivåspann" positive ONLY when the
 *       player's level is inside the band; outside → honest "ligger över/under" NEGATIVE factor.
 *   <li><b>m4</b> — an exactly-zero what-if delta gets verdict {@code NEUTRAL} ("påverkar inte
 *       totalpoängen"), never {@code WORSE}.
 *   <li><b>m7</b> — the waitlist "förbättring möjlig" quality warning aggregates ALL feasible group
 *       names into one message (was last-wins).
 * </ul>
 */
@SpringBootTest
class ReviewFixesTest {

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
    private ExplanationService explanationService;
    @Autowired
    private WhatIfService whatIfService;

    private ExplanationTestFixture newFixture() {
        return new ExplanationTestFixture(
                seasonPlanRepository, activityPlanRepository, personRepository, participantProfileRepository,
                playerAssignmentRepository, trainingGroupRepository, timeSlotRepository, trainingBlockGenerationService,
                fieldDefinitionRepository, customFieldValueRepository, optimizationRunRepository);
    }

    // ─────────────────────────────────────────────────────────────────── M1

    @Test
    void inBandPlayerGetsMatcharPositiveFactor() {
        ExplanationTestFixture fx = newFixture();
        List<String> blocks = fx.addTimeSlotWithBlocks("Torsdag 18.00-19.30", 1);
        String groupY = fx.addGroup("Grupp Y", 1, 1, 5, 5, blocks.get(0), 600.0, 690.0);

        String kalle = fx.addParticipant("Kalle", "Karlsson", 640.0, 3); // inside 600-690
        fx.place(kalle, groupY);
        String runId = fx.insertFinishedRun();

        PersonExplanationResponse response = explanationService.explainPerson(fx.planId, runId, kalle);

        assertThat(response.positiveFactors())
                .anyMatch(f -> f.messageSv().contains("matchar") && f.messageSv().contains("nivåspann 600,0–690,0"));
        assertThat(response.negativeFactors()).noneMatch(f -> f.messageSv().contains("nivåspann"));
    }

    @Test
    void outOfBandPlayerNeverGetsMatcharPositiveButAnHonestNegative() {
        ExplanationTestFixture fx = newFixture();
        List<String> blocks = fx.addTimeSlotWithBlocks("Torsdag 18.00-19.30", 1);
        // The exact false claim the review caught in the E2E transcript: 971 vs band 850-913.
        String group = fx.addGroup("Torsdag Herr 2", 1, 1, 12, 12, blocks.get(0), 850.0, 913.0);

        String tuva = fx.addParticipant("Tuva", "Nilsson", 971.0, 3); // ABOVE the band
        fx.place(tuva, group);
        String runId = fx.insertFinishedRun();

        PersonExplanationResponse response = explanationService.explainPerson(fx.planId, runId, tuva);

        assertThat(response.positiveFactors()).noneMatch(f -> f.messageSv().contains("matchar"));
        assertThat(response.negativeFactors())
                .anyMatch(f -> f.messageSv().contains("ligger över") && f.messageSv().contains("nivåspann 850,0–913,0"));
    }

    @Test
    void belowBandPlayerGetsLiggerUnderNegative() {
        ExplanationTestFixture fx = newFixture();
        List<String> blocks = fx.addTimeSlotWithBlocks("Torsdag 18.00-19.30", 1);
        String group = fx.addGroup("Grupp Y", 1, 1, 5, 5, blocks.get(0), 600.0, 690.0);

        String pelle = fx.addParticipant("Pelle", "Persson", 400.0, 3); // BELOW the band
        fx.place(pelle, group);
        String runId = fx.insertFinishedRun();

        PersonExplanationResponse response = explanationService.explainPerson(fx.planId, runId, pelle);

        assertThat(response.positiveFactors()).noneMatch(f -> f.messageSv().contains("matchar"));
        assertThat(response.negativeFactors()).anyMatch(f -> f.messageSv().contains("ligger under"));
    }

    // ─────────────────────────────────────────────────────────────────── m4

    @Test
    void exactlyZeroDeltaGetsNeutralVerdictNotWorse() {
        ExplanationTestFixture fx = newFixture();
        List<String> blocks = fx.addTimeSlotWithBlocks("Torsdag 18.00-19.30", 2);
        // Constructed so the ONLY firing soft constraint is groupSizeTarget, and the move shifts one
        // deviation point from A to B exactly symmetrically: A 4->3 (dev 1->0), B 3->4 (dev 0->1),
        // net soft delta 0. All levels identical -> levelBalance 0 both sides; equal means -> no
        // groupOrderByLevel matches; minSize 0 -> no under-min penalty; no wishes/preferences.
        String groupA = fx.addGroup("Grupp A", 1, 0, 3, 12, blocks.get(0));
        String groupB = fx.addGroup("Grupp B", 2, 0, 3, 12, blocks.get(1));

        String mover = fx.addParticipant("Mover", "M", 500.0, 3);
        fx.place(mover, groupA);
        for (int i = 0; i < 3; i++) {
            fx.place(fx.addParticipant("A" + i, "A", 500.0, 3), groupA);
        }
        for (int i = 0; i < 3; i++) {
            fx.place(fx.addParticipant("B" + i, "B", 500.0, 3), groupB);
        }
        String runId = fx.insertFinishedRun();

        WhatIfWhyNotResponse response = whatIfService.whyNot(fx.planId, runId, mover, groupB);

        assertThat(response.alternative().scoreDelta().hard()).isZero();
        assertThat(response.alternative().scoreDelta().medium()).isZero();
        assertThat(response.alternative().scoreDelta().soft()).isZero();
        assertThat(response.alternative().verdict()).isEqualTo("NEUTRAL");
        assertThat(response.alternative().narrativeSv()).contains("påverkar inte totalpoängen");
    }

    // ─────────────────────────────────────────────────────────────────── m7

    @Test
    void qualityWarningAggregatesAllFeasibleGroupNames() {
        ExplanationTestFixture fx = newFixture();
        List<String> blocks = fx.addTimeSlotWithBlocks("Torsdag 18.00-19.30", 2);
        fx.addGroup("Grupp A", 1, 0, 5, 5, blocks.get(0));
        fx.addGroup("Grupp B", 2, 0, 5, 5, blocks.get(1));
        fx.place(fx.addParticipant("M1", "M", 500.0, 3), null); // waitlisted with BOTH groups wide open

        String erik = fx.addParticipant("Erik", "Eriksson", 500.0, 3);
        fx.place(erik, null);
        String runId = fx.insertFinishedRun();

        PersonExplanationResponse response = explanationService.explainPerson(fx.planId, runId, erik);

        assertThat(response.waitlist()).isNotNull();
        String warning = response.waitlist().qualityWarningSv();
        assertThat(warning).isNotNull();
        assertThat(warning).contains("Grupp A").contains("Grupp B"); // was last-wins: only "Grupp B"
    }
}
