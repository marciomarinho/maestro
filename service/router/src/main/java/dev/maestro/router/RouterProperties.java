package dev.maestro.router;

import java.time.Duration;
import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * How the router connects to acquirers, and how it forms and acts on opinions about them.
 *
 * <p>What an acquirer <em>costs</em> is deliberately not here: that is a commercial
 * agreement per corridor and lives in the {@code acquirer_corridor} table, where it can
 * change without a deployment. What is here is the shape of the router's judgement, which
 * is a design decision rather than an operational one and belongs under review.
 */
@ConfigurationProperties("maestro.router")
public record RouterProperties(
        List<Acquirer> acquirers,
        Duration requestTimeout,
        Health health,
        Scoring scoring,
        Selection selection,
        Failover failover,
        Breaker breaker,
        RetryBudget retryBudget,
        String opsToken) {

    public RouterProperties {
        acquirers = acquirers == null ? List.of() : List.copyOf(acquirers);
        requestTimeout = requestTimeout == null ? Duration.ofSeconds(5) : requestTimeout;
        health = health == null ? Health.defaults() : health;
        scoring = scoring == null ? Scoring.defaults() : scoring;
        selection = selection == null ? Selection.defaults() : selection;
        failover = failover == null ? Failover.defaults() : failover;
        breaker = breaker == null ? Breaker.defaults() : breaker;
        retryBudget = retryBudget == null ? RetryBudget.defaults() : retryBudget;
        if (opsToken == null || opsToken.isBlank()) {
            throw new IllegalArgumentException("maestro.router.ops-token must be set");
        }
    }

    /** Where an acquirer answers. Identity and an address; nothing evaluative. */
    public record Acquirer(String id, String baseUrl) {
    }

    /**
     * How quickly the router changes its mind, and what it believes before it has evidence.
     *
     * @param halfLife                  how long it takes evidence to lose half its weight.
     *                                  The single responsiveness tunable (ADR-0007). Thirty
     *                                  seconds registers a brownout while it is still
     *                                  happening without reacting to ordinary jitter
     * @param priorApprovalRate         what a corridor is assumed to approve before it has
     *                                  shown otherwise. Set near a plausible real approval
     *                                  rate rather than at 1.0, so a brand-new acquirer is
     *                                  neither shunned nor handed all the traffic
     * @param priorTechnicalFailureRate the availability equivalent, assumed low
     * @param priorWeight               how many observations the prior is worth. Twenty
     *                                  means a corridor needs roughly that many attempts
     *                                  before its own record outweighs the assumption —
     *                                  which is what stops one unlucky failure from
     *                                  evicting a low-traffic corridor
     * @param restoredSampleCap         the most confidence a snapshot read at startup may
     *                                  claim. A restored opinion is a hint, not a
     *                                  conviction; live traffic must be able to overturn
     *                                  it within seconds
     * @param snapshotInterval          how often health is written down, so a restart
     *                                  mid-incident does not begin blind
     */
    public record Health(
            Duration halfLife,
            double priorApprovalRate,
            double priorTechnicalFailureRate,
            double priorWeight,
            double restoredSampleCap,
            Duration snapshotInterval) {

        public static Health defaults() {
            return new Health(Duration.ofSeconds(30), 0.90, 0.02, 20, 10, Duration.ofSeconds(10));
        }

        public Health {
            halfLife = halfLife == null ? Duration.ofSeconds(30) : halfLife;
            snapshotInterval = snapshotInterval == null ? Duration.ofSeconds(10) : snapshotInterval;
            if (halfLife.isNegative() || halfLife.isZero()) {
                throw new IllegalArgumentException("half-life must be positive");
            }
            if (priorWeight < 0 || restoredSampleCap < 0) {
                throw new IllegalArgumentException("prior weight and sample cap cannot be negative");
            }
        }
    }

    /**
     * The relative worth of the four things the router knows about a corridor.
     *
     * <p>The magnitudes carry an argument, and it is the one commercial stakeholders
     * usually want to have. <strong>Availability outweighs cost by three to one.</strong>
     * An acquirer thirty basis points cheaper that fails one request in five is not
     * cheaper — the lost margin on a declined sale dwarfs the saving on the ones that go
     * through, and the customer who was declined may not come back at all.
     *
     * <p>Approval and technical failure are weighted comparably because from a merchant's
     * point of view they are the same event: a payment that did not happen. They are
     * measured separately because from the platform's point of view they are not — one is
     * the issuer's decision and final, the other is an outage and worth retrying elsewhere
     * (ADR-0012).
     *
     * <p>Latency is weighted lowest of the four. It matters — a slow acquirer costs
     * conversions — but a fast decline is worth nothing, so speed only breaks ties between
     * acquirers that are otherwise succeeding.
     */
    public record Scoring(
            double approvalWeight,
            double technicalWeight,
            double latencyWeight,
            double costWeight) {

        public static Scoring defaults() {
            return new Scoring(1.0, 1.2, 0.15, 0.40);
        }
    }

    /**
     * How the scores become a choice.
     *
     * @param explorationFloor the minimum share of traffic every candidate receives,
     *                         however badly it is scoring. <strong>The most important
     *                         number in the router.</strong> A router that always picks
     *                         the best score stops sending traffic to the alternatives,
     *                         so it stops receiving evidence about them, so it can never
     *                         learn that a demoted acquirer has recovered — and it looks
     *                         entirely correct while doing it (ADR-0007). Five percent is
     *                         the price of being able to detect recovery, and it is a
     *                         real cost that belongs in a conversation with a commercial
     *                         stakeholder rather than buried in a constant
     * @param temperature      how sharply score differences turn into traffic
     *                         differences. Lower concentrates traffic on the leader;
     *                         higher spreads it. At 0.15 a tenth of a point of score is
     *                         worth roughly a doubling of share, which keeps the split
     *                         responsive to health without letting rounding noise between
     *                         two equally healthy acquirers swing it
     */
    public record Selection(double explorationFloor, double temperature) {

        public static Selection defaults() {
            return new Selection(0.05, 0.15);
        }

        public Selection {
            if (explorationFloor < 0 || explorationFloor > 0.5) {
                throw new IllegalArgumentException(
                        "exploration floor must be between 0 and 0.5, was " + explorationFloor);
            }
            if (temperature <= 0) {
                throw new IllegalArgumentException("temperature must be positive");
            }
        }
    }

    /**
     * How hard the router tries before giving up on a payment.
     *
     * @param maxAcquirers          how many different acquirers one authorization may be
     *                              offered to. Three is the whole panel here; the number
     *                              exists so that a corridor-wide outage fails a payment
     *                              promptly instead of walking every bank in the country
     * @param sameAcquirerRetries   how many times an <em>unanswered</em> call is repeated
     *                              to the same acquirer with the same idempotency key. A
     *                              timeout means the transaction's fate is unknown, and
     *                              the only safe way to resolve that is to ask the
     *                              institution that might already have authorized it
     *                              (ADR-0012). Never a different one
     * @param backoff               the base delay before a repeat
     * @param maxBackoff            the ceiling on that delay, kept short deliberately:
     *                              the wait happens on the consumer thread, so a long one
     *                              stalls the partition behind it
     */
    public record Failover(
            int maxAcquirers, int sameAcquirerRetries, Duration backoff, Duration maxBackoff) {

        public static Failover defaults() {
            return new Failover(3, 2, Duration.ofMillis(50), Duration.ofMillis(500));
        }

        public Failover {
            backoff = backoff == null ? Duration.ofMillis(50) : backoff;
            maxBackoff = maxBackoff == null ? Duration.ofMillis(500) : maxBackoff;
            if (maxAcquirers < 1) {
                throw new IllegalArgumentException("maxAcquirers must be at least 1");
            }
            if (sameAcquirerRetries < 0) {
                throw new IllegalArgumentException("sameAcquirerRetries cannot be negative");
            }
        }
    }

    /**
     * When to stop asking an acquirer altogether.
     *
     * @param failureThreshold consecutive unanswered calls before the corridor is cut off.
     *                         Consecutive rather than a rate, because the breaker answers
     *                         "is it there" and a rate already has an owner — the health
     *                         model
     * @param openDuration     how long it stays cut off before the exploration floor is
     *                         allowed to probe it again
     */
    public record Breaker(int failureThreshold, Duration openDuration) {

        public static Breaker defaults() {
            return new Breaker(5, Duration.ofSeconds(15));
        }

        public Breaker {
            openDuration = openDuration == null ? Duration.ofSeconds(15) : openDuration;
            if (failureThreshold < 1) {
                throw new IllegalArgumentException("failureThreshold must be at least 1");
            }
        }
    }

    /**
     * The ceiling on retries, as a fraction of request volume.
     *
     * @param ratio           retries permitted per request. Ten percent is the figure
     *                        Google's SRE practice and Finagle both settled on, and the
     *                        reasoning is the same here: it is enough to rescue the
     *                        payments a single acquirer's failure would otherwise lose,
     *                        and far too little to turn that failure into a stampede
     * @param minimumRetries  an absolute allowance on top, so that a corridor doing one
     *                        payment a minute can still fail over. A pure ratio would
     *                        refuse the first retry it was ever asked for
     * @param window          half-life of the volume measurement
     */
    public record RetryBudget(double ratio, double minimumRetries, Duration window) {

        public static RetryBudget defaults() {
            return new RetryBudget(0.10, 5, Duration.ofSeconds(10));
        }

        public RetryBudget {
            window = window == null ? Duration.ofSeconds(10) : window;
            if (ratio < 0 || minimumRetries < 0) {
                throw new IllegalArgumentException("retry budget cannot be negative");
            }
        }
    }
}
