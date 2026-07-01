package se.klubb.groupplanner.system;

import org.springframework.context.ConfigurableApplicationContext;

/**
 * Seam for the JVM-terminating side-effect of shutdown, so {@link ShutdownManager} can be tested
 * without actually killing the test JVM (swap in a mock/spy bean in tests).
 */
public interface ProcessExiter {

    /** Gracefully closes the Spring context and terminates the process with the given exit code. */
    void exit(ConfigurableApplicationContext context, int exitCode);
}
