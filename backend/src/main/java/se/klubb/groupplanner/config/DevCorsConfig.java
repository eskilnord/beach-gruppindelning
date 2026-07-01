package se.klubb.groupplanner.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.web.servlet.config.annotation.CorsRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * Allows the Vite dev server (http://localhost:5173) to call the backend directly when it runs on
 * the fixed dev port 4517 outside the Tauri shell (docs/design/01-architecture.md §6). Not active
 * outside the {@code dev} profile: the packaged app talks to its own loopback backend only, no
 * cross-origin requests are expected.
 */
@Configuration
@Profile("dev")
public class DevCorsConfig implements WebMvcConfigurer {

    @Override
    public void addCorsMappings(CorsRegistry registry) {
        registry.addMapping("/api/**")
                .allowedOrigins("http://localhost:5173")
                .allowedMethods("GET", "POST", "PUT", "PATCH", "DELETE", "OPTIONS")
                .allowedHeaders("*");
    }
}
