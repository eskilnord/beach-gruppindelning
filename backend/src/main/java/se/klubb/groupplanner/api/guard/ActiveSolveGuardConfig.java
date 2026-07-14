package se.klubb.groupplanner.api.guard;

import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * Registers {@link ActiveSolveGuardInterceptor} for every {@code /api/**} route (registration
 * precedent: {@code se.klubb.groupplanner.config.TauriCorsConfig}'s {@code WebMvcConfigurer}). The
 * interceptor itself no-ops for read-only methods and non-plan-scoped resources, so scoping the
 * mapping any narrower than {@code /api/**} would only make the routing harder to audit.
 */
@Configuration
public class ActiveSolveGuardConfig implements WebMvcConfigurer {

    private final ActiveSolveGuardInterceptor interceptor;

    public ActiveSolveGuardConfig(ActiveSolveGuardInterceptor interceptor) {
        this.interceptor = interceptor;
    }

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(interceptor).addPathPatterns("/api/**");
    }
}
