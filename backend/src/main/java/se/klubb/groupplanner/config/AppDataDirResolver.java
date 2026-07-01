package se.klubb.groupplanner.config;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

/**
 * Resolves the directory the backend stores its SQLite database (and, per {@code
 * logback-spring.xml}, its logs) in, and ensures it exists.
 *
 * <p>Priority order (docs/design/02-product-data-ui.md §1, docs/plan.md M1 row):
 *
 * <ol>
 *   <li>{@code --app.data-dir=} Spring property (command-line arg, {@code -D} system property, or
 *       {@code app.data-dir} in a properties/yaml source) — used by dev mode and tests.
 *   <li>{@code GP_DATA_DIR} environment variable — set by the Tauri shell (docs/design/01-architecture.md
 *       §4) when spawning the packaged backend.
 *   <li>Platform default: macOS {@code ~/Library/Application Support/Gruppindelning/}, Windows
 *       {@code %APPDATA%\Gruppindelning\}, Linux {@code ~/.local/share/gruppindelning/}.
 * </ol>
 *
 * <p>The resolution logic itself ({@link #resolveDir}) is a pure static function so it is
 * unit-testable without touching the real filesystem or the real platform default directory.
 */
@Component
public class AppDataDirResolver {

    public static final String APP_DATA_DIR_PROPERTY = "app.data-dir";
    public static final String GP_DATA_DIR_ENV = "GP_DATA_DIR";
    public static final String DB_FILE_NAME = "gruppindelning.db";

    private final Path dataDir;

    @Autowired
    public AppDataDirResolver(Environment environment) {
        this(
                environment.getProperty(APP_DATA_DIR_PROPERTY),
                System.getenv(GP_DATA_DIR_ENV),
                System.getProperty("os.name"),
                System.getProperty("user.home"),
                System.getenv("APPDATA"));
    }

    /** Package-private, fully-parameterized constructor used directly by unit tests. */
    AppDataDirResolver(String propertyValue, String envValue, String osName, String userHome, String windowsAppData) {
        this.dataDir = resolveDir(propertyValue, envValue, osName, userHome, windowsAppData);
        createIfMissing(this.dataDir);
    }

    /** Absolute path to the resolved app data directory (guaranteed to exist after construction). */
    public Path dataDir() {
        return dataDir;
    }

    /** Absolute path to the SQLite database file inside {@link #dataDir()}. */
    public Path databaseFile() {
        return dataDir.resolve(DB_FILE_NAME);
    }

    /** JDBC URL for {@link #databaseFile()}. */
    public String jdbcUrl() {
        return "jdbc:sqlite:" + databaseFile();
    }

    static Path resolveDir(String propertyValue, String envValue, String osName, String userHome, String windowsAppData) {
        if (propertyValue != null && !propertyValue.isBlank()) {
            return Path.of(propertyValue);
        }
        if (envValue != null && !envValue.isBlank()) {
            return Path.of(envValue);
        }
        return platformDefault(osName, userHome, windowsAppData);
    }

    static Path platformDefault(String osName, String userHome, String windowsAppData) {
        String os = osName == null ? "" : osName.toLowerCase(Locale.ROOT);
        if (os.contains("mac") || os.contains("darwin")) {
            return Path.of(userHome, "Library", "Application Support", "Gruppindelning");
        }
        if (os.contains("win")) {
            String appData = (windowsAppData != null && !windowsAppData.isBlank())
                    ? windowsAppData
                    : Path.of(userHome, "AppData", "Roaming").toString();
            return Path.of(appData, "Gruppindelning");
        }
        // Linux and anything else: XDG-style data home.
        return Path.of(userHome, ".local", "share", "gruppindelning");
    }

    private static void createIfMissing(Path dir) {
        try {
            Files.createDirectories(dir);
        } catch (IOException e) {
            throw new UncheckedIOException("Could not create app data directory " + dir, e);
        }
    }
}
