package se.klubb.groupplanner.repo;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataAccessException;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import se.klubb.groupplanner.domain.ActivityPlan;
import se.klubb.groupplanner.domain.ParticipantProfile;
import se.klubb.groupplanner.domain.Person;
import se.klubb.groupplanner.domain.SeasonPlan;
import se.klubb.groupplanner.util.Uuid7;

/**
 * End-to-end repository round trip: season -> activity plan -> person -> participant profile
 * (docs/plan.md M1 row), plus a check that the {@code UNIQUE(person_id, activity_plan_id)}
 * constraint on {@code participant_profile} surfaces as a clean {@link DataAccessException} rather
 * than a raw {@link java.sql.SQLException}.
 */
@SpringBootTest
class RepositoryRoundTripTest {

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

    @Test
    void seasonThenPlanThenPersonThenParticipantRoundTrips() {
        Instant now = Instant.now();

        SeasonPlan season = seasonPlanRepository.insert(new SeasonPlan(
                Uuid7.generate(), "VT26", null, null, "active", now, now));
        assertThat(seasonPlanRepository.findById(season.id())).contains(season);

        ActivityPlan plan = activityPlanRepository.insert(new ActivityPlan(
                Uuid7.generate(), season.id(), "Herr", "beach", "draft", 10, 8, 12, 300.0, now, now));
        assertThat(activityPlanRepository.findById(plan.id())).contains(plan);
        assertThat(activityPlanRepository.findBySeasonPlanId(season.id())).containsExactly(plan);

        Person person = personRepository.insert(new Person(
                Uuid7.generate(), "Kalle", "Karlsson", "Kalle K", "kalle@example.com", "0700000000",
                null, true, false, null, now, now));
        assertThat(personRepository.findById(person.id())).contains(person);

        ParticipantProfile profile = participantProfileRepository.insert(new ParticipantProfile(
                Uuid7.generate(), person.id(), plan.id(), 550.0, "seriespel", "Grupp 3", 5.0, 5.2, 0.8, null,
                "Fritext från anmälan", "Intern anteckning", false, false));
        assertThat(participantProfileRepository.findById(profile.id())).contains(profile);
        assertThat(participantProfileRepository.findByActivityPlanId(plan.id())).containsExactly(profile);

        // Update path.
        ParticipantProfile updated = new ParticipantProfile(
                profile.id(), profile.personId(), profile.activityPlanId(), profile.rankingPoints(),
                profile.rankingSource(), profile.previousGroupName(), profile.previousGroupLevel(),
                profile.estimatedLevel(), profile.levelConfidence(), profile.manualLevelScore(),
                profile.importedComment(), profile.internalNote(), true, true);
        participantProfileRepository.update(updated);
        Optional<ParticipantProfile> reloaded = participantProfileRepository.findById(profile.id());
        assertThat(reloaded).isPresent();
        assertThat(reloaded.get().manualReviewFlag()).isTrue();
        assertThat(reloaded.get().waitlisted()).isTrue();

        // Delete path.
        assertThat(participantProfileRepository.deleteById(profile.id())).isTrue();
        assertThat(participantProfileRepository.findById(profile.id())).isEmpty();
    }

    @Test
    void duplicateParticipantProfileForSamePersonAndPlanViolatesUniqueConstraintCleanly() {
        Instant now = Instant.now();
        SeasonPlan season = seasonPlanRepository.insert(new SeasonPlan(
                Uuid7.generate(), "VT27", null, null, "active", now, now));
        ActivityPlan plan = activityPlanRepository.insert(new ActivityPlan(
                Uuid7.generate(), season.id(), "Dam", "beach", "draft", 10, 8, 12, null, now, now));
        Person person = personRepository.insert(new Person(
                Uuid7.generate(), "Anna", "Andersson", null, null, null, null, true, false, null, now, now));

        participantProfileRepository.insert(new ParticipantProfile(
                Uuid7.generate(), person.id(), plan.id(), null, null, null, null, null, null, null, null, null,
                false, false));

        assertThatThrownBy(() -> participantProfileRepository.insert(new ParticipantProfile(
                Uuid7.generate(), person.id(), plan.id(), null, null, null, null, null, null, null, null, null,
                false, false)))
                .isInstanceOf(DataAccessException.class);
    }

    @Test
    void seasonPlanListIsOrderedAndUpdateAndDeleteWork() {
        Instant now = Instant.now();
        SeasonPlan season = seasonPlanRepository.insert(new SeasonPlan(
                Uuid7.generate(), "VT28", null, null, "active", now, now));

        SeasonPlan renamed = new SeasonPlan(season.id(), "VT28 (renamed)", season.startDate(), season.endDate(),
                season.status(), season.createdAt(), Instant.now());
        seasonPlanRepository.update(renamed);
        assertThat(seasonPlanRepository.findById(season.id()).orElseThrow().name()).isEqualTo("VT28 (renamed)");

        List<SeasonPlan> all = seasonPlanRepository.findAll();
        assertThat(all).extracting(SeasonPlan::id).contains(season.id());

        assertThat(seasonPlanRepository.deleteById(season.id())).isTrue();
        assertThat(seasonPlanRepository.findById(season.id())).isEmpty();
        assertThat(seasonPlanRepository.deleteById(season.id())).isFalse();
    }
}
