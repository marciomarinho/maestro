package dev.maestro.router;

import java.time.Duration;
import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Acquirer connectivity.
 *
 * <p>Phase 1 configures acquirers here rather than in a database table. Cost, capacity
 * and per-corridor health arrive in Phase 3, and the table arrives with them — a table
 * with three unused columns would read as something abandoned rather than something
 * not yet needed.
 */
@ConfigurationProperties("maestro.router")
public record RouterProperties(List<Acquirer> acquirers, Duration requestTimeout) {

    public RouterProperties {
        acquirers = acquirers == null ? List.of() : List.copyOf(acquirers);
        requestTimeout = requestTimeout == null ? Duration.ofSeconds(5) : requestTimeout;
    }

    public record Acquirer(String id, String baseUrl) {
    }
}
