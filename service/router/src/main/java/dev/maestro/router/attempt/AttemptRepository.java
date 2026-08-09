package dev.maestro.router.attempt;

import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * The record of every acquirer call the platform has made.
 *
 * <p>The row is claimed <em>before</em> the acquirer is contacted and completed
 * afterwards, so an attempt left {@code IN_FLIGHT} is visible as exactly that: a call
 * whose outcome the platform never learned.
 *
 * <p>With cascading failover an operation can span several rows, which changes what
 * idempotency means here. The unique key on {@code (payment_id, operation, attempt_no)}
 * still stops two consumers starting the same attempt, but "has this command already been
 * handled?" is now {@link #isAnswered}: it asks whether an attempt <em>ended</em> the
 * operation, not merely whether one exists.
 */
@Repository
public class AttemptRepository {

    private static final String COLUMNS = """
            id, payment_id, merchant_id, attempt_no, operation, acquirer_id, corridor,
            selection_reason, health_score_at_selection, outcome, response_code,
            response_message, latency_ms, acquirer_reference, final_attempt
            """;

    private final JdbcClient jdbc;

    public AttemptRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * Claims the right to make this attempt.
     *
     * @return true if this consumer now owns the attempt
     */
    public boolean claim(Attempt attempt) {
        return jdbc.sql("""
                INSERT INTO payment_attempt
                    (id, payment_id, merchant_id, attempt_no, operation, acquirer_id, corridor,
                     selection_reason, health_score_at_selection, outcome)
                VALUES (:id, :paymentId, :merchantId, :attemptNo, :operation, :acquirerId,
                        :corridor, :selectionReason, :healthScore, 'IN_FLIGHT')
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
                .param("healthScore", attempt.healthScore())
                .update() == 1;
    }

    /**
     * Whether this operation has already reached an outcome the platform published.
     *
     * <p>The redelivery guard. Because the final flag and the outbox row commit together,
     * a true here means the event exists and re-running the command would duplicate an
     * effect that has already been announced.
     */
    public boolean isAnswered(String subjectId, String operation) {
        return jdbc.sql("""
                SELECT EXISTS (SELECT 1 FROM payment_attempt
                                WHERE payment_id = :subjectId
                                  AND operation = :operation
                                  AND final_attempt)
                """)
                .param("subjectId", subjectId)
                .param("operation", operation)
                .query(Boolean.class)
                .single();
    }

    /**
     * An attempt the platform started and never resolved — a crash between the call and
     * the answer. Resumed rather than replaced, so the acquirer sees the same idempotency
     * key and settles the ambiguity itself.
     */
    public Optional<Attempt> findInFlight(String subjectId, String operation) {
        return jdbc.sql("""
                SELECT %s FROM payment_attempt
                 WHERE payment_id = :subjectId AND operation = :operation
                   AND outcome = 'IN_FLIGHT'
                 ORDER BY attempt_no DESC
                 LIMIT 1
                """.formatted(COLUMNS))
                .param("subjectId", subjectId)
                .param("operation", operation)
                .query(AttemptRepository::map)
                .optional();
    }

    public Optional<Attempt> find(String subjectId, String operation, int attemptNo) {
        return jdbc.sql("""
                SELECT %s FROM payment_attempt
                 WHERE payment_id = :subjectId AND operation = :operation
                   AND attempt_no = :attemptNo
                """.formatted(COLUMNS))
                .param("subjectId", subjectId)
                .param("operation", operation)
                .param("attemptNo", attemptNo)
                .query(AttemptRepository::map)
                .optional();
    }

    /** Every attempt for a payment, oldest first — the routing audit trail. */
    public List<Attempt> historyOf(String paymentId) {
        return jdbc.sql("""
                SELECT %s FROM payment_attempt
                 WHERE payment_id = :paymentId
                 ORDER BY started_at, attempt_no
                """.formatted(COLUMNS))
                .param("paymentId", paymentId)
                .query(AttemptRepository::map)
                .list();
    }

    /** The highest attempt number used so far, or zero. The next one is this plus one. */
    public int highestAttemptNo(String subjectId, String operation) {
        return jdbc.sql("""
                SELECT COALESCE(MAX(attempt_no), 0) FROM payment_attempt
                 WHERE payment_id = :subjectId AND operation = :operation
                """)
                .param("subjectId", subjectId)
                .param("operation", operation)
                .query(Integer.class)
                .single();
    }

    /**
     * Acquirers this operation has already been offered to.
     *
     * <p>Read from the database rather than held in memory so that a failover sequence
     * interrupted by a restart resumes without offering the payment back to an acquirer
     * that has already failed it.
     */
    public Set<String> acquirersTried(String subjectId, String operation) {
        return Set.copyOf(jdbc.sql("""
                SELECT DISTINCT acquirer_id FROM payment_attempt
                 WHERE payment_id = :subjectId AND operation = :operation
                """)
                .param("subjectId", subjectId)
                .param("operation", operation)
                .query(String.class)
                .list());
    }

    /**
     * Records how an attempt ended.
     *
     * @param finalAttempt true when this attempt ends the operation. Written in the same
     *                     transaction as the outbox event, which is what makes
     *                     {@link #isAnswered} trustworthy
     */
    public void complete(
            String attemptId,
            String outcome,
            String responseCode,
            String responseMessage,
            long latencyMs,
            String acquirerReference,
            boolean finalAttempt) {
        jdbc.sql("""
                UPDATE payment_attempt
                   SET outcome = :outcome,
                       response_code = :responseCode,
                       response_message = :responseMessage,
                       latency_ms = :latencyMs,
                       acquirer_reference = :acquirerReference,
                       final_attempt = :finalAttempt,
                       completed_at = now()
                 WHERE id = :id
                """)
                .param("id", attemptId)
                .param("outcome", outcome)
                .param("responseCode", responseCode)
                .param("responseMessage", responseMessage)
                .param("latencyMs", (int) latencyMs)
                .param("acquirerReference", acquirerReference)
                .param("finalAttempt", finalAttempt)
                .update();
    }

    /**
     * Marks the latest attempt as the one that ended the operation.
     *
     * <p>For the case where the router runs out of acquirers between deciding to fail over
     * and trying to: the previous attempt was written down as one to walk away from, and
     * there is now nowhere to walk to. Without this the operation would have published an
     * outcome but have no attempt claiming to be final, and redelivery would try again.
     *
     * @return true if an attempt was marked; false when the operation never got as far as
     *         claiming one, which happens when every acquirer was already unavailable
     */
    public boolean markLatestFinal(String subjectId, String operation) {
        return jdbc.sql("""
                UPDATE payment_attempt
                   SET final_attempt = TRUE
                 WHERE id = (SELECT id FROM payment_attempt
                              WHERE payment_id = :subjectId AND operation = :operation
                              ORDER BY attempt_no DESC
                              LIMIT 1)
                """)
                .param("subjectId", subjectId)
                .param("operation", operation)
                .update() == 1;
    }

    private static Attempt map(java.sql.ResultSet rs, int rowNum) throws java.sql.SQLException {
        return new Attempt(
                rs.getString("id"),
                rs.getString("payment_id"),
                rs.getString("merchant_id"),
                rs.getInt("attempt_no"),
                rs.getString("operation"),
                rs.getString("acquirer_id"),
                rs.getString("corridor"),
                rs.getString("selection_reason"),
                rs.getBigDecimal("health_score_at_selection"),
                rs.getString("outcome"),
                rs.getString("response_code"),
                rs.getString("response_message"),
                rs.getObject("latency_ms", Integer.class),
                rs.getString("acquirer_reference"),
                rs.getBoolean("final_attempt"));
    }
}
