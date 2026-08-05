package dev.maestro.payment.core;

import dev.maestro.domain.money.Money;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * Refunds.
 *
 * <p>A refund is its own resource with its own identifier, not a flag on the payment,
 * because it is a separate money movement: it has its own acquirer call, its own outcome
 * and its own ledger postings, and a payment may have several.
 */
@Repository
public class RefundRepository {

    private final JdbcClient jdbc;

    public RefundRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public void insert(Refund refund) {
        jdbc.sql("""
                INSERT INTO refund (id, payment_id, merchant_id, amount_minor, currency, reason, status)
                VALUES (:id, :paymentId, :merchantId, :amount, :currency, :reason, :status)
                """)
                .param("id", refund.id())
                .param("paymentId", refund.paymentId())
                .param("merchantId", refund.merchantId())
                .param("amount", refund.amountMinor())
                .param("currency", refund.currency())
                .param("reason", refund.reason())
                .param("status", refund.status())
                .update();
    }

    public Optional<Refund> find(String merchantId, String refundId) {
        return jdbc.sql("SELECT * FROM refund WHERE id = :id AND merchant_id = :merchantId")
                .param("id", refundId)
                .param("merchantId", merchantId)
                .query(RefundRepository::mapRefund)
                .optional();
    }

    public List<Refund> findByPayment(String merchantId, String paymentId) {
        return jdbc.sql("""
                SELECT * FROM refund
                 WHERE payment_id = :paymentId AND merchant_id = :merchantId
                 ORDER BY created_at
                """)
                .param("paymentId", paymentId)
                .param("merchantId", merchantId)
                .query(RefundRepository::mapRefund)
                .list();
    }

    /** Guarded, so a redelivered outcome event settles a refund at most once. */
    public int markSucceeded(String refundId, String acquirerReference) {
        return jdbc.sql("""
                UPDATE refund
                   SET status = 'SUCCEEDED', acquirer_reference = :reference, updated_at = now()
                 WHERE id = :id AND status = 'PENDING'
                """)
                .param("id", refundId)
                .param("reference", acquirerReference)
                .update();
    }

    public int markFailed(String refundId, String reason) {
        return jdbc.sql("""
                UPDATE refund
                   SET status = 'FAILED', failure_reason = :reason, updated_at = now()
                 WHERE id = :id AND status = 'PENDING'
                """)
                .param("id", refundId)
                .param("reason", reason)
                .update();
    }

    private static Refund mapRefund(java.sql.ResultSet rs, int rowNum) throws java.sql.SQLException {
        return new Refund(
                rs.getString("id"),
                rs.getString("payment_id"),
                rs.getString("merchant_id"),
                rs.getLong("amount_minor"),
                rs.getString("currency"),
                rs.getString("reason"),
                rs.getString("status"),
                rs.getString("acquirer_reference"),
                rs.getString("failure_reason"),
                rs.getTimestamp("created_at").toInstant());
    }

    public record Refund(
            String id,
            String paymentId,
            String merchantId,
            long amountMinor,
            String currency,
            String reason,
            String status,
            String acquirerReference,
            String failureReason,
            Instant createdAt) {

        public static final String PENDING = "PENDING";

        public Money amount() {
            return Money.of(amountMinor, currency);
        }
    }
}
