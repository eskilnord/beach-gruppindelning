package se.klubb.groupplanner.config;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import org.springframework.http.MediaType;
import org.springframework.web.cors.CorsUtils;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Rejects any request whose {@code X-GP-Token} header does not match the effective {@code
 * GP_TOKEN} with a 401 JSON body. Registered only for {@code /api/*} (see {@link
 * SecurityFilterConfig}) per docs/design/01-architecture.md §4.
 *
 * <p>CORS preflight ({@code OPTIONS} with {@code Access-Control-Request-Method}) requests are let
 * through untouched: browsers never attach custom headers such as {@code X-GP-Token} to a
 * preflight, so this filter would otherwise 401 every preflight before Spring's CORS handling
 * (dev profile only, see {@link DevCorsConfig}) ever runs, breaking cross-origin calls from the
 * Vite dev server. Preflight requests carry no body and reach no controller, so this is safe.
 */
public class TokenAuthFilter extends OncePerRequestFilter {

    static final String HEADER_NAME = "X-GP-Token";
    private static final String UNAUTHORIZED_BODY = "{\"error\":\"unauthorized\"}";

    private final GpTokenProvider tokenProvider;

    public TokenAuthFilter(GpTokenProvider tokenProvider) {
        this.tokenProvider = tokenProvider;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
            throws ServletException, IOException {
        if (CorsUtils.isPreFlightRequest(request)) {
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

    private static boolean constantTimeEquals(String a, String b) {
        return MessageDigest.isEqual(a.getBytes(StandardCharsets.UTF_8), b.getBytes(StandardCharsets.UTF_8));
    }
}
