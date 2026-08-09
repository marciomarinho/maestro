package dev.maestro.router.acquirer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.maestro.domain.acquirer.AcquirerOutcome;
import dev.maestro.domain.acquirer.DeclineCode;
import dev.maestro.router.RouterProperties;
import dev.maestro.router.TestClock;
import dev.maestro.router.health.CorridorKey;
import dev.maestro.router.health.HealthRegistry;
import dev.maestro.router.resilience.CircuitBreakers;
import java.math.BigDecimal;
import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;

/**
 * The routing decision, proven against a clock and a die the test controls.
 *
 * <p>These are the properties the whole project exists to demonstrate. They run in
 * milliseconds and assert exact numbers because time and randomness are injected: the
 * alternative — driving real traffic through a container and sleeping — would turn
 * "traffic shifts within thirty seconds" into a bound so loose it proved nothing.
 *
 * <p>The end-to-end integration test proves this is genuinely wired up. This proves it is
 * genuinely right.
 */
class ScoringAcquirerSelectorTest {

    private static final String CORRIDOR = "VISA:AUD";
    private static final long TICKET = 1999L;

    /** The three demo acquiring agreements, exactly as {@code V900__demo_corridors.sql} seeds them. */
    private static final List<AcquirerCorridor> AGREEMENTS = List.of(
            agreement("southcross", "115.00", 25),
            agreement("northbank", "130.00", 30),
            agreement("meridian", "160.00", 20));

    private static final Map<String, Long> HEALTHY_LATENCY =
            Map.of("southcross", 32L, "northbank", 45L, "meridian", 68L);

    private final Random faultDice = new Random(1_234);

    @Test
    void theSteadyStateGoesToTheCheapestHealthyAcquirer() {
        Fixture fixture = fixture(RouterProperties.Selection.defaults());

        Map<String, Double> share = fixture.run(Duration.ofMinutes(3), noFaults());

        assertThat(share.get("southcross"))
                .as("cheapest and fastest, and nothing is wrong with it")
                .isGreaterThan(0.60);
        assertThat(share.get("meridian"))
                .as("dearest, so it earns little — but never nothing")
                .isLessThan(0.25);
    }

    @Test
    void everyCandidateKeepsTheExplorationFloorHoweverBadlyItScores() {
        Fixture fixture = fixture(RouterProperties.Selection.defaults());
        fixture.run(Duration.ofMinutes(2), Map.of("southcross", 1.0));

        Map<String, ScoringAcquirerSelector.Candidate> candidates = fixture.explain();

        assertThat(candidates.get("southcross").score())
                .as("failing every request it is given")
                .isLessThan(candidates.get("northbank").score());
        assertThat(candidates.get("southcross").probability())
                .as("and it still receives the floor, because a candidate that receives "
                        + "nothing produces no evidence about itself")
                .isGreaterThanOrEqualTo(0.05)
                .isLessThan(0.08);
    }

    @Test
    void trafficShiftsAwayFromAnAcquirerThatStartsFailing() {
        Fixture fixture = fixture(RouterProperties.Selection.defaults());
        fixture.run(Duration.ofMinutes(3), noFaults());

        // Measured as the share it is being given *now*, not averaged across the window:
        // an average includes the traffic sent before the router had any reason to
        // suspect anything, and would understate how fast it reacted.
        Duration untilDemoted = fixture.timeUntilShare(
                "southcross", share -> share < 0.10, Duration.ofMinutes(2),
                Map.of("southcross", 1.0));

        assertThat(untilDemoted)
                .as("the bound this phase claims: a brownout costs about a minute of "
                        + "degraded routing, not the tens of minutes a human would take "
                        + "to notice, decide and deploy a configuration change")
                .isLessThanOrEqualTo(Duration.ofSeconds(75));

        Map<String, Double> afterShifting = fixture.run(
                Duration.ofSeconds(30), Map.of("southcross", 1.0));
        assertThat(afterShifting.get("southcross"))
                .as("and it stays demoted, in the traffic actually sent")
                .isLessThan(0.10);
        assertThat(afterShifting.get("northbank") + afterShifting.get("meridian"))
                .as("the traffic went somewhere, rather than nowhere")
                .isGreaterThan(0.90);
    }

    /**
     * Why demotion takes about a minute rather than the half-life.
     *
     * <p>A feedback effect that only shows up once selection and health are wired
     * together: as an acquirer is demoted it receives less traffic, so it produces less
     * evidence about itself, so the reading that demoted it firms up more slowly than the
     * half-life alone would suggest. The system is self-damping, which is a good property
     * — it is also why the demotion bound is stated from measurement rather than derived
     * from the half-life and assumed.
     */
    @Test
    void demotionSlowsItselfDownBecauseADemotedAcquirerProducesLessEvidence() {
        Fixture fixture = fixture(RouterProperties.Selection.defaults());
        fixture.run(Duration.ofMinutes(3), noFaults());

        fixture.run(Duration.ofSeconds(30), Map.of("southcross", 1.0));
        double afterOneHalfLife = fixture.explain().get("southcross").probability();

        assertThat(afterOneHalfLife)
                .as("well down from its steady-state share of roughly three quarters")
                .isLessThan(0.30);
        assertThat(afterOneHalfLife)
                .as("but not yet at the floor, because it has been sent a shrinking "
                        + "fraction of the traffic that would have proved it broken")
                .isGreaterThan(0.08);
    }

    @Test
    void aHealedAcquirerWinsItsTrafficBack() {
        Fixture fixture = fixture(RouterProperties.Selection.defaults());
        fixture.run(Duration.ofMinutes(3), noFaults());
        fixture.run(Duration.ofMinutes(2), Map.of("southcross", 1.0));
        assertThat(fixture.explain().get("southcross").probability()).isLessThan(0.10);

        // The acquirer is healthy again. Nobody tells the router; it has to notice, and
        // what it notices with is the exploration traffic it never stopped sending.
        Duration untilRestored = fixture.timeUntilShare(
                "southcross", share -> share > 0.50, Duration.ofMinutes(5), noFaults());

        assertThat(untilRestored)
                .as("recovery detected and acted on, with no configuration change and "
                        + "nobody being paged")
                .isLessThanOrEqualTo(Duration.ofMinutes(2));
    }

    /**
     * The failure ADR-0007 exists to prevent, measured.
     *
     * <p>A router that always picks the best score sends a demoted acquirer nothing at
     * all. Its evidence then decays with elapsed time — as it should, because stale
     * evidence is not evidence — and after ten minutes there is none of it left. The
     * reading returns to the optimistic prior, and the router arrives at total confidence
     * that a completely broken acquirer is fine.
     *
     * <p>That is worse than the original phrasing of the argument, not better. The concern
     * was that recovery could never be detected; the reality is that recovery is
     * <em>fabricated</em> — the router will eventually route real payments back into a
     * dead acquirer, at full volume, on the strength of having forgotten about it.
     *
     * <p>The exploration floor buys the alternative for five percent of traffic: the
     * reading stays true, and it stays true because it keeps being paid for.
     */
    @Test
    void withoutExplorationTheRouterEndsUpConfidentAndWrong() {
        CorridorKey southcross = new CorridorKey("southcross", CORRIDOR);
        Map<String, Double> broken = Map.of("southcross", 1.0);

        Fixture explorer = fixture(RouterProperties.Selection.defaults());
        explorer.run(Duration.ofMinutes(3), noFaults());
        explorer.run(Duration.ofMinutes(10), broken);

        Fixture argmax = fixture(RouterProperties.Selection.defaults());
        argmax.runAlwaysBest(Duration.ofMinutes(3), noFaults());
        argmax.runAlwaysBest(Duration.ofMinutes(10), broken);

        assertThat(explorer.health.readingFor(southcross).samples())
                .as("the floor keeps evidence arriving, so the opinion stays paid for")
                .isGreaterThan(15);
        assertThat(explorer.health.readingFor(southcross).technicalFailureRate())
                .as("and the opinion is the truth: this acquirer is failing")
                .isGreaterThan(0.40);

        assertThat(argmax.health.readingFor(southcross).samples())
                .as("always picking the best leaves literally no evidence behind")
                .isLessThan(0.5);
        assertThat(argmax.health.readingFor(southcross).technicalFailureRate())
                .as("so the router ends up believing a totally broken acquirer is healthy "
                        + "— not unable to detect recovery, but inventing one")
                .isLessThan(0.05);
    }

    @Test
    void aBusinessDeclineIsNotHeldAgainstAvailabilityButIsHeldAgainstApproval() {
        Fixture fixture = fixture(RouterProperties.Selection.defaults());
        fixture.run(Duration.ofMinutes(3), noFaults());

        Map<String, Double> whileDeclining = fixture.run(
                Duration.ofMinutes(2), Map.of(), Map.of("southcross", 1.0));

        assertThat(whileDeclining.get("southcross"))
                .as("an acquirer whose issuer keeps refusing is worth less traffic, "
                        + "even though it answered every request perfectly")
                .isLessThan(0.35);
        assertThat(fixture.health.readingFor(new CorridorKey("southcross", CORRIDOR))
                .technicalFailureRate())
                .as("but its availability is untouched, so it is demoted rather than broken")
                .isLessThan(0.05);
    }

    @Test
    void theCheapestAcquirerIsNotTheSameOneAtEveryTicketSize() {
        Fixture fixture = fixture(RouterProperties.Selection.defaults());

        Map<String, Long> smallTicket = fixture.costs(500L);
        Map<String, Long> largeTicket = fixture.costs(500_000L);

        assertThat(smallTicket.get("meridian"))
                .as("on a $5 payment the 5c cheaper fixed fee beats 45 basis points")
                .isLessThan(smallTicket.get("southcross"));
        assertThat(largeTicket.get("meridian"))
                .as("on a $5,000 payment the same 45 basis points are $22.50")
                .isGreaterThan(largeTicket.get("southcross"));
    }

    @Test
    void aFailoverIsRecordedAsOneRatherThanAsANormalChoice() {
        Fixture fixture = fixture(RouterProperties.Selection.defaults());

        AcquirerSelector.Selection selection = fixture.selector.select(
                new AcquirerSelector.Request(CORRIDOR, TICKET, Set.of("southcross")));

        assertThat(selection.acquirerId()).isNotEqualTo("southcross");
        assertThat(selection.reason()).isEqualTo(AcquirerSelector.Selection.REASON_FAILOVER);
        assertThat(selection.healthScore()).isNotNull();
    }

    @Test
    void everyAcquirerExcludedMeansThereIsNowhereLeftToGo() {
        Fixture fixture = fixture(RouterProperties.Selection.defaults());

        assertThatThrownBy(() -> fixture.selector.select(new AcquirerSelector.Request(
                CORRIDOR, TICKET, Set.of("southcross", "northbank", "meridian"))))
                .isInstanceOf(AcquirerSelector.NoAcquirerAvailableException.class)
                .hasMessageContaining(CORRIDOR);
    }

    @Test
    void aCorridorNobodyServesIsRefusedRatherThanRoutedSomewhereWrong() {
        Fixture fixture = fixture(RouterProperties.Selection.defaults());

        assertThatThrownBy(() -> fixture.selector.select(
                AcquirerSelector.Request.first("AMEX:USD", TICKET)))
                .isInstanceOf(AcquirerSelector.NoAcquirerAvailableException.class);
    }

    // --- fixture ------------------------------------------------------------

    /**
     * Each fixture gets its own clock.
     *
     * <p>Shared, two scenarios in one test would run consecutively on the same timeline,
     * and the first one's health would be read thirteen minutes after its last
     * observation — fully decayed, and quietly proving nothing.
     */
    private Fixture fixture(RouterProperties.Selection selection) {
        TestClock clock = TestClock.startingNow();
        RouterProperties properties = new RouterProperties(
                AGREEMENTS.stream()
                        .map(a -> new RouterProperties.Acquirer(a.acquirerId(), "http://sim"))
                        .toList(),
                null,
                RouterProperties.Health.defaults(),
                RouterProperties.Scoring.defaults(),
                selection,
                null,
                null,
                null,
                "test-ops-token");
        HealthRegistry health = new HealthRegistry(properties, clock);
        Random selectionDice = new Random(20_260_809L);
        return new Fixture(
                clock,
                health,
                new ScoringAcquirerSelector(
                        corridor -> CORRIDOR.equals(corridor) ? AGREEMENTS : List.of(),
                        health,
                        new CircuitBreakers(properties, clock),
                        properties,
                        selectionDice::nextDouble));
    }

    /**
     * A miniature of the real loop: choose an acquirer, get an outcome from it, feed the
     * outcome back into health, repeat. Ten requests a second, which is what the brownout
     * demo drives.
     */
    private final class Fixture {

        private final TestClock clock;
        private final HealthRegistry health;
        private final ScoringAcquirerSelector selector;

        private Fixture(TestClock clock, HealthRegistry health, ScoringAcquirerSelector selector) {
            this.clock = clock;
            this.health = health;
            this.selector = selector;
        }

        Map<String, Double> run(Duration duration, Map<String, Double> technicalFailureRates) {
            return run(duration, technicalFailureRates, Map.of(), false);
        }

        Map<String, Double> run(
                Duration duration,
                Map<String, Double> technicalFailureRates,
                Map<String, Double> declineRates) {
            return run(duration, technicalFailureRates, declineRates, false);
        }

        /**
         * The same traffic, routed by always choosing the highest score.
         *
         * <p>Not a configuration of this selector — deliberately. Setting the exploration
         * floor to zero still leaves a softmax, which has an exponential tail and so keeps
         * trickling traffic to the loser anyway. The design this is contrasted against is
         * the one people actually write: pick the best, every time.
         */
        Map<String, Double> runAlwaysBest(
                Duration duration, Map<String, Double> technicalFailureRates) {
            return run(duration, technicalFailureRates, Map.of(), true);
        }

        private Map<String, Double> run(
                Duration duration,
                Map<String, Double> technicalFailureRates,
                Map<String, Double> declineRates,
                boolean alwaysBest) {

            Map<String, Integer> chosen = new HashMap<>();
            int steps = (int) (duration.toMillis() / 100);
            for (int i = 0; i < steps; i++) {
                AcquirerSelector.Request request =
                        AcquirerSelector.Request.first(CORRIDOR, TICKET);
                String acquirerId = alwaysBest
                        ? selector.explain(request).stream()
                                .max(java.util.Comparator.comparingDouble(
                                        ScoringAcquirerSelector.Candidate::score))
                                .orElseThrow()
                                .acquirerId()
                        : selector.select(request).acquirerId();
                chosen.merge(acquirerId, 1, Integer::sum);

                double roll = faultDice.nextDouble();
                if (roll < technicalFailureRates.getOrDefault(acquirerId, 0.0)) {
                    health.record(
                            new CorridorKey(acquirerId, CORRIDOR),
                            new AcquirerOutcome.TechnicalFailure("ISSUER_UNAVAILABLE", "down"),
                            300);
                } else if (roll < declineRates.getOrDefault(acquirerId, 0.0)) {
                    health.record(
                            new CorridorKey(acquirerId, CORRIDOR),
                            new AcquirerOutcome.BusinessDecline(DeclineCode.DO_NOT_HONOUR, "no"),
                            HEALTHY_LATENCY.get(acquirerId));
                } else {
                    health.record(
                            new CorridorKey(acquirerId, CORRIDOR),
                            new AcquirerOutcome.Approved("ref", "000000"),
                            HEALTHY_LATENCY.get(acquirerId));
                }
                clock.advance(Duration.ofMillis(100));
            }

            return chosen.entrySet().stream().collect(Collectors.toUnmodifiableMap(
                    Map.Entry::getKey, e -> (double) e.getValue() / steps));
        }

        /**
         * How long until an acquirer's share of traffic crosses a threshold.
         *
         * <p>The quantity that actually matters operationally, and the one every claim in
         * this phase is really about: not "does it react" but "how fast".
         */
        Duration timeUntilShare(
                String acquirerId,
                java.util.function.DoublePredicate crossed,
                Duration limit,
                Map<String, Double> technicalFailureRates) {

            for (long second = 1; second <= limit.toSeconds(); second++) {
                run(Duration.ofSeconds(1), technicalFailureRates);
                if (crossed.test(explain().get(acquirerId).probability())) {
                    return Duration.ofSeconds(second);
                }
            }
            return limit.plusSeconds(1);
        }

        Map<String, ScoringAcquirerSelector.Candidate> explain() {
            return selector.explain(AcquirerSelector.Request.first(CORRIDOR, TICKET)).stream()
                    .collect(Collectors.toUnmodifiableMap(
                            ScoringAcquirerSelector.Candidate::acquirerId, Function.identity()));
        }

        Map<String, Long> costs(long amountMinor) {
            return selector.explain(AcquirerSelector.Request.first(CORRIDOR, amountMinor)).stream()
                    .collect(Collectors.toUnmodifiableMap(
                            ScoringAcquirerSelector.Candidate::acquirerId,
                            ScoringAcquirerSelector.Candidate::costMinor));
        }
    }

    private static Map<String, Double> noFaults() {
        return Map.of();
    }

    private static AcquirerCorridor agreement(String acquirerId, String costBps, long fixedFee) {
        return new AcquirerCorridor(
                acquirerId, CORRIDOR, new BigDecimal(costBps), fixedFee, true);
    }
}
