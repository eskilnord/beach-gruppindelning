package se.klubb.groupplanner.api;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

/**
 * Verifies the token-gated health handshake endpoint (docs/design/01-architecture.md §4). GP_TOKEN
 * is fixed to "test-secret-token" for the test JVM via the surefire plugin config in pom.xml.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class HealthEndpointTest {

    private static final String VALID_TOKEN = "test-secret-token";

    @Autowired
    private TestRestTemplate restTemplate;

    @Test
    void healthReturns200WithCorrectToken() {
        HttpHeaders headers = new HttpHeaders();
        headers.set("X-GP-Token", VALID_TOKEN);

        ResponseEntity<String> response =
                restTemplate.exchange("/api/health", org.springframework.http.HttpMethod.GET,
                        new HttpEntity<>(headers), String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).isEqualTo("{\"status\":\"UP\"}");
    }

    @Test
    void healthReturns401WithoutToken() {
        ResponseEntity<String> response = restTemplate.getForEntity("/api/health", String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    @Test
    void healthReturns401WithWrongToken() {
        HttpHeaders headers = new HttpHeaders();
        headers.set("X-GP-Token", "not-the-right-token");

        ResponseEntity<String> response =
                restTemplate.exchange("/api/health", org.springframework.http.HttpMethod.GET,
                        new HttpEntity<>(headers), String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }
}
