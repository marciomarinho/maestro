package dev.maestro.router.attempt;

import java.util.Optional;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * The record of every acquirer call the platform has made.
 *
 * <p>The row is claimed <em>before</em> the acquirer is contacted and completed
 * afterwards, so an attempt left {@code IN_FLIGHT} is visible as exactly that: a call
 * whose outcome the platform never learned.
 */
@Repository
public class AttemptRepository {

    private final JdbcClient jdbc;

    public AttemptRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * Claims the right to make this attempt.
     *
     * <p>The unique key on {@code (payment_id, operation, attempt_no)} is what makes the
     * router idempotent under redelivery: a duplicate command inserts nothing and the
     * caller can see what the first one did instead of calling the acquirer again.
     *
     * @return true if this consumer now owns the attempt
     */
    public boolean claim(Attempt attempt) {
        return jdbc.sql("""
                INSERT INTO payment_attempt
                    (id, payment_id, merchant_id, attempt_no, operation, acquirer_id, corridor,
                     selection_reason, outcome)
                VALUES (:id, :paymentId, :merchantId, :attemptNo, :operation, :acquirerId,
                        :corridor, :selectionReason, 'IN_FLIGHT')
                ON CONFLICT (payment_id, operation, attempt_no) DO NOTHING
                """)
                .param("id", attempt.id())
                .param("paymentId", attempt.paymentId())
                .param("merchantId", attempt.merchantId())
                .param("attemptNo", attempt.attemptNo())
                .param("operation", attempt.operation())
                .param("acquirerId", attempt.acquirerId())
                .param("corridor", attempt.corridor())
                .param("selectionReason", attempt.selectionReason())
                .update() == 1;
    }

    public Optional<Attempt> find(String paymentId, String operation, int attemptNo) {
        return jdbc.sql("""
                SELECT id, payment_id, merchant_id, attempt_no, operation, acquirer_id, corridor,
                       selection_reason, outcome, response_code, response_message, latency_ms,
                       acquirer_reference
                  FROM payment_attempt
                 WHERE payment_id = :paymentId AND operation = :operation AND attempt_no = :attemptNo
                """)
                .param("paymentId", paymentId)
                .param("operation", operation)
                .param("attemptNo", attemptNo)
                .query((rs, rowNum) -> new Attempt(
                        rs.getString("id"),
                        rs.getString("payment_id"),
                        rs.getString("merchant_id"),
                        rs.getInt("attempt_no"),
                        rs.getString("operation"),
                        rs.getString("acquirer_id"),
                        rs.getString("corridor"),
                        rs.getString("selection_reason"),
                        rs.getString("outcome"),
                        rs.getString("response_code"),
                        rs.getString("response_message"),
                        rs.getObject("latency_ms", Integer.class),
                        rs.getString("acquirer_reference")))
                .optional();
    }

    public void complete(
            String attemptId,
            String outcome,
            String responseCode,
            String responseMessage,
            long latencyMs,
            String acquirerReference) {
        jdbc.sql("""
                UPDATE payment_attempt
                   SET outcome = :outcome,
                       response_code = :responseCode,
                       response_message = :responseMessage,
                       latency_ms = :latencyMs,
                       acquirer_reference = :acquirerReference,
                       completed_at = now()
                 WHERE id = :id
                """)
                .param("id", attemptId)
                .param("outcome", outcome)
                .param("responseCode", responseCode)
                .param("responseMessage", responseMessage)
                .param("latencyMs", (int) latencyMs)
                .param("acquirerReference", acquirerReference)
                .update();
    }
}
