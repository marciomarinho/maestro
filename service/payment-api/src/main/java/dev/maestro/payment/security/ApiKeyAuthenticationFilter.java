package dev.maestro.payment.security;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.Optional;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Authenticates every request before it reaches any merchant-scoped code.
 *
 * <p>Phase 1 accepts API keys; portal JWTs and the full role model arrive in Phase 6.
 * What matters now is that the shape is right — nothing is ever unauthenticated, and
 * the tenant is established at the edge rather than taken from a request body, where a
 * caller could name a merchant that is not theirs.
 */
public class ApiKeyAuthenticationFilter extends OncePerRequestFilter {

    private static final String BEARER = "Bearer ";

    private final ApiKeyRepository apiKeys;

    public ApiKeyAuthenticationFilter(ApiKeyRepository apiKeys) {
        this.apiKeys = apiKeys;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        // The management surface is consumed by machinery with no credential to
        // present: health by the container runtime, /actuator/prometheus by the
        // scraper. Nothing merchant-scoped is reachable through any of it.
        return request.getRequestURI().startsWith("/actuator/");
    }

    @Override
    protected void doFilterInternal(
            HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {

        Optional<MerchantPrincipal> principal = extractSecret(request)
                .flatMap(apiKeys::findActiveBySecret);

        if (principal.isEmpty()) {
            unauthorized(response);
            return;
        }

        try {
            TenantContext.callWith(principal.get(), () -> {
                chain.doFilter(request, response);
                return null;
            });
        } catch (IOException | ServletException | RuntimeException e) {
            throw e;
        } catch (Exception e) {
            throw new ServletException(e);
        }
    }

    private static Optional<String> extractSecret(HttpServletRequest request) {
        String header = request.getHeader(HttpHeaders.AUTHORIZATION);
        if (header == null || !header.startsWith(BEARER)) {
            return Optional.empty();
        }
        String secret = header.substring(BEARER.length()).trim();
        return secret.isEmpty() ? Optional.empty() : Optional.of(secret);
    }

    private static void unauthorized(HttpServletResponse response) throws IOException {
        // Deliberately does not distinguish "no credential" from "bad credential":
        // the difference is only useful to someone probing for valid keys.
        response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
        response.setContentType(MediaType.APPLICATION_PROBLEM_JSON_VALUE);
        response.getWriter().write("""
                {"type":"https://maestro.dev/errors/unauthorized",\
                "title":"Unauthorized",\
                "status":401,\
                "code":"unauthorized",\
                "detail":"A valid API key must be supplied in the Authorization header."}""");
    }
}
