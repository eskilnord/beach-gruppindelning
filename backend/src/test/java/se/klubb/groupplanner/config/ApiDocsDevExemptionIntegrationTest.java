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
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * In the {@code dev} profile, {@code /v3/api-docs} (springdoc's generated OpenAPI JSON) must be
 * reachable without an {@code X-GP-Token} header, per docs/plan.md ("servlet filter 401s any
 * request without X-GP-Token header ... /v3/api-docs exempted in dev profile only") — the
 * frontend's {@code npm run typegen} step fetches the spec with a plain, unauthenticated GET via
 * openapi-typescript. {@code /api/health} must still require the token even in this profile: a
 * regression guard proving {@link TokenAuthFilter}'s exemption is scoped to {@code /v3/api-docs}
 * only, not widened to every path. See {@link ApiDocsDefaultProfileIntegrationTest} for the
 * default-profile counterpart (spec endpoint stays token-guarded there).
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("dev")
class ApiDocsDevExemptionIntegrationTest {

    @TempDir
    static Path dataDir;

    @DynamicPropertySource
    static void appDataDir(DynamicPropertyRegistry registry) {
        registry.add("app.data-dir", () -> dataDir.toString());
    }

    @Autowired
    private TestRestTemplate restTemplate;

    @Test
    void apiDocsAccessibleWithoutTokenInDevProfile() {
        ResponseEntity<String> response = restTemplate.getForEntity("/v3/api-docs", String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).contains("\"openapi\"");
    }

    @Test
    void healthStillRequiresTokenInDevProfile() {
        ResponseEntity<String> response = restTemplate.getForEntity("/api/health", String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }
}
