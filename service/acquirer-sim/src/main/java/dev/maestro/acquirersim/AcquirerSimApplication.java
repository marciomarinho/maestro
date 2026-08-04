package dev.maestro.acquirersim;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;

/**
 * Simulated acquiring banks.
 *
 * <p>This is a first-class component, not a test fixture (ADR-0011). Real payment
 * sandboxes are built to be reliable, which makes them useless for demonstrating what
 * happens when an acquirer is not — and the central claim of this platform is about
 * exactly that. Owning the acquirer means latency, declines, timeouts, throttling and
 * brownouts can be produced on demand, at any volume, with no credentials.
 *
 * <p>In Phase 1 it approves everything after a fixed delay. The fault-injection API
 * arrives in Phase 3, alongside the routing logic that responds to it.
 */
@SpringBootApplication
@EnableConfigurationProperties(AcquirerSimProperties.class)
public class AcquirerSimApplication {

    public static void main(String[] args) {
        SpringApplication.run(AcquirerSimApplication.class, args);
    }
}
