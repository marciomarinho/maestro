package dev.maestro.router.resilience;

import dev.maestro.router.RouterProperties;
import java.time.Clock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Caps retries as a fraction of request volume.
 *
 * <p>This is the component that stops an incident from becoming an outage, and the failure
 * it prevents is worth stating plainly because it is counter-intuitive: <strong>retrying is
 * most dangerous exactly when it is most tempting.</strong>
 *
 * <p>Without a budget, a total failure at one acquirer multiplies every payment on its
 * corridors by the maximum attempt count. The surviving acquirers — the ones now carrying
 * all of the real traffic — are handed a multiple of it, at the one moment they have the
 * least headroom to absorb anything. A single acquirer's outage becomes a platform-wide
 * overload, caused entirely by the platform's own recovery logic.
 *
 * <p>The budget is a ratio rather than a fixed rate because the safe number of retries is
 * a property of how much traffic there is: ten retries a second is nothing at ten thousand
 * requests a second and is a stampede at twenty. Following the approach used in Google's
 * SRE practice and in Finagle, with a small absolute floor so that low-volume traffic can
 * still fail over at all — a ratio alone would deny the very first retry a quiet corridor
 * ever needs.
 *
 * <p>Volume is measured with the same time-decayed counting the health model uses, rather
 * than a bucket that resets: a fixed window lets a burst arriving just after a reset spend
 * the whole budget at once.
 */
@Component
public class RetryBudget {

    private static final Logger log = LoggerFactory.getLogger(RetryBudget.class);

    private final RouterProperties.RetryBudget config;
    private final Clock clock;
    private final DecayingCount requests;
    private final DecayingCount retries;

    public RetryBudget(RouterProperties properties, Clock clock) {
        this.config = properties.retryBudget();
        this.clock = clock;
        double halfLifeMillis = config.window().toMillis();
        this.requests = new DecayingCount(halfLifeMillis);
        this.retries = new DecayingCount(halfLifeMillis);
    }

    /** Counts one payment arriving. Every payment, whether or not it ends up retried. */
    public void recordRequest() {
        requests.add(clock.millis());
    }

    /**
     * Asks for permission to make one extra attempt.
     *
     * <p>Refusal is not an error. It means this payment fails now, on one acquirer's
     * outage, so that the platform does not fail every payment on all of them.
     *
     * @return true if the retry may proceed, having been counted against the budget
     */
    public boolean tryConsume() {
        long now = clock.millis();
        double allowed = requests.at(now) * config.ratio() + config.minimumRetries();
        if (retries.at(now) >= allowed) {
            log.warn("Retry budget exhausted: {} retries against {} requests, ceiling {}",
                    Math.round(retries.at(now)), Math.round(requests.at(now)), Math.round(allowed));
            return false;
        }
        retries.add(now);
        return true;
    }

    /** For metrics: what fraction of the ceiling is currently spent. */
    public double utilisation() {
        long now = clock.millis();
        double allowed = requests.at(now) * config.ratio() + config.minimumRetries();
        return allowed <= 0 ? 0 : retries.at(now) / allowed;
    }

    /**
     * A count that fades, so recent volume is what governs.
     *
     * <p>The same mechanism as the health model's moving average, reduced to its weight
     * term: exponential decay is what makes "requests in the recent past" a number that
     * needs no window boundary, and therefore has no boundary to game.
     */
    private static final class DecayingCount {

        private static final double LN_2 = Math.log(2);

        private final double halfLifeMillis;
        private double count;
        private long lastUpdateMillis;
        private boolean started;

        private DecayingCount(double halfLifeMillis) {
            this.halfLifeMillis = halfLifeMillis;
        }

        synchronized void add(long nowMillis) {
            count = at(nowMillis) + 1;
            lastUpdateMillis = nowMillis;
            started = true;
        }

        synchronized double at(long nowMillis) {
            if (!started) {
                return 0;
            }
            long elapsed = nowMillis - lastUpdateMillis;
            return elapsed <= 0 ? count : count * Math.exp(-LN_2 * elapsed / halfLifeMillis);
        }
    }
}
