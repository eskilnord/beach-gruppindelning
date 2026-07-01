package se.klubb.groupplanner.api;

import java.util.Map;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import se.klubb.groupplanner.system.ShutdownManager;

/**
 * System-level control endpoints. {@code /api/system/shutdown} is how the desktop shell asks the
 * backend to exit gracefully on normal app close (docs/design/01-architecture.md §4, step 5).
 */
@RestController
@RequestMapping("/api/system")
public class SystemController {

    private final ShutdownManager shutdownManager;

    public SystemController(ShutdownManager shutdownManager) {
        this.shutdownManager = shutdownManager;
    }

    @PostMapping("/shutdown")
    public Map<String, String> shutdown() {
        shutdownManager.triggerShutdown();
        return Map.of("status", "SHUTTING_DOWN");
    }
}
