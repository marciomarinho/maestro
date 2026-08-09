package dev.maestro.acquirersim.api;

import dev.maestro.acquirersim.AcquirerSimulator;
import dev.maestro.acquirersim.Behaviour;
import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Min;
import java.time.Duration;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Breaks acquirers on purpose.
 *
 * <p>This endpoint is why the resilience claims in this project can be demonstrated rather
 * than described. A reviewer runs the brownout demo, watches an acquirer's health collapse
 * and the traffic move, and heals it again — from a terminal, against a running system.
 *
 * <p>The presets are the two failures worth showing. {@code /brownout} is the dangerous one:
 * the acquirer keeps answering, keeps passing every health probe, and burns most of the
 * traffic it is given. That is the case a static routing table with health-check failover
 * cannot see, and the reason ADR-0007 exists.
 *
 * <p>Deliberately unauthenticated and deliberately on the simulator: nothing here exists in
 * a real acquirer, and confining it to the component that is explicitly a simulation
 * (ADR-0011) keeps a fault-injection surface out of every service that handles money.
 */
@RestController
@RequestMapping("/admin/acquirers")
public class AdminController {

    private final AcquirerSimulator simulator;

    public AdminController(AcquirerSimulator simulator) {
        this.simulator = simulator;
    }

    @GetMapping("/{acquirerId}/behaviour")
    public BehaviourView behaviour(@PathVariable String acquirerId) {
        return BehaviourView.of(simulator.behaviourOf(acquirerId));
    }

    /** Sets behaviour exactly. What the deterministic scenarios in the test suite use. */
    @PutMapping("/{acquirerId}/behaviour")
    public BehaviourView set(
            @PathVariable String acquirerId, @Valid @RequestBody BehaviourRequest request) {
        return BehaviourView.of(simulator.degrade(acquirerId, request.toBehaviour()));
    }

    /** Degraded but still answering, and still passing every health check. */
    @PostMapping("/{acquirerId}/brownout")
    public BehaviourView brownout(@PathVariable String acquirerId) {
        return BehaviourView.of(
                simulator.degrade(acquirerId, simulator.behaviourOf(acquirerId).brownout()));
    }

    /** Hard down. */
    @PostMapping("/{acquirerId}/blackout")
    public BehaviourView blackout(@PathVariable String acquirerId) {
        return BehaviourView.of(
                simulator.degrade(acquirerId, simulator.behaviourOf(acquirerId).blackout()));
    }

    /** Back to the configured behaviour — the half of the demo that proves recovery. */
    @PostMapping("/{acquirerId}/heal")
    public BehaviourView heal(@PathVariable String acquirerId) {
        return BehaviourView.of(simulator.heal(acquirerId));
    }

    /**
     * What may be set.
     *
     * <p>Separate from the response type because {@code healthy} is derived — it is a
     * reading of the other fields, not an instruction — and a request record containing it
     * would both invite callers to set a contradiction and, since it is a primitive,
     * reject every request that sensibly omitted it.
     *
     * <p>Durations are milliseconds rather than ISO-8601 so the demo script and a curl by
     * hand read the same way as the log line they produce.
     */
    public record BehaviourRequest(
            @Min(0) long latencyMs,
            @Min(0) long latencyJitterMs,
            @DecimalMin("0.0") @DecimalMax("1.0") double declineRate,
            @DecimalMin("0.0") @DecimalMax("1.0") double technicalFailureRate,
            @DecimalMin("0.0") @DecimalMax("1.0") double timeoutRate,
            @Min(0) int maxInFlight) {

        Behaviour toBehaviour() {
            return new Behaviour(
                    Duration.ofMillis(latencyMs),
                    Duration.ofMillis(latencyJitterMs),
                    declineRate,
                    technicalFailureRate,
                    timeoutRate,
                    maxInFlight);
        }
    }

    /** What is reported back, including the derived healthy flag. */
    public record BehaviourView(
            long latencyMs,
            long latencyJitterMs,
            double declineRate,
            double technicalFailureRate,
            double timeoutRate,
            int maxInFlight,
            boolean healthy) {

        static BehaviourView of(Behaviour behaviour) {
            return new BehaviourView(
                    behaviour.latency().toMillis(),
                    behaviour.latencyJitter().toMillis(),
                    behaviour.declineRate(),
                    behaviour.technicalFailureRate(),
                    behaviour.timeoutRate(),
                    behaviour.maxInFlight(),
                    behaviour.isHealthy());
        }
    }
}
