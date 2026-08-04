package dev.maestro.payment;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * The merchant-facing front door, and the owner of payment intent.
 *
 * <p>It accepts and validates instructions, enforces idempotency and tenant scoping,
 * owns the payment state machine, and writes commands to the transactional outbox in
 * the same transaction as the state change. It never talks to an acquirer and never
 * posts to the ledger — both would put merchant-facing availability at the mercy of a
 * downstream system (ADR-0014).
 */
@SpringBootApplication
public class PaymentApiApplication {

    public static void main(String[] args) {
        SpringApplication.run(PaymentApiApplication.class, args);
    }
}
