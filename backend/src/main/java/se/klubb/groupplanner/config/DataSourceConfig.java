package se.klubb.groupplanner.config;

import javax.sql.DataSource;
import org.sqlite.SQLiteConfig;
import org.sqlite.SQLiteDataSource;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Wires the single SQLite {@link DataSource} used by the whole application (Flyway, {@code
 * JdbcClient}, everything) per ADR-004 and the SQLite rules in {@code CLAUDE.md}: WAL journal
 * mode, {@code foreign_keys=ON}, {@code busy_timeout=5000}.
 *
 * <p>Pragmas are applied via {@link SQLiteConfig} rather than ad-hoc connection-init SQL so they
 * are guaranteed to be set on every physical connection the driver opens (verified by {@code
 * PragmaIntegrationTest}, which queries {@code PRAGMA journal_mode}/{@code PRAGMA foreign_keys}
 * through the real Spring-wired {@code DataSource}).
 *
 * <p>Defining this {@code @Bean} explicitly makes Spring Boot's {@code DataSourceAutoConfiguration}
 * back off entirely ({@code @ConditionalOnMissingBean(DataSource.class)}), so no HikariCP pool is
 * created — appropriate for a single-user embedded desktop database (docs/adr/004, "no write
 * concurrency").
 */
@Configuration
public class DataSourceConfig {

    /**
     * Production default (CLAUDE.md "SQLite rules": {@code busy_timeout=5000}). Overridable via
     * {@code app.sqlite.busy-timeout-ms} — used ONLY by the test suite ({@code
     * src/test/resources/application.properties} raises it to 30000, see the comment there): a
     * background solve's writeback can briefly hold the write lock while another connection
     * writes, and slow CI runners stretch "briefly" past 5 s. In the packaged app nothing
     * competes for the write lock (single user, solver writeback guarded by 409s on mutating
     * endpoints), so production keeps the tight timeout as a genuine deadlock/regression alarm.
     */
    static final int BUSY_TIMEOUT_MS = 5000;

    @Bean
    public DataSource dataSource(
            AppDataDirResolver appDataDirResolver,
            @Value("${app.sqlite.busy-timeout-ms:" + BUSY_TIMEOUT_MS + "}") int busyTimeoutMs) {
        SQLiteConfig config = new SQLiteConfig();
        config.enforceForeignKeys(true);
        config.setJournalMode(SQLiteConfig.JournalMode.WAL);
        config.setBusyTimeout(busyTimeoutMs);

        SQLiteDataSource dataSource = new SQLiteDataSource(config);
        dataSource.setUrl(appDataDirResolver.jdbcUrl());
        return dataSource;
    }
}
