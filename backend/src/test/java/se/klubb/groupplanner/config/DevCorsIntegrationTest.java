package se.klubb.groupplanner.config;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.ActiveProfiles;

/**
 * Regression test for a real bug caught during M0 manual verification: {@link TokenAuthFilter} ran
 * before Spring's CORS handling and rejected every preflight (browsers never send the custom
 * {@code X-GP-Token} header on an OPTIONS preflight), which broke {@link DevCorsConfig} entirely.
 * The filter now special-cases CORS preflight requests (docs/design/01-architecture.md §4/§6).
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("dev")
class DevCorsIntegrationTest {

    @Autowired
    private TestRestTemplate restTemplate;

    @Test
    void corsPreflightSucceedsWithoutToken() {
        HttpHeaders headers = new HttpHeaders();
        headers.set("Origin", "http://localhost:5173");
        headers.set("Access-Control-Request-Method", "GET");
        headers.set("Access-Control-Request-Headers", "X-GP-Token");

        ResponseEntity<String> response = restTemplate.exchange(
                "/api/health", HttpMethod.OPTIONS, new HttpEntity<>(headers), String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getHeaders().getAccessControlAllowOrigin()).isEqualTo("http://localhost:5173");
    }

    @Test
    void actualCrossOriginRequestStillRequiresToken() {
        HttpHeaders headers = new HttpHeaders();
        headers.set("Origin", "http://localhost:5173");

        ResponseEntity<String> response = restTemplate.exchange(
                "/api/health", HttpMethod.GET, new HttpEntity<>(headers), String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }
}
