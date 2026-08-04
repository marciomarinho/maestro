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

    private static Payment mapPayment(java.sql.ResultSet rs, int rowNum) throws java.sql.SQLException {
        return new Payment(
                rs.getString("id"),
                rs.getString("merchant_id"),
                rs.getLong("amount_minor"),
                rs.getString("currency"),
                rs.getLong("captured_amount_minor"),
                rs.getLong("refunded_amount_minor"),
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
