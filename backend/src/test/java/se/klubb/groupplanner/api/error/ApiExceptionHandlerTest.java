package se.klubb.groupplanner.api.error;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.multipart.MaxUploadSizeExceededException;

/**
 * Unit tests for handler mappings that are awkward to trigger through MockMvc - currently the
 * multipart size limit (M3 review finding 5): {@code MaxUploadSizeExceededException} must map to
 * 413 with the API-wide {@code {"error": ...}} shape and a message safe to show verbatim in the
 * Swedish wizard UI.
 */
class ApiExceptionHandlerTest {

    private final ApiExceptionHandler handler = new ApiExceptionHandler();

    @Test
    void maxUploadSizeExceededMapsTo413WithClearMessage() {
        ResponseEntity<Map<String, String>> response =
                handler.handleMaxUploadSize(new MaxUploadSizeExceededException(25L * 1024 * 1024));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.PAYLOAD_TOO_LARGE);
        assertThat(response.getBody()).containsKey("error");
        assertThat(response.getBody().get("error")).contains("25 MB");
    }
}
