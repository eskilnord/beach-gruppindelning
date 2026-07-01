package se.klubb.groupplanner.system;

/**
 * Builds the exact {@code GP_READY} stdout line the desktop shell parses during the startup
 * handshake (docs/design/01-architecture.md §4, step 3).
 *
 * <p>The format is intentionally hand-rolled (no Jackson) so it stays a trivially testable, single
 * compact line with no whitespace: {@code GP_READY {"port":54321,"pid":4242,"schemaVersion":0}}.
 */
public final class GpReadyMessage {

    private static final String PREFIX = "GP_READY ";

    private GpReadyMessage() {
    }

    /** Builds the full line, including the {@code GP_READY } prefix, ready to print to stdout. */
    public static String build(int port, long pid, int schemaVersion) {
        return PREFIX + json(port, pid, schemaVersion);
    }

    /** Builds just the compact JSON payload (no spaces), for tests that check the JSON shape. */
    public static String json(int port, long pid, int schemaVersion) {
        return "{\"port\":" + port + ",\"pid\":" + pid + ",\"schemaVersion\":" + schemaVersion + "}";
    }
}
