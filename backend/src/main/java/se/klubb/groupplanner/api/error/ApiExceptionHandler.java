package se.klubb.groupplanner.api.error;

import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.multipart.MaxUploadSizeExceededException;

/**
 * Translates exceptions into the API-wide JSON error shape {@code {"error": "..."}} (docs/plan.md
 * M1 row: "consistent JSON error shape and 404 handling").
 *
 * <p>SQLite constraint violations (unique/foreign-key/not-null) are detected by walking the cause
 * chain for an {@code org.sqlite.SQLiteException} rather than relying on Spring's
 * {@code SQLErrorCodeSQLExceptionTranslator} database-specific error-code table, which does not
 * ship a mapping for SQLite — see backend/docs/m1-notes.md.
 */
@RestControllerAdvice
public class ApiExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(ApiExceptionHandler.class);

    @ExceptionHandler(NotFoundException.class)
    public ResponseEntity<Map<String, String>> handleNotFound(NotFoundException e) {
        return error(HttpStatus.NOT_FOUND, e.getMessage());
    }

    @ExceptionHandler(BadRequestException.class)
    public ResponseEntity<Map<String, String>> handleBadRequest(BadRequestException e) {
        return error(HttpStatus.BAD_REQUEST, e.getMessage());
    }

    @ExceptionHandler(ConflictException.class)
    public ResponseEntity<Map<String, String>> handleConflict(ConflictException e) {
        return error(HttpStatus.CONFLICT, e.getMessage());
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<Map<String, String>> handleUnreadableBody(HttpMessageNotReadableException e) {
        return error(HttpStatus.BAD_REQUEST, "Malformed request body");
    }

    /**
     * Import wizard uploads exceeding {@code spring.servlet.multipart.max-file-size}/{@code
     * max-request-size} (25 MB, application.yaml). Mapped to 413 with a message safe to surface
     * verbatim in the Swedish wizard UI, instead of the generic 500 the catch-all would produce.
     */
    @ExceptionHandler(MaxUploadSizeExceededException.class)
    public ResponseEntity<Map<String, String>> handleMaxUploadSize(MaxUploadSizeExceededException e) {
        return error(HttpStatus.PAYLOAD_TOO_LARGE, "Filen är för stor - max 25 MB");
    }

    @ExceptionHandler(DataIntegrityViolationException.class)
    public ResponseEntity<Map<String, String>> handleDataIntegrityViolation(DataIntegrityViolationException e) {
        if (isSqliteConstraintViolation(e)) {
            return error(HttpStatus.CONFLICT, "Constraint violation: the request conflicts with existing data");
        }
        log.warn("Unclassified data integrity violation", e);
        return error(HttpStatus.CONFLICT, "Constraint violation");
    }

    @ExceptionHandler(DataAccessException.class)
    public ResponseEntity<Map<String, String>> handleDataAccessException(DataAccessException e) {
        if (isSqliteConstraintViolation(e)) {
            return error(HttpStatus.CONFLICT, "Constraint violation: the request conflicts with existing data");
        }
        log.error("Unexpected data access error", e);
        return error(HttpStatus.INTERNAL_SERVER_ERROR, "Internal error");
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<Map<String, String>> handleGeneric(Exception e) {
        log.error("Unhandled exception", e);
        return error(HttpStatus.INTERNAL_SERVER_ERROR, "Internal error");
    }

    private static boolean isSqliteConstraintViolation(Throwable e) {
        Throwable cause = e;
        while (cause != null) {
            if (cause instanceof org.sqlite.SQLiteException sqliteException
                    && sqliteException.getResultCode().name().startsWith("SQLITE_CONSTRAINT")) {
                return true;
            }
            cause = cause.getCause();
        }
        return false;
    }

    private static ResponseEntity<Map<String, String>> error(HttpStatus status, String message) {
        return ResponseEntity.status(status).body(Map.of("error", message));
    }
}
