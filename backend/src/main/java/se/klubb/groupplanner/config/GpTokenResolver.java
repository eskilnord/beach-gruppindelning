package se.klubb.groupplanner.config;

/**
 * Pure resolution logic for the effective {@code GP_TOKEN}, kept free of Spring/env-var lookups so
 * it is trivially unit-testable (see docs/design/01-architecture.md §4, step 3).
 *
 * <ul>
 *   <li>If {@code GP_TOKEN} is set and non-blank, it is used as-is.</li>
 *   <li>Otherwise, if the {@code dev} profile is active, the fixed fallback token {@value
 *       #DEV_FALLBACK_TOKEN} is used (matches {@code npm run dev}'s fixed dev token).</li>
 *   <li>Otherwise, startup fails with a clear error: a missing token outside {@code dev} is a
 *       misconfiguration, not something to silently work around.</li>
 * </ul>
 */
public final class GpTokenResolver {

    public static final String DEV_FALLBACK_TOKEN = "dev";

    private GpTokenResolver() {
    }

    public static String resolve(String gpTokenEnv, boolean devProfileActive) {
        if (gpTokenEnv != null && !gpTokenEnv.isBlank()) {
            return gpTokenEnv;
        }
        if (devProfileActive) {
            return DEV_FALLBACK_TOKEN;
        }
        throw new IllegalStateException(
                "GP_TOKEN environment variable is required outside the 'dev' profile. "
                        + "Set GP_TOKEN before launching the backend, or start with "
                        + "--spring.profiles.active=dev for local development (fixed token 'dev').");
    }
}
