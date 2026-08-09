package dev.maestro.router.resilience;

import dev.maestro.router.RouterProperties;
import dev.maestro.router.health.CorridorKey;
import java.time.Clock;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * A circuit breaker per acquirer-corridor.
 *
 * <p>Health scoring already moves traffic away from an acquirer that is failing, so it is
 * worth being clear about what the breaker adds. Scoring is proportional and gradual: a
 * badly scoring corridor keeps the exploration floor, which is exactly right while the
 * question is <em>how good</em> an acquirer is. The breaker answers a different question —
 * <em>is it there at all</em> — and answers it absolutely. An acquirer refusing every
 * connection should receive nothing, not five percent, because five percent of a large
 * volume is a lot of customers and none of those requests can succeed.
 *
 * <p>The interaction with exploration is the interesting part, and ADR-0007 specifies it:
 * <strong>an open breaker suppresses exploration entirely, and half-open is what restores
 * it.</strong> There is no separate probing mechanism, and there does not need to be —
 * half-open puts the corridor back in the candidate set, where its ruined score earns it
 * precisely the exploration floor. The floor <em>is</em> the probe. One success closes the
 * breaker; one failure opens it again for another interval.
 *
 * <p>State is per instance, like health, and for the same reason.
 */
@Component
public class CircuitBreakers {

    private static final Logger log = LoggerFactory.getLogger(CircuitBreakers.class);

    private final RouterProperties.Breaker config;
    private final Clock clock;
    private final ConcurrentHashMap<CorridorKey, Breaker> breakers = new ConcurrentHashMap<>();

    public CircuitBreakers(RouterProperties properties, Clock clock) {
        this.config = properties.breaker();
        this.clock = clock;
    }

    /** Whether this corridor may be given a request at all. */
    public boolean admits(CorridorKey key) {
        return stateOf(key) != State.OPEN;
    }

    public State stateOf(CorridorKey key) {
        return breakerFor(key).stateAt(clock.millis(), config.openDuration());
    }

    /**
     * The acquirer answered — approval or decline, it makes no difference here.
     *
     * <p>A breaker tracks reachability, not agreement. Opening one because an issuer keeps
     * declining would cut off an acquirer that is working perfectly, and would do it
     * fastest for the merchant with the riskiest customers.
     */
    public void recordAnswer(CorridorKey key) {
        breakerFor(key).onAnswer(key);
    }

    /** The acquirer did not answer, or refused on capacity grounds. */
    public void recordFailure(CorridorKey key) {
        breakerFor(key).onFailure(key, clock.millis(), config);
    }

    public Map<CorridorKey, State> states() {
        long now = clock.millis();
        return breakers.entrySet().stream()
                .collect(Collectors.toUnmodifiableMap(
                        Map.Entry::getKey, e -> e.getValue().stateAt(now, config.openDuration())));
    }

    private Breaker breakerFor(CorridorKey key) {
        return breakers.computeIfAbsent(key, k -> new Breaker());
    }

    public enum State {
        /** Requests flow normally. */
        CLOSED,
        /** The corridor is excluded from selection entirely. */
        OPEN,
        /** Readmitted to selection, where the exploration floor supplies the probe. */
        HALF_OPEN
    }

    /**
     * One breaker.
     *
     * <p>Synchronised rather than lock-free: the transitions are a short interlocking
     * sequence, and a compare-and-swap version of them would be a page of code defending
     * against interleavings that a monitor rules out in one word.
     */
    private static final class Breaker {

        private State state = State.CLOSED;
        private int consecutiveFailures;
        private long openedAtMillis;

        /**
         * The state now, having first let the clock do its work.
         *
         * <p>The OPEN-to-HALF_OPEN transition happens on read rather than on a timer.
         * There is nothing for a timer to wake up and do — the transition only matters at
         * the moment somebody asks whether this corridor may take a request — and a
         * scheduled task per breaker would be a thread pool sized by the number of
         * acquirer-corridor pairs, to change a field.
         */
        synchronized State stateAt(long nowMillis, Duration openFor) {
            if (state == State.OPEN && nowMillis - openedAtMillis >= openFor.toMillis()) {
                state = State.HALF_OPEN;
                consecutiveFailures = 0;
                log.info("breaker half-open after {}; the exploration floor is now the probe", openFor);
            }
            return state;
        }

        synchronized void onAnswer(CorridorKey key) {
            consecutiveFailures = 0;
            if (state != State.CLOSED) {
                log.info("breaker={} closing after a successful probe", key);
                state = State.CLOSED;
            }
        }

        synchronized void onFailure(CorridorKey key, long nowMillis, RouterProperties.Breaker config) {
            consecutiveFailures++;
            if (state == State.HALF_OPEN) {
                // The probe failed. Straight back to open, without waiting to accumulate
                // the threshold again — the corridor has already proved itself once.
                open(key, nowMillis, "probe failed");
                return;
            }
            if (state == State.CLOSED && consecutiveFailures >= config.failureThreshold()) {
                open(key, nowMillis, consecutiveFailures + " consecutive failures");
            }
        }

        private void open(CorridorKey key, long nowMillis, String why) {
            state = State.OPEN;
            openedAtMillis = nowMillis;
            log.warn("breaker={} opening: {}", key, why);
        }
    }
}
