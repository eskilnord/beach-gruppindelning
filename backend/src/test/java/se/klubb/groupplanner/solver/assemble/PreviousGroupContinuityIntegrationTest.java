package se.klubb.groupplanner.solver.assemble;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Path;
import java.time.Instant;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import se.klubb.groupplanner.domain.ActivityPlan;
import se.klubb.groupplanner.domain.ParticipantProfile;
import se.klubb.groupplanner.domain.Person;
import se.klubb.groupplanner.domain.SeasonPlan;
import se.klubb.groupplanner.repo.ActivityPlanRepository;
import se.klubb.groupplanner.repo.ParticipantProfileRepository;
import se.klubb.groupplanner.repo.PersonRepository;
import se.klubb.groupplanner.repo.PlayerAssignmentRepository;
import se.klubb.groupplanner.repo.SeasonPlanRepository;
import se.klubb.groupplanner.solver.domain.GroupPlanSolution;
import se.klubb.groupplanner.solver.domain.PlayerAssignment;
import se.klubb.groupplanner.util.Uuid7;

/**
 * Regression test for the B5 continuity bug fix: {@link SolverInputAssembler#previousGroupOrderOf}
 * used to apply a bare trailing-integer regex to the WHOLE stored {@code previous_group_name}
 * string, so any value carrying a trailing term parenthetical (e.g. {@code "Torsdag Herr 3
 * (Vårtermin 2025)"} - the shape actually produced by the app's own council-grouped export/import
 * round trip) silently assembled a {@code null} previousGroupOrder, making the continuity
 * constraint permanently inert for such participants. The fix delegates to {@link
 * se.klubb.groupplanner.groups.PreviousGroupNormalizer#parse(String)}, which strips the trailing
 * term suffix before extracting the ordinal.
 */
@SpringBootTest
class PreviousGroupContinuityIntegrationTest {

    @TempDir
    static Path dataDir;

    @DynamicPropertySource
    static void appDataDir(DynamicPropertyRegistry registry) {
        registry.add("app.data-dir", () -> dataDir.toString());
    }

    @Autowired
    private SolverInputAssembler assembler;
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

    private String createPlan() {
        Instant now = Instant.now();
        SeasonPlan season = seasonPlanRepository.insert(new SeasonPlan(Uuid7.generate(), "VT26", null, null, "active", now, now));
        ActivityPlan plan = activityPlanRepository.insert(
                new ActivityPlan(Uuid7.generate(), season.id(), "Herr", "beach", "draft", null, null, null, null, now, now));
        return plan.id();
    }

    private String createParticipant(String planId, String firstName, String previousGroupName) {
        Instant now = Instant.now();
        Person person = personRepository.insert(new Person(
                Uuid7.generate(), firstName, "Testsson", null, null, null, null, true, false, null, now, now));
        ParticipantProfile profile = participantProfileRepository.insert(new ParticipantProfile(
                Uuid7.generate(), person.id(), planId, null, null, previousGroupName, null, null, null, null, null, null,
                false, false, false));
        playerAssignmentRepository.insertImportedIfAbsent(profile.id());
        return profile.id();
    }

    @Test
    void trailingTermParentheticalDoesNotSuppressGroupOrder() {
        String planId = createPlan();
        createParticipant(planId, "Ada", "Torsdag Herr 3 (Vårtermin 2025)");

        GroupPlanSolution solution = assembler.assemble(planId).solution();
        PlayerAssignment pa = solution.getPlayerAssignments().get(0);

        assertThat(pa.getPreviousGroupOrder()).isEqualTo(3);
    }

    @Test
    void unparsableFreeTextYieldsNullGroupOrder() {
        String planId = createPlan();
        createParticipant(planId, "Bo", "Nybörjargrupp");

        GroupPlanSolution solution = assembler.assemble(planId).solution();
        PlayerAssignment pa = solution.getPlayerAssignments().get(0);

        assertThat(pa.getPreviousGroupOrder()).isNull();
    }

    /** MINOR 14 (B5 review): the legacy plain shape (no term suffix at all) - the bare trailing-digit
     *  regex this bug fix's PreviousGroupNormalizer#parse delegation still handles directly, without
     *  needing to strip anything first. */
    @Test
    void legacyPlainValueWithoutATermSuffixStillYieldsGroupOrder() {
        String planId = createPlan();
        createParticipant(planId, "Cissi", "Herr 3");

        GroupPlanSolution solution = assembler.assemble(planId).solution();
        PlayerAssignment pa = solution.getPlayerAssignments().get(0);

        assertThat(pa.getPreviousGroupOrder()).isEqualTo(3);
    }

    /** MINOR 14 (B5 review): a {@code null} previousGroupName (never imported/set at all) must not
     *  throw and must simply yield a {@code null} previousGroupOrder, same as unparsable free text. */
    @Test
    void nullPreviousGroupNameYieldsNullGroupOrder() {
        String planId = createPlan();
        createParticipant(planId, "Doris", null);

        GroupPlanSolution solution = assembler.assemble(planId).solution();
        PlayerAssignment pa = solution.getPlayerAssignments().get(0);

        assertThat(pa.getPreviousGroupOrder()).isNull();
    }
}
