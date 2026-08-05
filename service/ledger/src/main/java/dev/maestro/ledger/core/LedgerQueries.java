package dev.maestro.ledger.core;

import dev.maestro.domain.money.Money;
import java.util.List;
import java.util.Optional;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/** Reads over the books, for refund pricing, the verification job and the ops endpoints. */
@Repository
public class LedgerQueries {

    private final JdbcClient jdbc;

    public LedgerQueries(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * What has been captured for a payment, and what the platform charged for it.
     *
     * <p>Derived from the postings rather than stored separately, so it cannot disagree
     * with the books. Summing across transactions handles partial captures: two captures
     * of $10 price a later refund exactly as one capture of $20 would.
     */
    public Optional<CaptureTotals> captureTotals(String paymentId) {
        return jdbc.sql("""
                SELECT t.currency,
                       COALESCE(SUM(CASE WHEN a.kind = 'ACQUIRER_RECEIVABLE' AND p.direction = 'DEBIT'
                                         THEN p.amount_minor ELSE 0 END), 0) AS gross_minor,
                       COALESCE(SUM(CASE WHEN a.kind = 'PLATFORM_FEE_REVENUE' AND p.direction = 'CREDIT'
                                         THEN p.amount_minor ELSE 0 END), 0) AS fee_minor
                  FROM journal_transaction t
                  JOIN posting p ON p.transaction_id = t.id
                  JOIN account a ON a.id = p.account_id
                 WHERE t.payment_id = :paymentId
                   AND t.transaction_type = 'CAPTURE'
                 GROUP BY t.currency
                """)
                .param("paymentId", paymentId)
                .query((rs, rowNum) -> new CaptureTotals(
                        Money.of(rs.getLong("gross_minor"), rs.getString("currency")),
                        Money.of(rs.getLong("fee_minor"), rs.getString("currency"))))
                .optional();
    }

    /** Every transaction recorded against a payment, newest last, with its postings. */
    public List<TransactionView> transactionsForPayment(String paymentId) {
        List<TransactionView> transactions = jdbc.sql("""
                SELECT id, source_event_id, transaction_type, currency, occurred_at, recorded_at
                  FROM journal_transaction
                 WHERE payment_id = :paymentId
                 ORDER BY recorded_at, id
                """)
                .param("paymentId", paymentId)
                .query((rs, rowNum) -> new TransactionView(
                        rs.getString("id"),
                        rs.getString("source_event_id"),
                        rs.getString("transaction_type"),
                        rs.getString("currency"),
                        rs.getTimestamp("recorded_at").toInstant(),
                        List.of()))
                .list();

        return transactions.stream()
                .map(transaction -> new TransactionView(
                        transaction.id(),
                        transaction.sourceEventId(),
                        transaction.type(),
                        transaction.currency(),
                        transaction.recordedAt(),
                        postingsFor(transaction.id())))
                .toList();
    }

    public List<PostingView> postingsFor(String transactionId) {
        return jdbc.sql("""
                SELECT account_id, direction, amount_minor, currency
                  FROM posting
                 WHERE transaction_id = :transactionId
                 ORDER BY direction DESC, account_id
                """)
                .param("transactionId", transactionId)
                .query((rs, rowNum) -> new PostingView(
                        rs.getString("account_id"),
                        rs.getString("direction"),
                        rs.getLong("amount_minor"),
                        rs.getString("currency")))
                .list();
    }

    /** Materialised balances, debit-positive. */
    public List<BalanceView> balances() {
        return jdbc.sql("""
                SELECT b.account_id, a.kind, a.merchant_id, b.balance_minor, b.currency, b.posting_count
                  FROM account_balance b
                  JOIN account a ON a.id = b.account_id
                 ORDER BY b.account_id
                """)
                .query((rs, rowNum) -> new BalanceView(
                        rs.getString("account_id"),
                        rs.getString("kind"),
                        rs.getString("merchant_id"),
                        rs.getLong("balance_minor"),
                        rs.getString("currency"),
                        rs.getLong("posting_count")))
                .list();
    }

    /**
     * Recomputes balances from raw postings.
     *
     * <p>Deliberately does not read {@code account_balance} — the whole point is to derive
     * the answer independently so the two can be compared.
     */
    public List<BalanceView> recomputedBalances() {
        return jdbc.sql("""
                SELECT p.account_id,
                       a.kind,
                       a.merchant_id,
                       SUM(CASE WHEN p.direction = 'DEBIT' THEN p.amount_minor
                                ELSE -p.amount_minor END) AS balance_minor,
                       p.currency,
                       count(*) AS posting_count
                  FROM posting p
                  JOIN account a ON a.id = p.account_id
                 GROUP BY p.account_id, a.kind, a.merchant_id, p.currency
                 ORDER BY p.account_id
                """)
                .query((rs, rowNum) -> new BalanceView(
                        rs.getString("account_id"),
                        rs.getString("kind"),
                        rs.getString("merchant_id"),
                        rs.getLong("balance_minor"),
                        rs.getString("currency"),
                        rs.getLong("posting_count")))
                .list();
    }

    /**
     * The sum of every posting ever made, per currency.
     *
     * <p>In a double-entry system this must be exactly zero. It is the cheapest possible
     * whole-ledger integrity check: one query, and a non-zero answer means something is
     * badly wrong regardless of which account it happened in.
     */
    public List<GlobalImbalance> globalImbalance() {
        return jdbc.sql("""
                SELECT currency,
                       SUM(CASE WHEN direction = 'DEBIT' THEN amount_minor
                                ELSE -amount_minor END) AS imbalance_minor
                  FROM posting
                 GROUP BY currency
                HAVING SUM(CASE WHEN direction = 'DEBIT' THEN amount_minor
                                ELSE -amount_minor END) <> 0
                """)
                .query((rs, rowNum) -> new GlobalImbalance(
                        rs.getString("currency"), rs.getLong("imbalance_minor")))
                .list();
    }

    public record CaptureTotals(Money gross, Money fee) {
    }

    public record TransactionView(
            String id,
            String sourceEventId,
            String type,
            String currency,
            java.time.Instant recordedAt,
            List<PostingView> postings) {
    }

    public record PostingView(String accountId, String direction, long amountMinor, String currency) {
    }

    public record BalanceView(
            String accountId,
            String kind,
            String merchantId,
            long balanceMinor,
            String currency,
            long postingCount) {
    }

    public record GlobalImbalance(String currency, long imbalanceMinor) {
    }
}
