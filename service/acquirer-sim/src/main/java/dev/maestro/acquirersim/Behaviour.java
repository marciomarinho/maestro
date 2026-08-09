package dev.maestro.acquirersim;

import java.time.Duration;

/**
 * How an acquirer is behaving right now.
 *
 * <p>This is the fault-injection surface, and it is the reason the routing claims in this
 * project are demonstrable rather than asserted. Every knob corresponds to a way real
 * acquirers actually degrade — and, deliberately, to a signal the router's health model
 * consumes: declines feed approval rate, technical failures and timeouts feed the
 * availability signal, latency feeds the latency signal, and the in-flight cap produces
 * the throttling that a capacity-blind router would keep walking into.
 *
 * <p>A {@code Behaviour} is immutable; degrading an acquirer replaces it wholesale, so a
 * reader of {@link AcquirerSimulator} never has to reason about a half-applied change.
 *
 * @param latency              base round trip to the issuer
 * @param latencyJitter        uniform additional delay, so latency has a spread rather
 *                             than a single value that no real network produces
 * @param declineRate          fraction of operations the issuer refuses — a
 *                             <em>business</em> outcome, final everywhere (ADR-0012)
 * @param technicalFailureRate fraction that fail in the acquirer's systems, so nothing
 *                             was decided and another acquirer may legitimately be tried
 * @param timeoutRate          fraction that never answer. Simulated by hanging past any
 *                             sane deadline rather than by returning an error, because a
 *                             timeout the client detects and a failure the server reports
 *                             are different events and the router treats them differently
 * @param maxInFlight          concurrent operations accepted before refusing on capacity
 *                             grounds; zero means unlimited
 */
public record Behaviour(
        Duration latency,
        Duration latencyJitter,
        double declineRate,
        double technicalFailureRate,
        double timeoutRate,
        int maxInFlight) {

    /** Long enough that any client deadline fires first. The client defines the timeout. */
    public static final Duration HANG = Duration.ofSeconds(30);

    public Behaviour {
        latency = latency == null ? Duration.ofMillis(50) : latency;
        latencyJitter = latencyJitter == null ? Duration.ZERO : latencyJitter;
        declineRate = requireFraction(declineRate, "declineRate");
        technicalFailureRate = requireFraction(technicalFailureRate, "technicalFailureRate");
        timeoutRate = requireFraction(timeoutRate, "timeoutRate");
        if (maxInFlight < 0) {
            throw new IllegalArgumentException("maxInFlight cannot be negative");
        }
    }

    /** A healthy acquirer with the given round-trip time. */
    public static Behaviour healthy(Duration latency) {
        return new Behaviour(latency, Duration.ZERO, 0, 0, 0, 0);
    }

    /**
     * Degraded but answering: most requests fail in the acquirer's systems and the ones
     * that survive are slow.
     *
     * <p>This is the mode that defeats health-check failover, and the reason ADR-0007
     * rejects it. The simulator still returns {@code 200 OK} to {@code /actuator/health}
     * throughout — a probe sees a healthy acquirer while live traffic is burning.
     */
    public Behaviour brownout() {
        return new Behaviour(
                latency.multipliedBy(6), latency.multipliedBy(4), declineRate, 0.60, 0.05, maxInFlight);
    }

    /** Hard down: nothing gets an answer that means anything. */
    public Behaviour blackout() {
        return new Behaviour(latency, Duration.ZERO, 0, 1.0, 0, maxInFlight);
    }

    /** True while nothing is being injected, so callers can report the plain case plainly. */
    public boolean isHealthy() {
        return declineRate == 0 && technicalFailureRate == 0 && timeoutRate == 0;
    }

    private static double requireFraction(double value, String name) {
        if (value < 0 || value > 1) {
            throw new IllegalArgumentException(name + " must be between 0 and 1, was " + value);
        }
        return value;
    }
}
