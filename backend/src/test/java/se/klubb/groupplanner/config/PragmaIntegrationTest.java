package se.klubb.groupplanner.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * Verifies the SQLite pragmas configured in {@link DataSourceConfig} actually take effect on real
 * connections obtained through the Spring-wired {@code DataSource}/{@code JdbcClient} (CLAUDE.md
 * "SQLite rules": WAL, {@code foreign_keys=ON}, {@code busy_timeout=5000}; docs/plan.md M1 row).
 */
@SpringBootTest
class PragmaIntegrationTest {

    @TempDir
    static Path dataDir;

    @DynamicPropertySource
    static void appDataDir(DynamicPropertyRegistry registry) {
        registry.add("app.data-dir", () -> dataDir.toString());
    }

    @Autowired
    private JdbcClient jdbcClient;

    @Test
    void journalModeIsWal() {
        String journalMode = jdbcClient.sql("PRAGMA journal_mode").query(String.class).single();

        assertThat(journalMode).isEqualToIgnoringCase("wal");
    }

    @Test
    void foreignKeysAreEnforced() {
        Integer foreignKeys = jdbcClient.sql("PRAGMA foreign_keys").query(Integer.class).single();

        assertThat(foreignKeys).isEqualTo(1);
    }

    @Test
    void busyTimeoutIsFiveSeconds() {
        Integer busyTimeoutMs = jdbcClient.sql("PRAGMA busy_timeout").query(Integer.class).single();

        assertThat(busyTimeoutMs).isEqualTo(DataSourceConfig.BUSY_TIMEOUT_MS);
    }

    @Test
    void foreignKeyViolationIsActuallyRejected() {
        // Belt-and-braces: foreign_keys=1 alone does not prove enforcement is active for this
        // connection/statement - insert a row with a dangling FK and confirm SQLite rejects it.
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> jdbcClient.sql(
                        "INSERT INTO activity_plan (id, season_plan_id, name, status, created_at, updated_at) "
                                + "VALUES ('does-not-matter', 'no-such-season', 'x', 'draft', '2026-01-01T00:00:00Z', '2026-01-01T00:00:00Z')")
                .update())
                .isInstanceOf(Exception.class);
    }
}
