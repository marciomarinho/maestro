package dev.maestro.ledger.hold;

import dev.maestro.domain.money.Money;
import java.time.Instant;
import java.util.Optional;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * Authorization holds.
 *
 * <p>A hold is a reservation, not a movement, so it produces no postings (ADR-0008). It is
 * tracked because the ledger still needs to know what has been promised — an authorization
 * that is never captured or released would otherwise be invisible.
 *
 * <p>Every mutation is a guarded conditional update predicated on the current status, so a
 * redelivered event changes zero rows instead of releasing a hold twice.
 */
@Repository
public class HoldRepository {

    private final JdbcClient jdbc;

    public HoldRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * Places a hold, or does nothing if one already exists for this payment.
     *
     * <p>The payment identifier is the primary key, which makes placement naturally
     * idempotent without a deduplication table.
     */
    public boolean place(
            String paymentId,
            String merchantId,
            String acquirerId,
            Money amount,
            Instant expiresAt) {
        return jdbc.sql("""
                INSERT INTO hold (payment_id, merchant_id, acquirer_id, amount_minor, currency,
                                  status, expires_at)
                VALUES (:paymentId, :merchantId, :acquirerId, :amount, :currency, 'ACTIVE', :expiresAt)
                ON CONFLICT (payment_id) DO NOTHING
                """)
                .param("paymentId", paymentId)
                .param("merchantId", merchantId)
                .param("acquirerId", acquirerId)
                .param("amount", amount.amountMinor())
                .param("currency", amount.currency().getCurrencyCode())
                .param("expiresAt", expiresAt == null ? null : java.sql.Timestamp.from(expiresAt))
                .update() == 1;
    }

    /** Marks the hold consumed by a capture. */
    public boolean consume(String paymentId) {
        return transition(paymentId, "CAPTURED");
    }

    /** Releases the hold because the authorization was voided or lapsed. */
    public boolean release(String paymentId, HoldStatus status) {
        return transition(paymentId, status.name());
    }

    private boolean transition(String paymentId, String toStatus) {
        return jdbc.sql("""
                UPDATE hold
                   SET status = :status, updated_at = now()
                 WHERE payment_id = :paymentId AND status = 'ACTIVE'
                """)
                .param("paymentId", paymentId)
                .param("status", toStatus)
                .update() == 1;
    }

    public Optional<Hold> find(String paymentId) {
        return jdbc.sql("""
                SELECT payment_id, merchant_id, acquirer_id, amount_minor, currency, status, expires_at
                  FROM hold WHERE payment_id = :paymentId
                """)
                .param("paymentId", paymentId)
                .query((rs, rowNum) -> new Hold(
                        rs.getString("payment_id"),
                        rs.getString("merchant_id"),
                        rs.getString("acquirer_id"),
                        Money.of(rs.getLong("amount_minor"), rs.getString("currency")),
                        HoldStatus.valueOf(rs.getString("status")),
                        rs.getTimestamp("expires_at") == null
                                ? null
                                : rs.getTimestamp("expires_at").toInstant()))
                .optional();
    }

    public long countActive() {
        return jdbc.sql("SELECT count(*) FROM hold WHERE status = 'ACTIVE'")
                .query(Long.class)
                .single();
    }

    public record Hold(
            String paymentId,
            String merchantId,
            String acquirerId,
            Money amount,
            HoldStatus status,
            Instant expiresAt) {
    }

    public enum HoldStatus {
        ACTIVE,
        CAPTURED,
        RELEASED,
        EXPIRED
    }
}
