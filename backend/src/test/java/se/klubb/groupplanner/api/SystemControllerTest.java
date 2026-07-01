package se.klubb.groupplanner.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.verify;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import se.klubb.groupplanner.system.ProcessExiter;

/**
 * Verifies POST /api/system/shutdown (docs/design/01-architecture.md §4, step 5) without actually
 * killing the test JVM: {@link ProcessExiter} is mocked, so we can assert it gets called
 * asynchronously after the HTTP response is returned, instead of exercising the real
 * SpringApplication.exit/System.exit path.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class SystemControllerTest {

    private static final String VALID_TOKEN = "test-secret-token";

    @Autowired
    private TestRestTemplate restTemplate;

    @MockitoBean
    private ProcessExiter processExiter;

    @Test
    void shutdownReturns200WithTokenAndTriggersAsyncExit() {
        HttpHeaders headers = new HttpHeaders();
        headers.set("X-GP-Token", VALID_TOKEN);

        ResponseEntity<String> response =
                restTemplate.exchange("/api/system/shutdown", HttpMethod.POST,
                        new HttpEntity<>(headers), String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).isEqualTo("{\"status\":\"SHUTTING_DOWN\"}");

        // The exit happens on a daemon thread after a short delay, once the response has
        // already been flushed - so we wait (bounded) rather than asserting synchronously.
        verify(processExiter, timeout(3000)).exit(any(ConfigurableApplicationContext.class), eq(0));
    }

    @Test
    void shutdownReturns401WithoutToken() {
        ResponseEntity<String> response =
                restTemplate.postForEntity("/api/system/shutdown", null, String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }
}
