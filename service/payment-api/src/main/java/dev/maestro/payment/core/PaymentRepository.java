package dev.maestro.payment.core;

import dev.maestro.domain.payment.CaptureMethod;
import dev.maestro.domain.payment.PaymentStatus;
import java.util.Optional;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * Payment persistence.
 *
 * <p>Every state change is a <strong>guarded conditional update</strong> — the current
 * status appears in the {@code WHERE} clause, so a transition can only be applied from
 * the state it is legal in. A duplicate event therefore updates zero rows and is
 * absorbed silently. This is the mechanism that turns at-least-once delivery into
 * exactly-once money effects (ADR-0006); the row is the concurrency-control point and
 * no application-level locking is involved.
 *
 * <p>Reads are merchant-scoped without exception. The merchant comes from the
 * authenticated principal, never from the caller.
 */
@Repository
public class PaymentRepository {

    private final JdbcClient jdbc;

    public PaymentRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public void insert(Payment payment) {
        jdbc.sql("""
                INSERT INTO payment (id, merchant_id, amount_minor, currency, card_token,
                                     card_network, card_last4, card_country, status,
                                     capture_method, reference, metadata)
                VALUES (:id, :merchantId, :amountMinor, :currency, :cardToken,
                        :cardNetwork, :cardLast4, :cardCountry, :status,
                        :captureMethod, :reference, CAST(:metadata AS jsonb))
                """)
                .param("id", payment.id())
                .param("merchantId", payment.merchantId())
                .param("amountMinor", payment.amountMinor())
                .param("currency", payment.currency())
                .param("cardToken", payment.cardToken())
                .param("cardNetwork", payment.cardNetwork())
                .param("cardLast4", payment.cardLast4())
                .param("cardCountry", payment.cardCountry())
                .param("status", payment.status().name())
                .param("captureMethod", payment.captureMethod().name())
                .param("reference", payment.reference())
                .param("metadata", payment.metadataJson())
                .update();
    }

    public Optional<Payment> find(String merchantId, String paymentId) {
        return jdbc.sql("SELECT * FROM payment WHERE id = :id AND merchant_id = :merchantId")
                .param("id", paymentId)
                .param("merchantId", merchantId)
                .query(PaymentRepository::mapPayment)
                .optional();
    }

    /** Unscoped lookup, for event consumers that act on behalf of the platform. */
    public Optional<Payment> findById(String paymentId) {
        return jdbc.sql("SELECT * FROM payment WHERE id = :id")
                .param("id", paymentId)
                .query(PaymentRepository::mapPayment)
                .optional();
    }

    /**
     * Moves a payment into authorization.
     *
     * @return the number of rows changed: 1 on success, 0 if the payment was not in
     *         {@code CREATED} — which is how a duplicate confirmation is detected
     *         without a read-then-write race
     */
    public int transitionToAuthorizing(String merchantId, String paymentId) {
        return jdbc.sql("""
                UPDATE payment
                   SET status = 'AUTHORIZING', updated_at = now()
                 WHERE id = :id AND merchant_id = :merchantId AND status = 'CREATED'
                """)
                .param("id", paymentId)
                .param("merchantId", merchantId)
                .update();
    }

    public int markAuthorized(
            String paymentId,
            String acquirerId,
            String acquirerReference,
            String authorizationCode) {
        return jdbc.sql("""
                UPDATE payment
                   SET status = 'AUTHORIZED',
                       acquirer_id = :acquirerId,
                       acquirer_reference = :acquirerReference,
                       authorization_code = :authorizationCode,
                       authorized_at = now(),
                       authorization_expires_at = now() + INTERVAL '7 days',
                       updated_at = now()
                 WHERE id = :id AND status = 'AUTHORIZING'
                """)
                .param("id", paymentId)
                .param("acquirerId", acquirerId)
                .param("acquirerReference", acquirerReference)
                .param("authorizationCode", authorizationCode)
                .update();
    }

    public int markDeclined(String paymentId, String acquirerId, String declineCode, String message) {
        return jdbc.sql("""
                UPDATE payment
                   SET status = 'DECLINED',
                       acquirer_id = :acquirerId,
                       decline_code = :declineCode,
                       failure_reason = :message,
                       updated_at = now()
                 WHERE id = :id AND status = 'AUTHORIZING'
                """)
                .param("id", paymentId)
                .param("acquirerId", acquirerId)
                .param("declineCode", declineCode)
                .param("message", message)
                .update();
    }

    public int markFailed(String paymentId, String reason) {
        return jdbc.sql("""
                UPDATE payment
                   SET status = 'FAILED', failure_reason = :reason, updated_at = now()
                 WHERE id = :id AND status = 'AUTHORIZING'
                """)
                .param("id", paymentId)
                .param("reason", reason)
                .update();
    }

    // --- capture -----------------------------------------------------------

    /**
     * Moves an authorized payment into capture.
     *
     * <p>Used by both the merchant's explicit capture and the automatic capture triggered
     * on authorization, which is why it is guarded rather than trusting the caller to have
     * checked: two paths reaching it at once must still produce one capture.
     */
    public int transitionToCapturing(String paymentId) {
        return jdbc.sql("""
                UPDATE payment
                   SET status = 'CAPTURING', updated_at = now()
                 WHERE id = :id AND status = 'AUTHORIZED'
                """)
                .param("id", paymentId)
                .update();
    }

    public int markCaptured(String paymentId, long capturedAmountMinor) {
        return jdbc.sql("""
                UPDATE payment
                   SET status = 'CAPTURED',
                       captured_amount_minor = :capturedAmount,
                       updated_at = now()
                 WHERE id = :id AND status = 'CAPTURING'
                """)
                .param("id", paymentId)
                .param("capturedAmount", capturedAmountMinor)
                .update();
    }

    /**
     * A failed capture returns the payment to {@code AUTHORIZED}.
     *
     * <p>The authorization survives a failed capture, so the merchant can try again —
     * unlike a failed authorization, nothing has been lost but time.
     */
    public int markCaptureFailed(String paymentId, String reason) {
        return jdbc.sql("""
                UPDATE payment
                   SET status = 'AUTHORIZED', failure_reason = :reason, updated_at = now()
                 WHERE id = :id AND status = 'CAPTURING'
                """)
                .param("id", paymentId)
                .param("reason", reason)
                .update();
    }

    // --- void and expiry ---------------------------------------------------

    public int markVoided(String paymentId) {
        return jdbc.sql("""
                UPDATE payment
                   SET status = 'VOIDED', updated_at = now()
                 WHERE id = :id AND status = 'AUTHORIZED'
                """)
                .param("id", paymentId)
                .update();
    }

    /**
     * Claims lapsed authorizations for expiry.
     *
     * <p>{@code FOR UPDATE SKIP LOCKED} lets every instance sweep concurrently without a
     * leader election: each claims a disjoint batch and the rest move on rather than
     * blocking.
     */
    public java.util.List<Payment> claimExpiredAuthorizations(int limit) {
        return jdbc.sql("""
                SELECT * FROM payment
                 WHERE status = 'AUTHORIZED'
                   AND authorization_expires_at IS NOT NULL
                   AND authorization_expires_at < now()
                 ORDER BY authorization_expires_at
                 LIMIT :limit
                 FOR UPDATE SKIP LOCKED
                """)
                .param("limit", limit)
                .query(PaymentRepository::mapPayment)
                .list();
    }

    public int markExpired(String paymentId) {
        return jdbc.sql("""
                UPDATE payment
                   SET status = 'EXPIRED', updated_at = now()
                 WHERE id = :id AND status = 'AUTHORIZED'
                """)
                .param("id", paymentId)
                .update();
    }

    // --- refunds -----------------------------------------------------------

    /**
     * Reserves part of the captured amount for a refund.
     *
     * <p>The whole invariant lives in this one statement. Reading the payment, checking the
     * remaining balance in Java and then writing would let two concurrent refunds both pass
     * the check; putting the arithmetic in the {@code WHERE} clause means the database
     * arbitrates and the loser changes zero rows.
     *
     * @return 1 if the amount was reserved, 0 if it would exceed what is refundable
     */
    public int reserveRefund(String merchantId, String paymentId, long amountMinor) {
        return jdbc.sql("""
                UPDATE payment
                   SET refund_reserved_minor = refund_reserved_minor + :amount,
                       updated_at = now()
                 WHERE id = :id
                   AND merchant_id = :merchantId
                   AND status IN ('CAPTURED', 'PARTIALLY_REFUNDED')
                   AND refund_reserved_minor + :amount <= captured_amount_minor
                """)
                .param("id", paymentId)
                .param("merchantId", merchantId)
                .param("amount", amountMinor)
                .update();
    }

    /** Gives a reservation back after a refund fails, so the amount becomes refundable again. */
    public int releaseRefundReservation(String paymentId, long amountMinor) {
        return jdbc.sql("""
                UPDATE payment
                   SET refund_reserved_minor = refund_reserved_minor - :amount,
                       updated_at = now()
                 WHERE id = :id AND refund_reserved_minor >= :amount
                """)
                .param("id", paymentId)
                .param("amount", amountMinor)
                .update();
    }

    /**
     * Settles a refund: the reserved amount becomes an actual refund.
     *
     * <p>The status follows from the totals rather than from a flag, so it cannot disagree
     * with the amounts — fully refunded means refunded equals captured, by definition.
     */
    public int settleRefund(String paymentId, long amountMinor) {
        return jdbc.sql("""
                UPDATE payment
                   SET refunded_amount_minor = refunded_amount_minor + :amount,
                       status = CASE
                           WHEN refunded_amount_minor + :amount >= captured_amount_minor
                                THEN 'REFUNDED'
                           ELSE 'PARTIALLY_REFUNDED'
                       END,
                       updated_at = now()
                 WHERE id = :id AND status IN ('CAPTURED', 'PARTIALLY_REFUNDED')
                """)
                .param("id", paymentId)
                .param("amount", amountMinor)
                .update();
    }

    private static Payment mapPayment(java.sql.ResultSet rs, int rowNum) throws java.sql.SQLException {
        return new Payment(
                rs.getString("id"),
                rs.getString("merchant_id"),
                rs.getLong("amount_minor"),
                rs.getString("currency"),
                rs.getLong("captured_amount_minor"),
                rs.getLong("refunded_amount_minor"),
                rs.getLong("refund_reserved_minor"),
                rs.getString("card_token"),
                rs.getString("card_network"),
                rs.getString("card_last4"),
                rs.getString("card_country"),
                PaymentStatus.valueOf(rs.getString("status")),
                CaptureMethod.valueOf(rs.getString("capture_method")),
                rs.getString("reference"),
                rs.getString("metadata"),
                rs.getString("acquirer_id"),
                rs.getString("acquirer_reference"),
                rs.getString("authorization_code"),
                rs.getString("decline_code"),
                rs.getString("failure_reason"),
                instant(rs, "authorized_at"),
                instant(rs, "created_at"),
                instant(rs, "updated_at"));
    }

    private static java.time.Instant instant(java.sql.ResultSet rs, String column)
            throws java.sql.SQLException {
        java.sql.Timestamp value = rs.getTimestamp(column);
        return value == null ? null : value.toInstant();
    }
}
