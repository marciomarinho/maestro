package dev.maestro.router.web;

import dev.maestro.router.acquirer.AcquirerSelector;
import dev.maestro.router.acquirer.ScoringAcquirerSelector;
import dev.maestro.router.health.CorridorHealth;
import dev.maestro.router.health.CorridorKey;
import dev.maestro.router.health.HealthRegistry;
import dev.maestro.router.resilience.CircuitBreakers;
import dev.maestro.router.resilience.RetryBudget;
import java.util.Comparator;
import java.util.List;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * What the router currently thinks, and what it would do about it.
 *
 * <p>Written for two readers. An operator following the <em>acquirer brownout</em> runbook
 * needs to see whether the router has noticed yet, and a viewer of the brownout demo needs
 * to watch the numbers move — without either of them having to read a Prometheus scrape or
 * take the interesting part on trust.
 *
 * <p>Read-only, and deliberately so. There is no endpoint here to force traffic to an
 * acquirer or to close a breaker by hand. The manual override that does exist is the
 * {@code enabled} flag on {@code acquirer_corridor}, which is a deliberate commercial act
 * recorded in a table, not a button that resets when the process restarts.
 */
@RestController
@RequestMapping("/ops/routing")
public class RoutingOpsController {

    /** The ticket size the table is priced at when the caller does not say. */
    private static final long DEFAULT_AMOUNT_MINOR = 1999L;

    private final ScoringAcquirerSelector selector;
    private final HealthRegistry health;
    private final CircuitBreakers breakers;
    private final RetryBudget retryBudget;

    public RoutingOpsController(
            ScoringAcquirerSelector selector,
            HealthRegistry health,
            CircuitBreakers breakers,
            RetryBudget retryBudget) {
        this.selector = selector;
        this.health = health;
        this.breakers = breakers;
        this.retryBudget = retryBudget;
    }

    /**
     * The decision the router would make right now, and the reasoning behind it.
     *
     * <p>Priced at a ticket size because the answer depends on one: a fixed fee dominates a
     * basis-point spread on small payments and is irrelevant on large ones, so "which
     * acquirer is cheapest" has no answer until somebody names an amount.
     */
    @GetMapping("/corridors/{corridor}")
    public CorridorView corridor(
            @PathVariable String corridor,
            @RequestParam(name = "amount_minor", required = false) Long amountMinor) {

        long amount = amountMinor == null ? DEFAULT_AMOUNT_MINOR : amountMinor;
        List<CandidateView> candidates = selector
                .explain(AcquirerSelector.Request.first(corridor, amount))
                .stream()
                .map(candidate -> new CandidateView(
                        candidate.acquirerId(),
                        candidate.score(),
                        candidate.probability(),
                        candidate.costMinor(),
                        candidate.health().approvalRate(),
                        candidate.health().technicalFailureRate(),
                        candidate.health().latencyMillis().orElse(0),
                        candidate.health().samples(),
                        breakers.stateOf(new CorridorKey(candidate.acquirerId(), corridor)).name()))
                .sorted(Comparator.comparingDouble(CandidateView::probability).reversed())
                .toList();

        return new CorridorView(corridor, amount, candidates);
    }

    /**
     * Every corridor the router has an opinion about.
     *
     * <p>Includes corridors whose breaker is open, which the corridor view above cannot
     * show — an open breaker removes the acquirer from the candidate list entirely, so the
     * one thing an operator most wants to see would be the one thing missing.
     */
    @GetMapping("/health")
    public List<HealthView> health() {
        return health.readAll().entrySet().stream()
                .map(entry -> {
                    CorridorHealth.Reading reading = entry.getValue();
                    return new HealthView(
                            entry.getKey().acquirerId(),
                            entry.getKey().corridor(),
                            reading.approvalRate(),
                            reading.technicalFailureRate(),
                            reading.latencyMillis().orElse(0),
                            reading.samples(),
                            breakers.stateOf(entry.getKey()).name());
                })
                .sorted(Comparator.comparing(HealthView::corridor).thenComparing(HealthView::acquirerId))
                .toList();
    }

    /** How close failover is to being refused. */
    @GetMapping("/retry-budget")
    public RetryBudgetView retryBudget() {
        return new RetryBudgetView(retryBudget.utilisation());
    }

    public record CorridorView(String corridor, long amountMinor, List<CandidateView> candidates) {
    }

    /**
     * @param probability the share of traffic this acquirer would receive right now,
     *                    exploration floor included
     * @param costMinor   what routing this payment here would cost, at this amount
     * @param samples     effective observations behind the reading. A number near zero
     *                    means the router is deciding on assumption rather than evidence
     */
    public record CandidateView(
            String acquirerId,
            double score,
            double probability,
            long costMinor,
            double approvalRate,
            double technicalFailureRate,
            double latencyMs,
            double samples,
            String breaker) {
    }

    public record HealthView(
            String acquirerId,
            String corridor,
            double approvalRate,
            double technicalFailureRate,
            double latencyMs,
            double samples,
            String breaker) {
    }

    public record RetryBudgetView(double utilisation) {
    }
}
