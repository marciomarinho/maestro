package dev.maestro.payment.attempt;

import dev.maestro.events.payload.AttemptRecorded;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

/**
 * The routing audit trail, projected from the router's events.
 *
 * <p>A read model and nothing more: it holds no invariant, decides nothing, and if it were
 * lost the platform would keep taking payments correctly and merely stop being able to
 * explain them. That is worth stating, because it is the licence for this table to be
 * eventually consistent when nothing else in this service is.
 */
@Repository
public class AttemptProjection {

    private final JdbcClient jdbc;

    public AttemptProjection(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * Records one attempt.
     *
     * <p>Upsert rather than insert because delivery is at least once (ADR-0006). Overwriting
     * with identical content is the correct response to a redelivery; accumulating duplicate
     * rows would turn a merchant's explanation of one failover into an explanation of three.
     */
    @Transactional
    public void apply(AttemptRecorded event) {
        jdbc.sql("""
                INSERT INTO payment_attempt_view
                    (payment_id, merchant_id, operation, attempt_no, acquirer_id, corridor,
                     selection_reason, health_score, outcome, response_code, response_message,
                     latency_ms, final_attempt)
                VALUES (:paymentId, :merchantId, :operation, :attemptNo, :acquirerId, :corridor,
                        :selectionReason, :healthScore, :outcome, :responseCode, :responseMessage,
                        :latencyMs, :finalAttempt)
                ON CONFLICT (payment_id, operation, attempt_no) DO UPDATE
                   SET outcome = EXCLUDED.outcome,
                       response_code = EXCLUDED.response_code,
                       response_message = EXCLUDED.response_message,
                       latency_ms = EXCLUDED.latency_ms,
                       final_attempt = EXCLUDED.final_attempt
                """)
                .param("paymentId", event.paymentId())
                .param("merchantId", event.merchantId())
                .param("operation", event.operation())
                .param("attemptNo", event.attemptNo())
                .param("acquirerId", event.acquirerId())
                .param("corridor", event.corridor())
                .param("selectionReason", event.selectionReason())
                .param("healthScore", event.healthScore())
                .param("outcome", event.outcome())
                .param("responseCode", event.responseCode())
                .param("responseMessage", event.responseMessage())
                .param("latencyMs", (int) event.latencyMs())
                .param("finalAttempt", event.finalAttempt())
                .update();
    }

    /**
     * Every attempt made on a payment, oldest first.
     *
     * <p>Scoped by merchant in the query rather than filtered afterwards, so a payment
     * belonging to another tenant returns an empty history instead of somebody else's
     * routing decisions.
     */
    public List<AttemptView> forPayment(String merchantId, String paymentId) {
        return jdbc.sql("""
                SELECT operation, attempt_no, acquirer_id, corridor, selection_reason,
                       health_score, outcome, response_code, response_message, latency_ms,
                       final_attempt, recorded_at
                  FROM payment_attempt_view
                 WHERE merchant_id = :merchantId AND payment_id = :paymentId
                 ORDER BY recorded_at, attempt_no
                """)
                .param("merchantId", merchantId)
                .param("paymentId", paymentId)
                .query((rs, rowNum) -> new AttemptView(
                        rs.getString("operation"),
                        rs.getInt("attempt_no"),
                        rs.getString("acquirer_id"),
                        rs.getString("corridor"),
                        rs.getString("selection_reason"),
                        rs.getBigDecimal("health_score"),
                        rs.getString("outcome"),
                        rs.getString("response_code"),
                        rs.getString("response_message"),
                        rs.getObject("latency_ms", Integer.class),
                        rs.getBoolean("final_attempt"),
                        rs.getObject("recorded_at", java.time.OffsetDateTime.class).toInstant()))
                .list();
    }

    /**
     * One acquirer call, as a merchant sees it.
     *
     * @param selectionReason why this acquirer, in a word: {@code BEST_SCORE},
     *                        {@code EXPLORATION}, {@code FAILOVER} or {@code PINNED}
     * @param healthScore     what that acquirer scored at the moment it was chosen. The
     *                        number the decision was actually made on, not the current one
     */
    public record AttemptView(
            String operation,
            int attemptNo,
            String acquirerId,
            String corridor,
            String selectionReason,
            BigDecimal healthScore,
            String outcome,
            String responseCode,
            String responseMessage,
            Integer latencyMs,
            boolean finalAttempt,
            Instant recordedAt) {
    }
}
