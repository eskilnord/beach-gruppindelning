package se.klubb.groupplanner.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * Outside the {@code dev} profile (no profile active, i.e. the default/production-like
 * configuration), {@code /v3/api-docs} must stay token-guarded like every other backend path — the
 * dev-only exemption added to {@link TokenAuthFilter} must not leak into the default profile, per
 * docs/plan.md ("/v3/api-docs exempted in dev profile only"). No {@code @ActiveProfiles} is set
 * here on purpose: this is the default-profile counterpart to {@link
 * ApiDocsDevExemptionIntegrationTest}. {@code /api/health} is asserted too, as the existing
 * regression guard for the token filter in general.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class ApiDocsDefaultProfileIntegrationTest {

    @TempDir
    static Path dataDir;

    @DynamicPropertySource
    static void appDataDir(DynamicPropertyRegistry registry) {
        registry.add("app.data-dir", () -> dataDir.toString());
    }

    @Autowired
    private TestRestTemplate restTemplate;

    @Test
    void apiDocsRequiresTokenOutsideDevProfile() {
        ResponseEntity<String> response = restTemplate.getForEntity("/v3/api-docs", String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    @Test
    void healthRequiresTokenOutsideDevProfile() {
        ResponseEntity<String> response = restTemplate.getForEntity("/api/health", String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }
}
