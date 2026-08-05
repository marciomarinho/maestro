package dev.maestro.ledger.core;

import dev.maestro.domain.id.Ids;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

/**
 * Writes journal transactions.
 *
 * <p>Everything a transaction touches — the transaction row, its postings, the accounts
 * they reference and the materialised balances — commits together. The balance check is a
 * deferred constraint trigger, so it runs at that commit: postings are legitimately
 * unbalanced while the rows are going in, and unbalanced at the end is impossible.
 */
@Repository
public class LedgerRepository {

    private static final Logger log = LoggerFactory.getLogger(LedgerRepository.class);

    private final JdbcClient jdbc;

    public LedgerRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * Records an entry, unless the event that produced it has already been recorded.
     *
     * <p>Idempotency is the unique constraint on {@code source_event_id} — claimed with
     * {@code ON CONFLICT DO NOTHING} rather than by catching the violation, because a
     * raised constraint error would abort the surrounding transaction and leave nothing to
     * do but roll back and re-read.
     *
     * @return true if this call recorded the entry; false if it was already recorded
     */
    @Transactional
    public boolean record(JournalEntry entry) {
        String transactionId = Ids.generate("txn");

        int claimed = jdbc.sql("""
                INSERT INTO journal_transaction
                    (id, source_event_id, transaction_type, payment_id, reference, currency, occurred_at)
                VALUES (:id, :sourceEventId, :type, :paymentId, :reference, :currency, :occurredAt)
                ON CONFLICT (source_event_id) DO NOTHING
                """)
                .param("id", transactionId)
                .param("sourceEventId", entry.sourceEventId())
                .param("type", entry.type().name())
                .param("paymentId", entry.paymentId())
                .param("reference", entry.reference())
                .param("currency", entry.currency().getCurrencyCode())
                .param("occurredAt", java.sql.Timestamp.from(entry.occurredAt()))
                .update();

        if (claimed == 0) {
            log.debug("Event {} already recorded; skipping replay", entry.sourceEventId());
            return false;
        }

        for (JournalEntry.Line line : entry.lines()) {
            ensureAccountExists(line.account());
            insertPosting(transactionId, line);
            applyToBalance(line);
        }

        log.info(
                "transaction={} type={} payment={} postings={}",
                transactionId, entry.type(), entry.paymentId(), entry.lines().size());
        return true;
    }

    /** Accounts are created on first use; their identifiers are derived, not allocated. */
    private void ensureAccountExists(AccountRef account) {
        jdbc.sql("""
                INSERT INTO account
                    (id, kind, account_type, normal_balance, merchant_id, acquirer_id, currency)
                VALUES (:id, :kind, :type, :normalBalance, :merchantId, :acquirerId, :currency)
                ON CONFLICT (id) DO NOTHING
                """)
                .param("id", account.id())
                .param("kind", account.kind().name())
                .param("type", account.kind().type().name())
                .param("normalBalance", account.kind().normalBalance().name())
                .param("merchantId", account.merchantId())
                .param("acquirerId", account.acquirerId())
                .param("currency", account.currency().getCurrencyCode())
                .update();
    }

    private void insertPosting(String transactionId, JournalEntry.Line line) {
        jdbc.sql("""
                INSERT INTO posting (id, transaction_id, account_id, direction, amount_minor, currency)
                VALUES (:id, :transactionId, :accountId, :direction, :amount, :currency)
                """)
                .param("id", Ids.generate("pst"))
                .param("transactionId", transactionId)
                .param("accountId", line.account().id())
                .param("direction", line.direction().name())
                .param("amount", line.amount().amountMinor())
                .param("currency", line.amount().currency().getCurrencyCode())
                .update();
    }

    /**
     * Applies the posting to the materialised balance.
     *
     * <p>Stored debit-positive, so a credit subtracts. This is an optimisation and is
     * treated as one: {@code BalanceVerifier} recomputes from raw postings and reports any
     * divergence rather than trusting this arithmetic.
     */
    private void applyToBalance(JournalEntry.Line line) {
        jdbc.sql("""
                INSERT INTO account_balance (account_id, balance_minor, currency, posting_count)
                VALUES (:accountId, :delta, :currency, 1)
                ON CONFLICT (account_id) DO UPDATE
                   SET balance_minor = account_balance.balance_minor + EXCLUDED.balance_minor,
                       posting_count = account_balance.posting_count + 1,
                       updated_at    = now()
                """)
                .param("accountId", line.account().id())
                .param("delta", line.direction().signed(line.amount().amountMinor()))
                .param("currency", line.amount().currency().getCurrencyCode())
                .update();
    }
}
