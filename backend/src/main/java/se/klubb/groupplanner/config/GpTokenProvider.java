package se.klubb.groupplanner.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.env.Environment;
import org.springframework.core.env.Profiles;
import org.springframework.stereotype.Component;

/**
 * Resolves and holds the effective {@code GP_TOKEN} for the lifetime of the application context.
 * Resolution happens once, at construction time, so a missing token outside the {@code dev}
 * profile fails application startup immediately with a clear error (docs/design/01-architecture.md
 * §4).
 */
@Component
public class GpTokenProvider {

    private static final Logger log = LoggerFactory.getLogger(GpTokenProvider.class);

    private final String token;

    public GpTokenProvider(Environment environment) {
        boolean devProfileActive = environment.acceptsProfiles(Profiles.of("dev"));
        this.token = GpTokenResolver.resolve(System.getenv("GP_TOKEN"), devProfileActive);
        if (devProfileActive && (System.getenv("GP_TOKEN") == null || System.getenv("GP_TOKEN").isBlank())) {
            log.warn("GP_TOKEN not set; falling back to dev token '{}' because the 'dev' profile is active",
                    GpTokenResolver.DEV_FALLBACK_TOKEN);
        }
    }

    public String token() {
        return token;
    }
}
