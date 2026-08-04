package dev.maestro.acquirersim;

import java.time.Duration;
import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * The acquirers this instance impersonates.
 *
 * <p>Phase 1 models only the two properties the walking skeleton needs: identity and
 * response latency. Decline rates, timeout rates, throughput caps and brownout modes
 * are added in Phase 3, when there is routing logic for them to exercise.
 */
@ConfigurationProperties("maestro.acquirer-sim")
public record AcquirerSimProperties(List<Acquirer> acquirers) {

    public AcquirerSimProperties {
        acquirers = acquirers == null ? List.of() : List.copyOf(acquirers);
    }

    /**
     * @param id      stable identifier used by the router and recorded on every attempt
     * @param name    display name
     * @param latency simulated round-trip time to the issuer
     */
    public record Acquirer(String id, String name, Duration latency) {

        public Acquirer {
            latency = latency == null ? Duration.ofMillis(50) : latency;
        }
    }
}
