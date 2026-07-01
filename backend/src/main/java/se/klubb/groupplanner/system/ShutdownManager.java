package se.klubb.groupplanner.system;

import java.time.Duration;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.stereotype.Component;

/**
 * Triggers an asynchronous, graceful process exit for {@code POST /api/system/shutdown}
 * (docs/design/01-architecture.md §4, step 5). The exit runs on a separate daemon thread with a
 * short delay so the HTTP response has time to flush before the JVM goes down.
 */
@Component
public class ShutdownManager {

    private static final Logger log = LoggerFactory.getLogger(ShutdownManager.class);
    private static final Duration DEFAULT_DELAY = Duration.ofMillis(250);

    private final ConfigurableApplicationContext context;
    private final ProcessExiter exiter;
    private final Duration delay;

    @Autowired
    public ShutdownManager(ConfigurableApplicationContext context, ProcessExiter exiter) {
        this(context, exiter, DEFAULT_DELAY);
    }

    ShutdownManager(ConfigurableApplicationContext context, ProcessExiter exiter, Duration delay) {
        this.context = context;
        this.exiter = exiter;
        this.delay = delay;
    }

    /** Schedules a graceful exit on a daemon thread; returns immediately. */
    public void triggerShutdown() {
        log.info("Shutdown requested via POST /api/system/shutdown; exiting in {}ms", delay.toMillis());
        Thread shutdownThread = new Thread(this::exitAfterDelay, "gp-shutdown");
        shutdownThread.setDaemon(true);
        shutdownThread.start();
    }

    private void exitAfterDelay() {
        try {
            Thread.sleep(delay.toMillis());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        exiter.exit(context, 0);
    }
}
