package se.klubb.groupplanner.system;

import jakarta.annotation.PostConstruct;
import java.time.Duration;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * If {@code GP_PARENT_PID} is set, watches the parent (Tauri shell) process every 5 s via {@link
 * ProcessHandle#of(long)} and self-terminates if it disappears, so a crashed/force-quit shell never
 * leaves an orphaned backend running (docs/design/01-architecture.md §4, step 5 / risks).
 */
@Component
public class ParentPidWatchdog {

    private static final Logger log = LoggerFactory.getLogger(ParentPidWatchdog.class);
    static final Duration POLL_INTERVAL = Duration.ofSeconds(5);

    @PostConstruct
    void start() {
        String parentPidEnv = System.getenv("GP_PARENT_PID");
        if (parentPidEnv == null || parentPidEnv.isBlank()) {
            log.debug("GP_PARENT_PID not set; parent watchdog disabled");
            return;
        }

        long parentPid;
        try {
            parentPid = Long.parseLong(parentPidEnv.trim());
        } catch (NumberFormatException e) {
            log.warn("GP_PARENT_PID='{}' is not a valid PID; parent watchdog disabled", parentPidEnv);
            return;
        }

        Thread watchdog = new Thread(() -> watch(parentPid), "gp-parent-watchdog");
        watchdog.setDaemon(true);
        watchdog.start();
        log.info("Parent-PID watchdog started for PID {}", parentPid);
    }

    private void watch(long parentPid) {
        while (true) {
            try {
                Thread.sleep(POLL_INTERVAL.toMillis());
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
            if (!ProcessHandle.of(parentPid).map(ProcessHandle::isAlive).orElse(false)) {
                log.warn("Parent process {} is gone; shutting down", parentPid);
                System.exit(0);
            }
        }
    }
}
