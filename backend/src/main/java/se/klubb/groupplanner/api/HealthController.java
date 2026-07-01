package se.klubb.groupplanner.api;

import java.util.Map;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Hand-rolled health endpoint (no actuator, per M0 spec) used by the desktop shell's post-handshake
 * health poll (docs/design/01-architecture.md §4, step 4).
 */
@RestController
public class HealthController {

    @GetMapping("/api/health")
    public Map<String, String> health() {
        return Map.of("status", "UP");
    }
}
