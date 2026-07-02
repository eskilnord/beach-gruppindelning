package se.klubb.groupplanner.level;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Path;
import java.time.Instant;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import se.klubb.groupplanner.domain.ActivityPlan;
import se.klubb.groupplanner.domain.ParticipantProfile;
import se.klubb.groupplanner.domain.Person;
import se.klubb.groupplanner.domain.SeasonPlan;
import se.klubb.groupplanner.repo.ActivityPlanRepository;
import se.klubb.groupplanner.repo.ParticipantProfileRepository;
import se.klubb.groupplanner.repo.PersonRepository;
import se.klubb.groupplanner.repo.SeasonPlanRepository;
import se.klubb.groupplanner.util.Uuid7;

/**
 * DB-level tests for {@link LevelService#recomputeForPlan} (M4 review test-gap finding): a
 * recompute must not only SET {@code estimated_level} when a source exists — it must also NULL a
 * previously-persisted value when every source has since been removed (and set confidence to 0 and
 * force the manual-review flag), asserted at the raw column level via {@link JdbcClient}.
 */
@SpringBootTest
class LevelServiceIntegrationTest {

    @TempDir
    static Path dataDir;

    @DynamicPropertySource
    static void appDataDir(DynamicPropertyRegistry registry) {
        registry.add("app.data-dir", () -> dataDir.toString());
    }

    @Autowired
    private LevelService levelService;
    @Autowired
    private SeasonPlanRepository seasonPlanRepository;
    @Autowired
    private ActivityPlanRepository activityPlanRepository;
    @Autowired
    private PersonRepository personRepository;
    @Autowired
    private ParticipantProfileRepository participantProfileRepository;
    @Autowired
    private JdbcClient jdbcClient;

    @Test
    void recomputeNullsAPreviouslyPersistedLevelWhenAllSourcesAreRemoved() {
        Instant now = Instant.now();
        SeasonPlan season = seasonPlanRepository.insert(
                new SeasonPlan(Uuid7.generate(), "VT26", null, null, "active", now, now));
        ActivityPlan plan = activityPlanRepository.insert(
                new ActivityPlan(Uuid7.generate(), season.id(), "Herr", "beach", "draft", null, null, null, null, now, now));
        Person person = personRepository.insert(new Person(
                Uuid7.generate(), "Kalle", "Karlsson", null, null, null, null, true, false, null, now, now));
        ParticipantProfile profile = participantProfileRepository.insert(new ParticipantProfile(
                Uuid7.generate(), person.id(), plan.id(), 720.0, "seriespel",
                null, null, null, null, null, null, null, false, false));

        // First recompute: ranking source present -> level persisted.
        levelService.recomputeForPlan(plan.id());
        Map<String, Object> afterFirst = levelColumns(profile.id());
        assertThat(afterFirst.get("estimated_level")).isEqualTo(720.0);
        assertThat(afterFirst.get("level_confidence")).isEqualTo(LevelService.CONFIDENCE_MEDIUM);
        assertThat(afterFirst.get("manual_review_flag")).isEqualTo(0);

        // Remove the only source (e.g. the council clears a wrongly-imported ranking via PATCH).
        ParticipantProfile current = participantProfileRepository.findById(profile.id()).orElseThrow();
        participantProfileRepository.update(new ParticipantProfile(
                current.id(), current.personId(), current.activityPlanId(),
                null, null, // rankingPoints + rankingSource cleared
                current.previousGroupName(), current.previousGroupLevel(),
                current.estimatedLevel(), current.levelConfidence(), current.manualLevelScore(),
                current.importedComment(), current.internalNote(),
                current.manualReviewFlag(), current.waitlisted()));

        // Second recompute: no source at all -> the stale persisted level must actually become
        // NULL in the database (not survive as a ghost of the removed ranking), confidence 0.0,
        // and the manual-review flag must be forced on.
        levelService.recomputeForPlan(plan.id());
        Map<String, Object> afterSecond = levelColumns(profile.id());
        assertThat(afterSecond.get("estimated_level")).isNull();
        assertThat(afterSecond.get("level_confidence")).isEqualTo(LevelService.CONFIDENCE_NONE);
        assertThat(afterSecond.get("manual_review_flag")).isEqualTo(1);
    }

    /** Reads the three computed columns raw (no domain-record mapping in between). */
    private Map<String, Object> levelColumns(String participantId) {
        return jdbcClient.sql(
                        "SELECT estimated_level, level_confidence, manual_review_flag "
                                + "FROM participant_profile WHERE id = :id")
                .param("id", participantId)
                .query((rs, rowNum) -> {
                    Map<String, Object> row = new java.util.HashMap<>();
                    double level = rs.getDouble("estimated_level");
                    row.put("estimated_level", rs.wasNull() ? null : level);
                    double confidence = rs.getDouble("level_confidence");
                    row.put("level_confidence", rs.wasNull() ? null : confidence);
                    row.put("manual_review_flag", rs.getInt("manual_review_flag"));
                    return row;
                })
                .single();
    }
}
