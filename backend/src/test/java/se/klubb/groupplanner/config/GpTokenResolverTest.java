package se.klubb.groupplanner.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

/**
 * Unit tests for the GP_TOKEN fallback/fail-fast rules described in
 * docs/design/01-architecture.md §4.
 */
class GpTokenResolverTest {

    @Test
    void usesEnvTokenWhenPresent() {
        String token = GpTokenResolver.resolve("supplied-token", false);

        assertThat(token).isEqualTo("supplied-token");
    }

    @Test
    void usesEnvTokenEvenInDevProfile() {
        String token = GpTokenResolver.resolve("supplied-token", true);

        assertThat(token).isEqualTo("supplied-token");
    }

    @Test
    void fallsBackToDevTokenWhenMissingInDevProfile() {
        assertThat(GpTokenResolver.resolve(null, true)).isEqualTo("dev");
        assertThat(GpTokenResolver.resolve("", true)).isEqualTo("dev");
        assertThat(GpTokenResolver.resolve("   ", true)).isEqualTo("dev");
    }

    @Test
    void failsFastWhenMissingOutsideDevProfile() {
        assertThatThrownBy(() -> GpTokenResolver.resolve(null, false))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("GP_TOKEN");

        assertThatThrownBy(() -> GpTokenResolver.resolve("", false))
                .isInstanceOf(IllegalStateException.class);
    }
}
