package se.klubb.groupplanner.fields;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Path;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * v0.6.0 milestone B6: proves the Flyway-migrated {@code constraint_definition.default_weight}
 * seeds (V13__priority_order_default_weights.sql) actually match {@link PriorityOrder} — the two
 * are hand-authored independently (SQL literals vs. Java ladder constants) and nothing in the build
 * enforces they agree except this test. Boots a fresh temp-dir SQLite database exactly like {@code
 * db.FlywayMigrationTest}.
 */
@SpringBootTest
class PriorityLadderSeedConsistencyTest {

    @TempDir
    static Path dataDir;

    @DynamicPropertySource
    static void appDataDir(DynamicPropertyRegistry registry) {
        registry.add("app.data-dir", () -> dataDir.toString());
    }

    @Autowired
    private JdbcClient jdbcClient;

    /** The 6 constraint keys {@link PriorityOrder#weightsFor} computes from the 4-priority ranking —
     * must match {@code constraint_definition.default_weight} exactly at {@link
     * PriorityOrder#defaultOrder()}. */
    @Test
    void seededDefaultWeightsMatchPriorityOrderAtDefaultOrder() {
        Map<String, Integer> expected = PriorityOrder.weightsFor(PriorityOrder.defaultOrder());
        assertThat(expected).hasSize(6);

        for (Map.Entry<String, Integer> entry : expected.entrySet()) {
            Integer seeded = jdbcClient.sql("SELECT default_weight FROM constraint_definition WHERE key = :key")
                    .param("key", entry.getKey())
                    .query(Integer.class)
                    .single();
            assertThat(seeded)
                    .as("constraint_definition.default_weight for '%s' must match PriorityOrder.weightsFor(defaultOrder())",
                            entry.getKey())
                    .isEqualTo(entry.getValue());
        }
    }

    /** The 7 SOFT constraint keys V13 retempers that are NOT part of the 4-priority ladder — pinned
     * directly against the migration's own literal values (backend/docs/priority-order-notes.md). */
    @Test
    void seededDefaultWeightsForNonBucketConstraintsMatchTheMigration() {
        Map<String, Integer> expected = Map.of(
                "groupSizeTarget", 800,
                "groupMinSizeSoft", 2000,
                "coachLevelFit", 42,
                "coachPreferenceSoft", 600,
                "coachPreferredTimeSlot", 250,
                "coachUnknownTimeSlot", 250,
                "lateTimeForLowerGroups", 300);
        assertThat(expected).hasSize(7);

        for (Map.Entry<String, Integer> entry : expected.entrySet()) {
            Integer seeded = jdbcClient.sql("SELECT default_weight FROM constraint_definition WHERE key = :key")
                    .param("key", entry.getKey())
                    .query(Integer.class)
                    .single();
            assertThat(seeded)
                    .as("constraint_definition.default_weight for '%s' must match V13's literal value", entry.getKey())
                    .isEqualTo(entry.getValue());
        }
    }
}
