package dev.maestro.router.web;

import dev.maestro.router.RouterProperties;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import org.springframework.http.MediaType;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Guards the routing operations endpoints with a shared secret.
 *
 * <p>The same modest mechanism the ledger uses, for the same reason: the router has no
 * identity model of its own until Phase 6, and what matters now is that nothing is
 * reachable unauthenticated. Constant-time comparison, so the token's prefix is not
 * leaked through response timing.
 *
 * <p>What it protects is worth being explicit about. The routing view exposes which
 * acquirers the platform holds relationships with, what it thinks of each of them, and
 * what they cost — commercially sensitive in a way a payment status is not.
 */
public class OpsTokenFilter extends OncePerRequestFilter {

    private static final String BEARER = "Bearer ";

    private final byte[] expectedToken;

    public OpsTokenFilter(RouterProperties properties) {
        this.expectedToken = properties.opsToken().getBytes(StandardCharsets.UTF_8);
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return request.getRequestURI().startsWith("/actuator/health");
    }

    @Override
    protected void doFilterInternal(
            HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {

        String header = request.getHeader("Authorization");
        String presented = header != null && header.startsWith(BEARER)
                ? header.substring(BEARER.length()).trim()
                : "";

        if (!MessageDigest.isEqual(presented.getBytes(StandardCharsets.UTF_8), expectedToken)) {
            response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
            response.setContentType(MediaType.APPLICATION_PROBLEM_JSON_VALUE);
            response.getWriter().write("""
                    {"type":"https://maestro.dev/errors/unauthorized",\
                    "title":"Unauthorized",\
                    "status":401,\
                    "code":"unauthorized",\
                    "detail":"The routing operations API requires the platform operations token."}""");
            return;
        }
        chain.doFilter(request, response);
    }
}
