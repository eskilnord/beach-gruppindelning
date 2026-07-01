package se.klubb.groupplanner.config;

import javax.sql.DataSource;
import org.sqlite.SQLiteConfig;
import org.sqlite.SQLiteDataSource;
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

    static final int BUSY_TIMEOUT_MS = 5000;

    @Bean
    public DataSource dataSource(AppDataDirResolver appDataDirResolver) {
        SQLiteConfig config = new SQLiteConfig();
        config.enforceForeignKeys(true);
        config.setJournalMode(SQLiteConfig.JournalMode.WAL);
        config.setBusyTimeout(BUSY_TIMEOUT_MS);

        SQLiteDataSource dataSource = new SQLiteDataSource(config);
        dataSource.setUrl(appDataDirResolver.jdbcUrl());
        return dataSource;
    }
}
