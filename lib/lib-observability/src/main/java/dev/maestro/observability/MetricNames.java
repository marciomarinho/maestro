package dev.maestro.observability;

/**
 * Every custom metric name in the platform, in one place.
 *
 * <p>A metric name is an interface: dashboards, alerts and runbooks all couple to it,
 * and none of them are compiled against this code. Declaring the names here makes an
 * ad-hoc metric a code-review failure and a rename a deliberate, searchable act rather
 * than a silent dashboard outage.
 *
 * <p>Convention: {@code maestro.<owner>.<noun>[.<property>]}, lower-case dotted;
 * Micrometer renders the Prometheus form ({@code maestro_router_attempts_total}).
 * Dimensions go in tags ({@link MetricTags}), never in the name — a name that embeds an
 * acquirer cannot be summed across acquirers.
 */
public final class MetricNames {

    // --- Payments funnel (payment-api). Counter, tagged by TRANSITION. ---
    public static final String PAYMENT_TRANSITIONS = "maestro.payment.transitions";

    // --- Routing (router) ---
    /** Counter of acquirer attempts, tagged by ACQUIRER, CORRIDOR, OPERATION, OUTCOME. */
    public static final String ROUTER_ATTEMPTS = "maestro.router.attempts";
    /** Timer around the acquirer call, tagged by ACQUIRER and OPERATION. */
    public static final String ROUTER_ACQUIRER_LATENCY = "maestro.router.acquirer.latency";

    // --- Corridor health gauges (router), tagged by ACQUIRER and CORRIDOR ---
    public static final String ROUTER_CORRIDOR_APPROVAL_RATE = "maestro.router.corridor.approval.rate";
    public static final String ROUTER_CORRIDOR_TECHNICAL_FAILURE_RATE = "maestro.router.corridor.technical.failure.rate";
    public static final String ROUTER_CORRIDOR_LATENCY = "maestro.router.corridor.latency";
    public static final String ROUTER_CORRIDOR_SAMPLES = "maestro.router.corridor.samples";
    /** 0 = closed, 1 = half-open, 2 = open. */
    public static final String ROUTER_CORRIDOR_BREAKER = "maestro.router.corridor.breaker";
    public static final String ROUTER_RETRY_BUDGET_UTILISATION = "maestro.router.retry.budget.utilisation";

    // --- Outbox (every service that owns one). Gauges. ---
    /** Rows not yet published. Sustained growth means the relay is not keeping up. */
    public static final String OUTBOX_PENDING = "maestro.outbox.pending";
    /** Age in seconds of the oldest unpublished row. The signal for a stalled relay. */
    public static final String OUTBOX_OLDEST_AGE = "maestro.outbox.oldest.age";

    // --- Dead-letter queue (router). Gauge over the DLQ topic's end offsets. ---
    public static final String DLQ_DEPTH = "maestro.dlq.depth";

    // --- Ledger integrity (ledger). Gauges over the latest verification. ---
    /** Accounts whose materialised balance disagrees with their postings. Any value above zero is an incident. */
    public static final String LEDGER_DRIFT_ACCOUNTS = "maestro.ledger.drift.accounts";
    /** Currencies whose postings do not sum to zero. Any value above zero is an incident. */
    public static final String LEDGER_CURRENCY_IMBALANCES = "maestro.ledger.currency.imbalances";
    /** Accounts covered by the latest verification. Zero means verification has not run. */
    public static final String LEDGER_ACCOUNTS_VERIFIED = "maestro.ledger.accounts.verified";

    private MetricNames() {
    }
}
