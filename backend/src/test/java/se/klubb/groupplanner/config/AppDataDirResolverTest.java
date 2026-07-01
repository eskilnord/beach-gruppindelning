package se.klubb.groupplanner.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Unit tests for the {@code --app.data-dir=} property > {@code GP_DATA_DIR} env > platform default
 * precedence (docs/design/02-product-data-ui.md §1, docs/plan.md M1 row). Uses only the
 * package-private, fully-parameterized constructor and temp dirs so the real platform data
 * directory on the machine running the tests is never touched.
 */
class AppDataDirResolverTest {

    @TempDir
    Path tempDir;

    @Test
    void propertyTakesPrecedenceOverEnvAndDefault() {
        Path propertyDir = tempDir.resolve("from-property");
        Path envDir = tempDir.resolve("from-env");

        AppDataDirResolver resolver = new AppDataDirResolver(
                propertyDir.toString(), envDir.toString(), "Mac OS X", tempDir.toString(), null);

        assertThat(resolver.dataDir()).isEqualTo(propertyDir);
        assertThat(Files.isDirectory(propertyDir)).isTrue();
    }

    @Test
    void envTakesPrecedenceOverDefaultWhenPropertyAbsent() {
        Path envDir = tempDir.resolve("from-env");

        AppDataDirResolver resolver =
                new AppDataDirResolver(null, envDir.toString(), "Mac OS X", tempDir.toString(), null);

        assertThat(resolver.dataDir()).isEqualTo(envDir);
        assertThat(Files.isDirectory(envDir)).isTrue();
    }

    @Test
    void blankPropertyAndEnvFallThroughToDefault() {
        AppDataDirResolver resolver = new AppDataDirResolver("   ", "  ", "Mac OS X", tempDir.toString(), null);

        assertThat(resolver.dataDir()).isEqualTo(tempDir.resolve("Library/Application Support/Gruppindelning"));
    }

    @Test
    void macDefaultUsesApplicationSupport() {
        Path dir = AppDataDirResolver.platformDefault("Mac OS X", tempDir.toString(), null);

        assertThat(dir).isEqualTo(tempDir.resolve("Library/Application Support/Gruppindelning"));
    }

    @Test
    void windowsDefaultUsesAppDataEnvVar() {
        Path windowsAppData = tempDir.resolve("Roaming");

        Path dir = AppDataDirResolver.platformDefault("Windows 11", tempDir.toString(), windowsAppData.toString());

        assertThat(dir).isEqualTo(windowsAppData.resolve("Gruppindelning"));
    }

    @Test
    void windowsDefaultFallsBackWhenAppDataEnvVarMissing() {
        Path dir = AppDataDirResolver.platformDefault("Windows 11", tempDir.toString(), null);

        assertThat(dir).isEqualTo(tempDir.resolve("AppData/Roaming/Gruppindelning"));
    }

    @Test
    void linuxDefaultUsesXdgStyleDataHome() {
        Path dir = AppDataDirResolver.platformDefault("Linux", tempDir.toString(), null);

        assertThat(dir).isEqualTo(tempDir.resolve(".local/share/gruppindelning"));
    }

    @Test
    void databaseFileAndJdbcUrlAreDerivedFromDataDir() {
        Path dataDir = tempDir.resolve("data");

        AppDataDirResolver resolver = new AppDataDirResolver(dataDir.toString(), null, "Mac OS X", tempDir.toString(), null);

        assertThat(resolver.databaseFile()).isEqualTo(dataDir.resolve("gruppindelning.db"));
        assertThat(resolver.jdbcUrl()).isEqualTo("jdbc:sqlite:" + dataDir.resolve("gruppindelning.db"));
    }
}
