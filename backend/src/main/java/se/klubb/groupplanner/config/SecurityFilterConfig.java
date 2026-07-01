package se.klubb.groupplanner.config;

import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;
import org.springframework.core.env.Environment;

/**
 * Registers {@link TokenAuthFilter} scoped to {@code /api/*} and {@code /v3/api-docs/*}, so
 * non-API paths (none exist yet in M0, but future static assets would) are never blocked by the
 * token check. {@code /v3/api-docs} (springdoc's OpenAPI JSON, see the {@code
 * springdoc-openapi-starter-webmvc-api} dependency) is deliberately included here so it is
 * token-guarded by default; {@link TokenAuthFilter} itself carves out a dev-profile-only
 * exemption for that specific path (docs/plan.md: "/v3/api-docs exempted in dev profile only").
 */
@Configuration
public class SecurityFilterConfig {

    @Bean
    public FilterRegistrationBean<TokenAuthFilter> tokenAuthFilter(GpTokenProvider tokenProvider, Environment environment) {
        FilterRegistrationBean<TokenAuthFilter> registration =
                new FilterRegistrationBean<>(new TokenAuthFilter(tokenProvider, environment));
        registration.addUrlPatterns("/api/*", "/v3/api-docs/*");
        registration.setName("tokenAuthFilter");
        registration.setOrder(Ordered.HIGHEST_PRECEDENCE);
        return registration;
    }
}
