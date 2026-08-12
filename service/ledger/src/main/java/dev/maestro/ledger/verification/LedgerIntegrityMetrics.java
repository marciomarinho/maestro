package dev.maestro.ledger.verification;

import dev.maestro.observability.MetricNames;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.stereotype.Component;

/**
 * Publishes the latest verification verdict, so "the books balance" is a panel rather
 * than a claim.
 *
 * <p>The gauges read {@link BalanceVerifier#latest()} live at scrape time — there is no
 * copy to go stale. {@code accounts.verified} is deliberately included: a drift gauge
 * sitting at zero is only reassuring alongside evidence that verification is actually
 * running, and zero accounts verified is how "it never ran" shows up on the dashboard.
 */
@Component
public class LedgerIntegrityMetrics {

    public LedgerIntegrityMetrics(BalanceVerifier verifier, MeterRegistry meters) {
        Gauge.builder(MetricNames.LEDGER_DRIFT_ACCOUNTS, verifier,
                        v -> v.latest().drifts().size())
                .description("Accounts whose materialised balance disagrees with their postings")
                .register(meters);
        Gauge.builder(MetricNames.LEDGER_CURRENCY_IMBALANCES, verifier,
                        v -> v.latest().currencyImbalances().size())
                .description("Currencies whose postings do not sum to zero")
                .register(meters);
        Gauge.builder(MetricNames.LEDGER_ACCOUNTS_VERIFIED, verifier,
                        v -> v.latest().accountsChecked())
                .description("Accounts covered by the latest verification; zero means it has not run")
                .register(meters);
    }
}
