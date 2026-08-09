package dev.maestro.router;

import dev.maestro.router.acquirer.Dice;
import java.time.Clock;
import java.util.concurrent.ThreadLocalRandom;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;

/**
 * The heart of the platform: it decides which acquiring bank processes each payment.
 *
 * <p>In Phase 1 there is one acquirer and the decision is trivial, but the shape is
 * already the one Phase 3 needs — attempts are numbered and recorded with the reason
 * they were made, acquirer calls carry deterministic idempotency keys, and outcomes are
 * published through an outbox rather than fired at Kafka and hoped for.
 *
 * <p>It runs as its own process because its failure domain and scaling axis differ from
 * the API's: a hung acquirer or a consumer-group rebalance must not touch merchant-
 * facing availability (ADR-0014).
 */
@SpringBootApplication
@EnableConfigurationProperties(RouterProperties.class)
public class RouterApplication {

    public static void main(String[] args) {
        SpringApplication.run(RouterApplication.class, args);
    }

    /**
     * Time, as a dependency.
     *
     * <p>Health scoring is a function of elapsed time, so every claim about how fast the
     * router reacts and how fast it recovers is a claim about time. Injecting the clock is
     * what lets those claims be tested in milliseconds against a clock the test advances,
     * rather than by sleeping and hoping — which is the difference between a test that
     * proves the half-life and a test that is merely slow.
     */
    @Bean
    Clock clock() {
        return Clock.systemUTC();
    }

    /**
     * Randomness, as a dependency, for the same reason as the clock.
     *
     * <p>Drawn per thread so that thousands of concurrent authorizations do not contend
     * on one generator's internal state — the routing decision is on the hot path of
     * every payment in the platform.
     */
    @Bean
    Dice dice() {
        return () -> ThreadLocalRandom.current().nextDouble();
    }
}
