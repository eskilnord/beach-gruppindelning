package se.klubb.groupplanner.api.error;

/** Thrown for invalid request input (missing required field, malformed value, ...). Maps to 400. */
public class BadRequestException extends RuntimeException {

    public BadRequestException(String message) {
        super(message);
    }
}
