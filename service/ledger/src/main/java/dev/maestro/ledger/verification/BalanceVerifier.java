package dev.maestro.ledger.verification;

import dev.maestro.ledger.core.LedgerQueries;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Proves the books, rather than assuming them.
 *
 * <p>Materialised balances are an optimisation, and an optimisation that can silently
 * diverge from the truth is a liability in a ledger. This recomputes every balance from
 * raw postings and compares, and separately checks that all postings in each currency sum
 * to zero — the cheapest whole-ledger integrity check there is.
 *
 * <p>Drift is not an SLO with an error budget. Any non-zero result is an incident, which
 * is why the runbook for it exists before the first occurrence rather than after.
 */
@Component
public class BalanceVerifier {

    private static final Logger log = LoggerFactory.getLogger(BalanceVerifier.class);

    private final LedgerQueries queries;
    private volatile VerificationResult latest = VerificationResult.notYetRun();

    public BalanceVerifier(LedgerQueries queries) {
        this.queries = queries;
    }

    @Scheduled(
            fixedDelayString = "${maestro.ledger.verification-interval:5m}",
            initialDelayString = "${maestro.ledger.verification-interval:5m}")
    public void scheduledVerification() {
        verify();
    }

    @Transactional(readOnly = true)
    public VerificationResult verify() {
        Map<String, Long> materialised = queries.balances().stream()
                .collect(Collectors.toMap(
                        LedgerQueries.BalanceView::accountId, LedgerQueries.BalanceView::balanceMinor));
        Map<String, LedgerQueries.BalanceView> recomputed = queries.recomputedBalances().stream()
                .collect(Collectors.toMap(LedgerQueries.BalanceView::accountId, Function.identity()));

        List<Drift> drifts = new ArrayList<>();
        recomputed.forEach((accountId, actual) -> {
            long stored = materialised.getOrDefault(accountId, 0L);
            if (stored != actual.balanceMinor()) {
                drifts.add(new Drift(accountId, stored, actual.balanceMinor(), actual.currency()));
            }
        });
        // An account with a materialised balance but no postings at all is drift too, and
        // would otherwise be invisible to the loop above.
        materialised.forEach((accountId, stored) -> {
            if (!recomputed.containsKey(accountId) && stored != 0L) {
                drifts.add(new Drift(accountId, stored, 0L, null));
            }
        });

        List<LedgerQueries.GlobalImbalance> imbalances = queries.globalImbalance();

        VerificationResult result = new VerificationResult(
                true, drifts, imbalances, recomputed.size());
        latest = result;

        if (result.isClean()) {
            log.debug("Ledger verified: {} accounts, no drift", recomputed.size());
        } else {
            // Deliberately loud. See docs/operations/runbooks/ledger-drift.md.
            log.error(
                    "LEDGER INTEGRITY FAILURE: {} account(s) drifted, {} currency imbalance(s). {}",
                    drifts.size(), imbalances.size(), result.summary());
        }
        return result;
    }

    public VerificationResult latest() {
        return latest;
    }

    /**
     * @param accountId       the account that disagrees
     * @param storedMinor     what the materialised balance claims
     * @param recomputedMinor what the postings actually sum to
     */
    public record Drift(String accountId, long storedMinor, long recomputedMinor, String currency) {

        public long differenceMinor() {
            return storedMinor - recomputedMinor;
        }
    }

    public record VerificationResult(
            boolean hasRun,
            List<Drift> drifts,
            List<LedgerQueries.GlobalImbalance> currencyImbalances,
            int accountsChecked) {

        static VerificationResult notYetRun() {
            return new VerificationResult(false, List.of(), List.of(), 0);
        }

        public boolean isClean() {
            return drifts.isEmpty() && currencyImbalances.isEmpty();
        }

        public String summary() {
            if (!hasRun) {
                return "not yet run";
            }
            if (isClean()) {
                return "clean across %d accounts".formatted(accountsChecked);
            }
            return "drifts=%s imbalances=%s".formatted(drifts, currencyImbalances);
        }
    }
}
