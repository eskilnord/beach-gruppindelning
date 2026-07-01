package se.klubb.groupplanner.config;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import org.springframework.core.env.Environment;
import org.springframework.core.env.Profiles;
import org.springframework.http.MediaType;
import org.springframework.web.cors.CorsUtils;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Rejects any request whose {@code X-GP-Token} header does not match the effective {@code
 * GP_TOKEN} with a 401 JSON body. Registered for {@code /api/*} and {@code /v3/api-docs/*} (see
 * {@link SecurityFilterConfig}) per docs/design/01-architecture.md §4 and docs/plan.md ("servlet
 * filter 401s any request without X-GP-Token header ... /v3/api-docs exempted in dev profile
 * only").
 *
 * <p>CORS preflight ({@code OPTIONS} with {@code Access-Control-Request-Method}) requests are let
 * through untouched: browsers never attach custom headers such as {@code X-GP-Token} to a
 * preflight, so this filter would otherwise 401 every preflight before Spring's CORS handling
 * (dev profile only, see {@link DevCorsConfig}) ever runs, breaking cross-origin calls from the
 * Vite dev server. Preflight requests carry no body and reach no controller, so this is safe.
 *
 * <p>In the {@code dev} profile only, {@code /v3/api-docs} (springdoc's generated OpenAPI JSON
 * document, and any sub-paths under it) is also let through without a token check. This exists
 * for the frontend's {@code npm run typegen} step: it fetches the spec with a plain HTTP GET via
 * openapi-typescript, which has no notion of {@code GP_TOKEN} and cannot attach the header.
 * Outside the {@code dev} profile the spec endpoint stays token-guarded like every other backend
 * path — it is not meant to be reachable by an unauthenticated caller in a real deployment.
 */
public class TokenAuthFilter extends OncePerRequestFilter {

    static final String HEADER_NAME = "X-GP-Token";
    private static final String UNAUTHORIZED_BODY = "{\"error\":\"unauthorized\"}";
    private static final String API_DOCS_PATH = "/v3/api-docs";

    private final GpTokenProvider tokenProvider;
    private final boolean devProfileActive;

    public TokenAuthFilter(GpTokenProvider tokenProvider, Environment environment) {
        this.tokenProvider = tokenProvider;
        this.devProfileActive = environment.acceptsProfiles(Profiles.of("dev"));
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
            throws ServletException, IOException {
        if (CorsUtils.isPreFlightRequest(request)) {
            filterChain.doFilter(request, response);
            return;
        }

        if (devProfileActive && isApiDocsPath(request)) {
            filterChain.doFilter(request, response);
            return;
        }

        String provided = request.getHeader(HEADER_NAME);
        if (provided != null && constantTimeEquals(provided, tokenProvider.token())) {
            filterChain.doFilter(request, response);
            return;
        }
        response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setCharacterEncoding("UTF-8");
        response.getWriter().write(UNAUTHORIZED_BODY);
    }

    private static boolean isApiDocsPath(HttpServletRequest request) {
        String path = request.getRequestURI();
        return path != null && (path.equals(API_DOCS_PATH) || path.startsWith(API_DOCS_PATH + "/"));
    }

    private static boolean constantTimeEquals(String a, String b) {
        return MessageDigest.isEqual(a.getBytes(StandardCharsets.UTF_8), b.getBytes(StandardCharsets.UTF_8));
    }
}
