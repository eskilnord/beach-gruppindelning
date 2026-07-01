package se.klubb.groupplanner.system;

import org.springframework.boot.web.context.WebServerInitializedEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/**
 * On {@link WebServerInitializedEvent}, prints exactly one {@code GP_READY} line to stdout so the
 * Tauri shell can discover the actual bound port (see docs/design/01-architecture.md §4). M0 has no
 * persistence yet, so {@code schemaVersion} is fixed at 0.
 */
@Component
public class GpReadyPublisher {

    public static final int SCHEMA_VERSION = 0;

    @EventListener
    public void onWebServerInitialized(WebServerInitializedEvent event) {
        int port = event.getWebServer().getPort();
        long pid = ProcessHandle.current().pid();
        System.out.println(GpReadyMessage.build(port, pid, SCHEMA_VERSION));
    }
}
