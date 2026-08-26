package se.klubb.groupplanner.repo;

import java.time.Instant;
import java.util.Optional;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * {@code app_setting} key/value access via {@link JdbcClient} (ADR-004, V12) — a tiny generic
 * table for app-wide settings. B3 (v0.6.0) only uses the {@code "ui.mode"} key (see {@code
 * se.klubb.groupplanner.api.AppSettingsController}); the key/value shape leaves room for future
 * settings without another migration.
 */
@Repository
public class AppSettingsRepository {

    private final JdbcClient jdbcClient;

    public AppSettingsRepository(JdbcClient jdbcClient) {
        this.jdbcClient = jdbcClient;
    }

    public Optional<String> findValue(String key) {
        return jdbcClient.sql("SELECT value FROM app_setting WHERE key = :key")
                .param("key", key)
                .query(String.class)
                .optional();
    }

    /** Inserts the key/value pair, or replaces the existing row's value + updated_at (upsert). */
    public void upsert(String key, String value) {
        jdbcClient.sql("""
                        INSERT INTO app_setting (key, value, updated_at)
                        VALUES (:key, :value, :updatedAt)
                        ON CONFLICT (key) DO UPDATE SET value = excluded.value, updated_at = excluded.updated_at
                        """)
                .param("key", key)
                .param("value", value)
                .param("updatedAt", Instant.now().toString())
                .update();
    }
}
