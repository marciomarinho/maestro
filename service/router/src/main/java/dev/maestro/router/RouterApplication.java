package dev.maestro.router;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;

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
}
