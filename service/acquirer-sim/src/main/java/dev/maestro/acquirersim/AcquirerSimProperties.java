package dev.maestro.acquirersim;

import java.time.Duration;
import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * The acquirers this instance impersonates, and the behaviour each starts with.
 *
 * <p>Configuration sets the <em>initial</em> behaviour only. From then on it is runtime
 * state, changed through the admin API, because the entire point of the brownout demo is
 * that an acquirer degrades while the platform is running — a restart to change a
 * property would let the router forget everything it had learned, which is precisely the
 * thing under test.
 */
@ConfigurationProperties("maestro.acquirer-sim")
public record AcquirerSimProperties(List<Acquirer> acquirers) {

    public AcquirerSimProperties {
        acquirers = acquirers == null ? List.of() : List.copyOf(acquirers);
    }

    /**
     * @param id        stable identifier used by the router and recorded on every attempt
     * @param name      display name
     * @param latency   simulated round-trip time to the issuer when healthy
     * @param behaviour optional starting faults; healthy when absent, which is what every
     *                  acquirer configured for the demo starts as
     */
    public record Acquirer(String id, String name, Duration latency, Behaviour behaviour) {

        public Acquirer {
            latency = latency == null ? Duration.ofMillis(50) : latency;
            behaviour = behaviour == null ? Behaviour.healthy(latency) : behaviour;
        }
    }
}
