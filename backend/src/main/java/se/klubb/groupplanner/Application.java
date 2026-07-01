package se.klubb.groupplanner;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * Entry point for the Gruppindelning desktop backend.
 *
 * <p>This process is spawned by the Tauri shell (or run directly in the {@code dev} profile) and
 * speaks the localhost handshake protocol described in {@code docs/design/01-architecture.md} §4:
 * bind 127.0.0.1, print a single {@code GP_READY} line to stdout once the web server is up, and
 * require an {@code X-GP-Token} header on all {@code /api/*} requests.
 */
@SpringBootApplication
public class Application {

    public static void main(String[] args) {
        SpringApplication.run(Application.class, args);
    }
}
