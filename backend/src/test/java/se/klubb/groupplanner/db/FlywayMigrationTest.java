package se.klubb.groupplanner.db;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * Verifies the Flyway migrations (V1__core.sql, V2__seed_constraints_and_standard_fields.sql) run
 * cleanly on a fresh temp-dir SQLite database, that every table from docs/design/02-product-data-ui
 * .md §1 exists, and that the seeded rows match spec §10 (24 constraints) and §9.2 (19 standard
 * fields) — docs/plan.md M1 row ("Flyway migration test on a temp-dir SQLite... seeds counted").
 */
@SpringBootTest
class FlywayMigrationTest {

    @TempDir
    static Path dataDir;

    @DynamicPropertySource
    static void appDataDir(DynamicPropertyRegistry registry) {
        registry.add("app.data-dir", () -> dataDir.toString());
    }

    @Autowired
    private JdbcClient jdbcClient;

    private static final List<String> EXPECTED_TABLES = List.of(
            "person", "season_plan", "activity_plan", "participant_profile", "coach_profile",
            "coach_time_slot", "venue", "court", "time_slot", "training_block", "training_group",
            "player_assignment", "coach_assignment", "constraint_definition", "field_definition",
            "custom_field_value", "constraint_weight_config");

    @Test
    void allExpectedTablesExist() {
        List<String> tableNames = jdbcClient.sql("SELECT name FROM sqlite_master WHERE type = 'table'")
                .query((rs, rowNum) -> rs.getString("name"))
                .list();

        assertThat(tableNames).containsAll(EXPECTED_TABLES);
    }

    @Test
    void flywaySchemaHistoryShowsBothMigrationsApplied() {
        List<String> descriptions = jdbcClient.sql(
                        "SELECT description FROM flyway_schema_history WHERE success = 1 ORDER BY installed_rank")
                .query((rs, rowNum) -> rs.getString("description"))
                .list();

        assertThat(descriptions).containsExactly("core", "seed constraints and standard fields");
    }

    @Test
    void exactlyTwentyFourConstraintsAreSeeded() {
        Integer count = jdbcClient.sql("SELECT COUNT(*) FROM constraint_definition").query(Integer.class).single();

        assertThat(count).isEqualTo(24);
    }

    /**
     * The exact seed contract for all 24 standard constraints (spec §10.1-10.24): key AND
     * hard-or-soft classification. A future edit that flips a classification (e.g.
     * groupMaxSizeHard -> SOFT) fails here, not silently in the solver (M6).
     */
    private static final Map<String, String> EXPECTED_CONSTRAINT_CLASSIFICATIONS = Map.ofEntries(
            Map.entry("trainingBlockCapacity", "HARD"),              // §10.1
            Map.entry("groupRequiresTrainingBlock", "HARD"),         // §10.2
            Map.entry("groupMaxSizeHard", "HARD"),                   // §10.3
            Map.entry("groupSizeTarget", "SOFT"),                    // §10.4
            Map.entry("groupMinSizeSoft", "SOFT"),                   // §10.5
            Map.entry("levelBalance", "SOFT"),                       // §10.6
            Map.entry("groupOrderByLevel", "SOFT"),                  // §10.7
            Map.entry("previousGroupContinuity", "SOFT"),            // §10.8
            Map.entry("timeAvailabilityHard", "HARD"),               // §10.9
            Map.entry("timePreferenceSoft", "SOFT"),                 // §10.10
            Map.entry("sameGroupHard", "HARD"),                      // §10.11
            Map.entry("sameGroupSoft", "SOFT"),                      // §10.12
            Map.entry("differentGroupHard", "HARD"),                 // §10.13
            Map.entry("differentGroupSoft", "SOFT"),                 // §10.14
            Map.entry("coachNoOverlap", "HARD"),                     // §10.15
            Map.entry("playerNoOverlap", "HARD"),                    // §10.16
            Map.entry("coachCannotTrainAndCoachSameTime", "HARD"),   // §10.17
            Map.entry("coachAvailabilityHard", "HARD"),              // §10.18
            Map.entry("coachRequirementHard", "HARD"),               // §10.19
            Map.entry("coachLevelFit", "SOFT"),                      // §10.20
            Map.entry("coachPreferenceSoft", "SOFT"),                // §10.21
            Map.entry("lateTimeForLowerGroups", "SOFT"),             // §10.22
            Map.entry("lockedAssignmentHard", "HARD"),               // §10.23
            Map.entry("savedPlanResourceBlock", "HARD"));            // §10.24

    @Test
    void constraintKeyToHardOrSoftMapMatchesSpecSection10Exactly() {
        Map<String, String> actual = jdbcClient.sql("SELECT key, hard_or_soft FROM constraint_definition")
                .query((rs, rowNum) -> Map.entry(rs.getString("key"), rs.getString("hard_or_soft")))
                .list()
                .stream()
                .collect(java.util.stream.Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue));

        assertThat(actual).containsExactlyInAnyOrderEntriesOf(EXPECTED_CONSTRAINT_CLASSIFICATIONS);
    }

    @Test
    void exactlyNineteenStandardFieldsAreSeeded() {
        Integer count = jdbcClient.sql("SELECT COUNT(*) FROM field_definition WHERE is_standard = 1 AND activity_plan_id IS NULL")
                .query(Integer.class)
                .single();

        assertThat(count).isEqualTo(19);
    }

    @Test
    void standardFieldKeysMatchSpecSection9Point2() {
        List<String> keys = jdbcClient.sql("SELECT key FROM field_definition WHERE activity_plan_id IS NULL ORDER BY sort_order")
                .query((rs, rowNum) -> rs.getString("key"))
                .list();

        assertThat(keys).containsExactly(
                "rankingPoints", "previousGroupName", "previousGroupLevel", "manualLevelScore", "levelConfidence",
                "canTimes", "cannotTimes", "preferTimes", "playWith", "mustPlayWith", "avoidPlayWith",
                "wantsCoach", "mustHaveCoach", "cannotHaveCoach", "newToClub", "needsManualReview",
                "priority", "importedComment", "internalNote");
    }

    @Test
    void sensitiveCommentFieldsAreMarkedInformationOnly() {
        List<String> hardOrSoft = jdbcClient.sql(
                        "SELECT hard_or_soft FROM field_definition WHERE key IN ('importedComment', 'internalNote')")
                .query((rs, rowNum) -> rs.getString("hard_or_soft"))
                .list();

        assertThat(hardOrSoft).containsOnly("INFO");
    }

    /**
     * Every COLUMN-storage field must name a column that actually exists on participant_profile —
     * so the field->column mapping (used generically from M4 on) can never drift from the schema.
     */
    @Test
    void everyColumnStorageFieldMapsToARealParticipantProfileColumn() {
        List<String> participantProfileColumns = jdbcClient.sql("SELECT name FROM pragma_table_info('participant_profile')")
                .query((rs, rowNum) -> rs.getString("name"))
                .list();
        assertThat(participantProfileColumns).isNotEmpty();

        List<Map.Entry<String, String>> columnFields = jdbcClient.sql(
                        "SELECT key, column_name FROM field_definition WHERE storage_kind = 'COLUMN'")
                .query((rs, rowNum) -> Map.entry(rs.getString("key"), rs.getString("column_name")))
                .list();
        assertThat(columnFields).isNotEmpty();

        for (Map.Entry<String, String> field : columnFields) {
            assertThat(participantProfileColumns)
                    .as("field '%s' claims participant_profile column '%s'", field.getKey(), field.getValue())
                    .contains(field.getValue());
        }
    }

    @Test
    void columnNameIsPopulatedExactlyForColumnStorageFields() {
        // The CHECK constraint in V2 enforces this shape for future writes; this asserts the seed
        // data itself honours it, and documents the exact expected mapping.
        Map<String, String> expected = Map.of(
                "rankingPoints", "ranking_points",
                "previousGroupName", "previous_group_name",
                "previousGroupLevel", "previous_group_level",
                "manualLevelScore", "manual_level_score",
                "levelConfidence", "level_confidence",
                "needsManualReview", "manual_review_flag",
                "importedComment", "imported_comment",
                "internalNote", "internal_note");

        Map<String, String> actual = jdbcClient.sql(
                        "SELECT key, column_name FROM field_definition WHERE storage_kind = 'COLUMN'")
                .query((rs, rowNum) -> Map.entry(rs.getString("key"), rs.getString("column_name")))
                .list()
                .stream()
                .collect(java.util.stream.Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue));

        assertThat(actual).containsExactlyInAnyOrderEntriesOf(expected);

        Integer customWithColumnName = jdbcClient.sql(
                        "SELECT COUNT(*) FROM field_definition WHERE storage_kind = 'CUSTOM' AND column_name IS NOT NULL")
                .query(Integer.class)
                .single();
        assertThat(customWithColumnName).isZero();
    }
}
