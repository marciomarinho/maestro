package dev.maestro.ledger;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * The authoritative record of money.
 *
 * <p>It consumes payment events and turns them into double-entry postings. It is the only
 * component permitted to write them, and it runs as its own process for that reason: its
 * database role holds {@code SELECT} and {@code INSERT} on postings and nothing else, and
 * that guarantee is materially weaker when the API's connection pool lives in the same
 * JVM (ADR-0014, ADR-0016).
 *
 * <p>It publishes nothing in this phase, so it has no outbox — that arrives in Phase 5
 * with reconciliation, and scaffolding it now would be a component pretending to exist.
 */
@SpringBootApplication
@EnableConfigurationProperties(LedgerProperties.class)
@EnableScheduling
public class LedgerApplication {

    public static void main(String[] args) {
        SpringApplication.run(LedgerApplication.class, args);
    }
}
