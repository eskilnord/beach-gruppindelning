package se.klubb.groupplanner.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * Regression test for the v0.1.0 field report (2026-07-02): the packaged app's webview has origin
 * {@code tauri://localhost} (macOS) / {@code http://tauri.localhost} (Windows), so every SPA fetch
 * to the backend's random loopback port is cross-origin. Without {@link TauriCorsConfig}'s headers
 * the installed app failed with "Failed to fetch" on BOTH platforms while CI's --smoke (Rust-side
 * HTTP, no CORS) stayed green. Runs in the DEFAULT profile — the packaged configuration.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class TauriCorsIntegrationTest {

    private static final String VALID_TOKEN = "test-secret-token";

    @TempDir
    static Path dataDir;

    @DynamicPropertySource
    static void appDataDir(DynamicPropertyRegistry registry) {
        registry.add("app.data-dir", () -> dataDir.toString());
    }

    @Autowired
    private TestRestTemplate restTemplate;

    @Test
    void macWebviewOriginGetsCorsHeadersOnPreflightAndRealRequest() {
        assertOriginAllowed("tauri://localhost");
    }

    @Test
    void windowsWebviewOriginGetsCorsHeadersOnPreflightAndRealRequest() {
        assertOriginAllowed("http://tauri.localhost");
    }

    private void assertOriginAllowed(String origin) {
        HttpHeaders preflight = new HttpHeaders();
        preflight.set("Origin", origin);
        preflight.set("Access-Control-Request-Method", "GET");
        preflight.set("Access-Control-Request-Headers", "X-GP-Token");
        ResponseEntity<String> preflightResponse = restTemplate.exchange(
                "/api/health", HttpMethod.OPTIONS, new HttpEntity<>(preflight), String.class);
        assertThat(preflightResponse.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(preflightResponse.getHeaders().getAccessControlAllowOrigin()).isEqualTo(origin);

        HttpHeaders real = new HttpHeaders();
        real.set("Origin", origin);
        real.set("X-GP-Token", VALID_TOKEN);
        ResponseEntity<String> realResponse = restTemplate.exchange(
                "/api/health", HttpMethod.GET, new HttpEntity<>(real), String.class);
        assertThat(realResponse.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(realResponse.getHeaders().getAccessControlAllowOrigin()).isEqualTo(origin);
        // The export download filename must be readable by the SPA (M8 apiDownload).
        assertThat(realResponse.getHeaders().getAccessControlExposeHeaders()).contains("Content-Disposition");
    }

    @Test
    void viteDevOriginIsNotAllowedInThePackagedProfile() {
        HttpHeaders headers = new HttpHeaders();
        headers.set("Origin", "http://localhost:5173");
        headers.set("Access-Control-Request-Method", "GET");
        headers.set("Access-Control-Request-Headers", "X-GP-Token");

        ResponseEntity<String> response = restTemplate.exchange(
                "/api/health", HttpMethod.OPTIONS, new HttpEntity<>(headers), String.class);

        // Spring rejects a preflight from a non-allowed origin with 403.
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
    }

    @Test
    void crossOriginRequestFromWebviewStillRequiresTheToken() {
        HttpHeaders headers = new HttpHeaders();
        headers.set("Origin", "tauri://localhost");

        ResponseEntity<String> response = restTemplate.exchange(
                "/api/health", HttpMethod.GET, new HttpEntity<>(headers), String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }
}
