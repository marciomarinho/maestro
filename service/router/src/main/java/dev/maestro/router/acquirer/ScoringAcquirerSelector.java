package dev.maestro.router.acquirer;

import dev.maestro.router.RouterProperties;
import dev.maestro.router.health.CorridorHealth;
import dev.maestro.router.health.CorridorKey;
import dev.maestro.router.health.HealthRegistry;
import dev.maestro.router.resilience.CircuitBreakers;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.List;
import java.util.OptionalDouble;
import java.util.Set;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * The routing decision.
 *
 * <p>Scores every acquirer that can serve the corridor, turns the scores into a
 * distribution, and draws from it. Two properties matter more than the arithmetic:
 *
 * <ol>
 *   <li><strong>It draws rather than picks.</strong> Every candidate that is not
 *       circuit-broken keeps a guaranteed minimum share of traffic. This is the whole
 *       argument of ADR-0007. A router that always chooses the best score stops sending
 *       traffic to the alternatives, which means it stops receiving evidence about them,
 *       which means it can never discover that a demoted acquirer has recovered. It
 *       converges on whichever acquirer was healthiest at the moment of demotion and
 *       stays there permanently, silently, and looking entirely correct.</li>
 *   <li><strong>Latency and cost are compared, not measured.</strong> Both are normalised
 *       across the candidates actually available for this payment, so the score answers
 *       "dearer or slower than the alternatives" rather than "dear or slow" — a question
 *       with no fixed answer, since 200ms is excellent for one corridor and terrible for
 *       another.</li>
 * </ol>
 *
 * <p>Approval and technical-failure rates are not normalised, because they are already
 * absolute and comparable: 0.94 approval means the same thing everywhere.
 */
@Component
public class ScoringAcquirerSelector implements AcquirerSelector {

    private static final Logger log = LoggerFactory.getLogger(ScoringAcquirerSelector.class);

    private final CorridorCatalogue corridors;
    private final HealthRegistry health;
    private final RouterProperties.Scoring weights;
    private final RouterProperties.Selection tuning;
    private final CircuitBreakers breakers;
    private final Set<String> reachable;
    private final Dice dice;

    public ScoringAcquirerSelector(
            CorridorCatalogue corridors,
            HealthRegistry health,
            CircuitBreakers breakers,
            RouterProperties properties,
            Dice dice) {
        this.corridors = corridors;
        this.health = health;
        this.breakers = breakers;
        this.weights = properties.scoring();
        this.tuning = properties.selection();
        this.dice = dice;
        this.reachable = properties.acquirers().stream()
                .map(RouterProperties.Acquirer::id)
                .collect(Collectors.toUnmodifiableSet());
    }

    @Override
    public Selection select(Request request) {
        List<Candidate> candidates = explain(request);
        if (candidates.isEmpty()) {
            throw new NoAcquirerAvailableException(
                    "No acquirer available for corridor %s (excluded: %s)"
                            .formatted(request.corridor(), request.excludedAcquirerIds()));
        }

        Candidate chosen = draw(candidates);
        String reason = reasonFor(request, candidates, chosen);

        log.debug("corridor={} chose={} score={} probability={} reason={}",
                request.corridor(), chosen.acquirerId(), chosen.score(),
                chosen.probability(), reason);

        return new Selection(chosen.acquirerId(), reason, round(chosen.score()));
    }

    @Override
    public boolean hasCandidate(Request request) {
        return !explain(request).isEmpty();
    }

    /**
     * Every candidate with its score and its share of traffic.
     *
     * <p>Exposed rather than kept private because "why did this payment go to Northbank?"
     * should be answerable by looking, and because the brownout demo's whole value is
     * that a viewer can watch these numbers move. It is also what the traffic-shift and
     * recovery tests assert against, which means the thing on the screen and the thing
     * under test are the same thing.
     */
    public List<Candidate> explain(Request request) {
        List<AcquirerCorridor> available = corridors.candidatesFor(request.corridor()).stream()
                .filter(corridor -> !request.excludedAcquirerIds().contains(corridor.acquirerId()))
                // A corridor row for an acquirer this router has no address for is a
                // misconfiguration, not a candidate. Better to route around it than to
                // fail every payment on the corridor.
                .filter(corridor -> reachable.contains(corridor.acquirerId()))
                // An open breaker removes the corridor outright, exploration floor and
                // all. This is the one place traffic is cut to zero rather than reduced,
                // and it is why recovery afterwards needs the half-open transition
                // rather than merely a better score.
                .filter(corridor -> breakers.admits(
                        new CorridorKey(corridor.acquirerId(), request.corridor())))
                .toList();
        if (available.isEmpty()) {
            return List.of();
        }

        List<Assessment> assessments = available.stream()
                .map(corridor -> new Assessment(
                        corridor,
                        health.readingFor(new CorridorKey(corridor.acquirerId(), request.corridor())),
                        corridor.costMinorFor(request.amountMinor())))
                .toList();

        double[] scores = score(assessments);
        double[] probabilities = distribute(scores);

        List<Candidate> candidates = new ArrayList<>(assessments.size());
        for (int i = 0; i < assessments.size(); i++) {
            Assessment assessment = assessments.get(i);
            candidates.add(new Candidate(
                    assessment.corridor().acquirerId(),
                    scores[i],
                    probabilities[i],
                    assessment.reading(),
                    assessment.costMinor()));
        }
        return List.copyOf(candidates);
    }

    // --- scoring ------------------------------------------------------------

    /**
     * The formula from ADR-0007, applied to this payment's candidates.
     *
     * <pre>
     * score = w_approval  × approval_rate
     *       + w_technical × (1 − technical_failure_rate)
     *       − w_latency   × normalised_latency
     *       − w_cost      × normalised_cost
     * </pre>
     */
    private double[] score(List<Assessment> assessments) {
        double[] latencies = normalised(assessments.stream()
                .map(a -> a.reading().latencyMillis())
                .toList());
        double[] costs = normalised(assessments.stream()
                .map(a -> OptionalDouble.of(a.costMinor()))
                .toList());

        double[] scores = new double[assessments.size()];
        for (int i = 0; i < assessments.size(); i++) {
            CorridorHealth.Reading reading = assessments.get(i).reading();
            scores[i] = weights.approvalWeight() * reading.approvalRate()
                    + weights.technicalWeight() * (1 - reading.technicalFailureRate())
                    - weights.latencyWeight() * latencies[i]
                    - weights.costWeight() * costs[i];
        }
        return scores;
    }

    /**
     * Scales values onto {@code [0, 1]} against the best and worst of this candidate set.
     *
     * <p>An absent value — a corridor that has not answered yet, so has no latency —
     * lands at the midpoint of the others rather than at either end. Zero would make an
     * unmeasured acquirer look like the fastest on the panel and hand it traffic it has
     * done nothing to earn; one would make it look like the slowest and guarantee it never
     * gets measured. The midpoint says what is actually true, which is that nobody knows.
     */
    private static double[] normalised(List<OptionalDouble> values) {
        double min = values.stream().filter(OptionalDouble::isPresent)
                .mapToDouble(OptionalDouble::getAsDouble).min().orElse(0);
        double max = values.stream().filter(OptionalDouble::isPresent)
                .mapToDouble(OptionalDouble::getAsDouble).max().orElse(0);
        double range = max - min;

        double[] normalised = new double[values.size()];
        for (int i = 0; i < values.size(); i++) {
            OptionalDouble value = values.get(i);
            if (value.isEmpty()) {
                normalised[i] = 0.5;
            } else if (range <= 0) {
                // Every candidate identical on this dimension, so it decides nothing.
                normalised[i] = 0;
            } else {
                normalised[i] = (value.getAsDouble() - min) / range;
            }
        }
        return normalised;
    }

    /**
     * Turns scores into shares of traffic: a softmax, then the exploration floor.
     *
     * <p>The floor is applied by reserving {@code n × floor} of the distribution and
     * spreading only what remains by score. Every candidate therefore receives at least
     * the floor exactly, regardless of how badly it is scoring — which is the guarantee,
     * rather than an emergent property that a sufficiently bad score could erode.
     */
    private double[] distribute(double[] scores) {
        int n = scores.length;
        // With enough candidates the floors alone would exceed the whole distribution;
        // at that point every candidate is exploration and the split is uniform.
        double floor = Math.min(tuning.explorationFloor(), 1.0 / n);
        double reserved = floor * n;

        double highest = java.util.Arrays.stream(scores).max().orElse(0);
        double[] weighted = new double[n];
        double total = 0;
        for (int i = 0; i < n; i++) {
            // Shifted by the maximum before exponentiating: without it a handful of
            // points of score overflows to infinity and the distribution becomes NaN.
            weighted[i] = Math.exp((scores[i] - highest) / tuning.temperature());
            total += weighted[i];
        }

        double[] probabilities = new double[n];
        for (int i = 0; i < n; i++) {
            probabilities[i] = floor + (1 - reserved) * (weighted[i] / total);
        }
        return probabilities;
    }

    private Candidate draw(List<Candidate> candidates) {
        double roll = dice.roll();
        double cumulative = 0;
        for (Candidate candidate : candidates) {
            cumulative += candidate.probability();
            if (roll < cumulative) {
                return candidate;
            }
        }
        // Only reachable through floating-point accumulation landing a hair short of 1.
        return candidates.getLast();
    }

    private static String reasonFor(Request request, List<Candidate> candidates, Candidate chosen) {
        if (request.isFailover()) {
            return Selection.REASON_FAILOVER;
        }
        Candidate best = candidates.stream()
                .max(java.util.Comparator.comparingDouble(Candidate::score))
                .orElseThrow();
        return chosen.acquirerId().equals(best.acquirerId())
                ? Selection.REASON_BEST_SCORE
                : Selection.REASON_EXPLORATION;
    }

    private static BigDecimal round(double score) {
        return BigDecimal.valueOf(score).setScale(4, RoundingMode.HALF_UP);
    }

    private record Assessment(
            AcquirerCorridor corridor, CorridorHealth.Reading reading, long costMinor) {
    }

    /**
     * One acquirer's case for this payment, and what share of traffic it wins.
     *
     * @param probability its share under the current scores, floor included
     * @param costMinor   what routing this payment here would cost, at this amount
     */
    public record Candidate(
            String acquirerId,
            double score,
            double probability,
            CorridorHealth.Reading health,
            long costMinor) {
    }
}
