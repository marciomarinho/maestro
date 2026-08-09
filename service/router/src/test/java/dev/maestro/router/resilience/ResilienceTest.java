package dev.maestro.router.resilience;

import static org.assertj.core.api.Assertions.assertThat;

import dev.maestro.router.RouterProperties;
import dev.maestro.router.TestClock;
import dev.maestro.router.health.CorridorKey;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * The two components that decide when to stop trying.
 *
 * <p>Both exist to bound damage rather than to produce a result, which makes them easy to
 * get subtly wrong and hard to notice: a breaker that never opens and a retry budget that
 * never refuses both look exactly like a healthy system right up until the incident they
 * were built for.
 */
class ResilienceTest {

    private static final CorridorKey SOUTHCROSS = new CorridorKey("southcross", "VISA:AUD");
    private static final CorridorKey NORTHBANK = new CorridorKey("northbank", "VISA:AUD");

    private static RouterProperties properties(
            RouterProperties.Breaker breaker, RouterProperties.RetryBudget budget) {
        return new RouterProperties(
                List.of(), null, null, null, null, null, breaker, budget, "test-ops-token");
    }

    @Nested
    class Breakers {

        private final TestClock clock = TestClock.startingNow();
        private final CircuitBreakers breakers = new CircuitBreakers(
                properties(new RouterProperties.Breaker(3, Duration.ofSeconds(15)), null), clock);

        @Test
        void aCorridorIsAdmittedUntilItHasFailedRepeatedly() {
            assertThat(breakers.admits(SOUTHCROSS)).isTrue();

            breakers.recordFailure(SOUTHCROSS);
            breakers.recordFailure(SOUTHCROSS);
            assertThat(breakers.admits(SOUTHCROSS))
                    .as("two failures is a bad minute, not an outage")
                    .isTrue();

            breakers.recordFailure(SOUTHCROSS);
            assertThat(breakers.stateOf(SOUTHCROSS)).isEqualTo(CircuitBreakers.State.OPEN);
            assertThat(breakers.admits(SOUTHCROSS))
                    .as("cut off entirely — not reduced to the exploration floor")
                    .isFalse();
        }

        @Test
        void oneAnswerResetsTheRunOfFailures() {
            breakers.recordFailure(SOUTHCROSS);
            breakers.recordFailure(SOUTHCROSS);
            breakers.recordAnswer(SOUTHCROSS);
            breakers.recordFailure(SOUTHCROSS);
            breakers.recordFailure(SOUTHCROSS);

            assertThat(breakers.admits(SOUTHCROSS))
                    .as("failures have to be consecutive; intermittent trouble is the "
                            + "health model's business, not the breaker's")
                    .isTrue();
        }

        @Test
        void aBreakerHalfOpensOnItsOwnSoTheExplorationFloorCanProbeIt() {
            open(SOUTHCROSS);

            clock.advance(Duration.ofSeconds(14));
            assertThat(breakers.stateOf(SOUTHCROSS)).isEqualTo(CircuitBreakers.State.OPEN);

            clock.advance(Duration.ofSeconds(2));
            assertThat(breakers.stateOf(SOUTHCROSS))
                    .as("half-open readmits the corridor to selection, where its ruined "
                            + "score earns it exactly the floor — and the floor is the probe")
                    .isEqualTo(CircuitBreakers.State.HALF_OPEN);
            assertThat(breakers.admits(SOUTHCROSS)).isTrue();
        }

        @Test
        void aSuccessfulProbeClosesTheBreaker() {
            open(SOUTHCROSS);
            clock.advance(Duration.ofSeconds(16));

            breakers.recordAnswer(SOUTHCROSS);

            assertThat(breakers.stateOf(SOUTHCROSS)).isEqualTo(CircuitBreakers.State.CLOSED);
        }

        @Test
        void aFailedProbeReopensImmediatelyWithoutSpendingTheThresholdAgain() {
            open(SOUTHCROSS);
            clock.advance(Duration.ofSeconds(16));
            assertThat(breakers.stateOf(SOUTHCROSS)).isEqualTo(CircuitBreakers.State.HALF_OPEN);

            breakers.recordFailure(SOUTHCROSS);

            assertThat(breakers.stateOf(SOUTHCROSS))
                    .as("the corridor has already proved itself once; it does not get to "
                            + "spend another three real payments proving it again")
                    .isEqualTo(CircuitBreakers.State.OPEN);
        }

        @Test
        void breakersAreIndependentPerCorridor() {
            open(SOUTHCROSS);

            assertThat(breakers.admits(SOUTHCROSS)).isFalse();
            assertThat(breakers.admits(NORTHBANK))
                    .as("one acquirer being down says nothing about another")
                    .isTrue();
        }

        private void open(CorridorKey key) {
            for (int i = 0; i < 3; i++) {
                breakers.recordFailure(key);
            }
        }
    }

    @Nested
    class Budget {

        private final TestClock clock = TestClock.startingNow();
        private final RetryBudget budget = new RetryBudget(
                properties(null, new RouterProperties.RetryBudget(0.10, 5, Duration.ofSeconds(10))),
                clock);

        @Test
        void aQuietCorridorCanStillFailOver() {
            // A pure ratio would refuse the very first retry a low-volume corridor asks
            // for, which would mean no failover at all until the platform got busy.
            budget.recordRequest();

            assertThat(budget.tryConsume()).isTrue();
        }

        @Test
        void retriesAreCappedAtAFractionOfRequestVolume() {
            for (int i = 0; i < 100; i++) {
                budget.recordRequest();
                clock.advance(Duration.ofMillis(10));
            }

            // Ten percent of a hundred requests, plus the absolute floor of five.
            int granted = 0;
            while (budget.tryConsume()) {
                granted++;
            }

            assertThat(granted).isBetween(13, 17);
        }

        @Test
        void aTotalOutageCannotMultiplyTheLoadOnTheSurvivingAcquirers() {
            // Every payment fails and wants three attempts. Without a budget that is a
            // threefold amplification aimed at the acquirers now carrying everything.
            int requests = 200;
            int attempted = 0;
            for (int i = 0; i < requests; i++) {
                budget.recordRequest();
                for (int failover = 0; failover < 2 && budget.tryConsume(); failover++) {
                    attempted++;
                }
                clock.advance(Duration.ofMillis(10));
            }

            assertThat(attempted)
                    .as("extra attempts stay near a tenth of request volume, so the "
                            + "surviving acquirers see roughly the traffic they would have "
                            + "seen anyway rather than three times it")
                    .isLessThan(requests / 4);
        }

        @Test
        void theBudgetRefillsAsOlderRetriesAgeOut() {
            for (int i = 0; i < 50; i++) {
                budget.recordRequest();
            }
            while (budget.tryConsume()) {
                // spend it all
            }
            assertThat(budget.tryConsume()).isFalse();

            // Volume and retries both decay, but fresh requests arrive meanwhile.
            for (int i = 0; i < 50; i++) {
                clock.advance(Duration.ofMillis(200));
                budget.recordRequest();
            }

            assertThat(budget.tryConsume())
                    .as("a budget that never refilled would disable failover permanently "
                            + "after the first incident")
                    .isTrue();
        }

        @Test
        void utilisationIsReportableSoTheCeilingIsVisibleBeforeItIsHit() {
            for (int i = 0; i < 100; i++) {
                budget.recordRequest();
            }
            assertThat(budget.utilisation()).isZero();

            budget.tryConsume();
            budget.tryConsume();

            assertThat(budget.utilisation()).isBetween(0.1, 0.2);
        }
    }
}
