package se.klubb.groupplanner.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.web.servlet.config.annotation.CorsRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * CORS for the PACKAGED app's own webview (the counterpart of {@link DevCorsConfig}).
 *
 * <p>The Tauri webview does not load the frontend from the backend's origin: the page's origin is
 * {@code tauri://localhost} on macOS/Linux and {@code http://tauri.localhost} on Windows (Tauri v2
 * custom-protocol serving). Every {@code fetch} from the SPA to the backend's
 * {@code http://127.0.0.1:<randomPort>} is therefore CROSS-origin, and without these headers the
 * webview blocks the response — the installed app showed "Failed to fetch" for every call while
 * the Rust-side handshake (no CORS involvement) passed, which is exactly why CI's --smoke never
 * caught it (v0.1.0 field report, 2026-07-02).
 *
 * <p>Scope: only the fixed, well-known Tauri origins — no LAN origins, no wildcards. The
 * {@code X-GP-Token} requirement is unchanged (CORS is a browser gate, not the auth boundary).
 * Active outside the {@code dev} profile; DevCorsConfig covers the Vite origin in dev.
 */
@Configuration
@Profile("!dev")
public class TauriCorsConfig implements WebMvcConfigurer {

    static final String[] TAURI_ORIGINS = {
        "tauri://localhost",
        "http://tauri.localhost",
        "https://tauri.localhost"
    };

    @Override
    public void addCorsMappings(CorsRegistry registry) {
        registry.addMapping("/api/**")
                .allowedOrigins(TAURI_ORIGINS)
                .allowedMethods("GET", "POST", "PUT", "PATCH", "DELETE", "OPTIONS")
                .allowedHeaders("X-GP-Token", "Content-Type")
                .exposedHeaders("Content-Disposition");
    }
}
