package se.klubb.groupplanner.config;

import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;

/**
 * Registers {@link TokenAuthFilter} scoped to {@code /api/*} only, so non-API paths (none exist
 * yet in M0, but future static assets would) are never blocked by the token check.
 */
@Configuration
public class SecurityFilterConfig {

    @Bean
    public FilterRegistrationBean<TokenAuthFilter> tokenAuthFilter(GpTokenProvider tokenProvider) {
        FilterRegistrationBean<TokenAuthFilter> registration = new FilterRegistrationBean<>(new TokenAuthFilter(tokenProvider));
        registration.addUrlPatterns("/api/*");
        registration.setName("tokenAuthFilter");
        registration.setOrder(Ordered.HIGHEST_PRECEDENCE);
        return registration;
    }
}
