package se.klubb.groupplanner.api.error;

/**
 * Thrown for application-level conflicts that are not detectable via a SQL constraint (e.g. an
 * exact-duplicate {@code TimeSlot} — {@code UNIQUE} can't express it because {@code date} is
 * nullable and SQL treats every {@code NULL} as distinct). Maps to 409, same shape as the
 * {@code DataIntegrityViolationException} 409 path in {@link ApiExceptionHandler}.
 */
public class ConflictException extends RuntimeException {

    public ConflictException(String message) {
        super(message);
    }
}
