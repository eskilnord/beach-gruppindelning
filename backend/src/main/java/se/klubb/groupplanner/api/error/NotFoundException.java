package se.klubb.groupplanner.api.error;

/** Thrown by controllers/services when a path-referenced entity does not exist. Maps to 404. */
public class NotFoundException extends RuntimeException {

    public NotFoundException(String message) {
        super(message);
    }
}
