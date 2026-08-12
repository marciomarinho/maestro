package dev.maestro.ledger.web;

import dev.maestro.ledger.LedgerProperties;
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
 * Guards the ops endpoints with a shared secret.
 *
 * <p>Deliberately modest: the ledger has no merchant identity of its own until Phase 6
 * brings JWTs, and inventing one here would mean reading another service's tables. What
 * matters is that nothing is reachable unauthenticated — the mechanism is documented as
 * temporary rather than presented as a design.
 *
 * <p>The comparison is constant-time. A shared secret compared with {@code equals} leaks
 * its prefix through response timing, which is a small thing that is free to get right and
 * embarrassing to get wrong in a payments system.
 */
public class OpsTokenFilter extends OncePerRequestFilter {

    private static final String BEARER = "Bearer ";

    private final byte[] expectedToken;

    public OpsTokenFilter(LedgerProperties properties) {
        this.expectedToken = properties.opsToken().getBytes(StandardCharsets.UTF_8);
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        // The management surface is not the ops API: health feeds container
        // orchestration and /actuator/prometheus feeds the scraper, and neither can
        // present a bearer token. Nothing commercially sensitive lives under
        // /actuator — the sensitive views are the /ops endpoints this filter exists for.
        return request.getRequestURI().startsWith("/actuator/");
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
                    "detail":"The ledger operations API requires the platform operations token."}""");
            return;
        }
        chain.doFilter(request, response);
    }
}
