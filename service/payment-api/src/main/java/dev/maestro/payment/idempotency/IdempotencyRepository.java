package dev.maestro.payment.idempotency;

import java.util.Optional;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * Idempotency records, written in the same transaction as the effect they guard.
 *
 * <p>That co-location is the entire point (ADR-0013): there is no window in which a
 * payment exists but its key does not, so a merchant's retry can never create a second
 * one. Concurrency control is the primary key itself — two simultaneous requests race
 * to insert and exactly one wins.
 */
@Repository
public class IdempotencyRepository {

    private final JdbcClient jdbc;

    public IdempotencyRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * Attempts to claim a key.
     *
     * <p>{@code ON CONFLICT DO NOTHING} rather than letting the constraint throw:
     * a raised constraint violation would abort the surrounding transaction, and the
     * existing record could then only be read after a rollback.
     *
     * @return true if this caller now owns the key and should perform the effect
     */
    public boolean claim(
            String merchantId, String endpoint, String key, String requestHash) {
        return jdbc.sql("""
                INSERT INTO idempotency_record
                    (merchant_id, endpoint, idempotency_key, request_hash, status)
                VALUES (:merchantId, :endpoint, :key, :requestHash, 'IN_PROGRESS')
                ON CONFLICT (merchant_id, endpoint, idempotency_key) DO NOTHING
                """)
                .param("merchantId", merchantId)
                .param("endpoint", endpoint)
                .param("key", key)
                .param("requestHash", requestHash)
                .update() == 1;
    }

    public Optional<IdempotencyRecord> find(String merchantId, String endpoint, String key) {
        return jdbc.sql("""
                SELECT request_hash, status, response_status, response_body, resource_id
                  FROM idempotency_record
                 WHERE merchant_id = :merchantId
                   AND endpoint = :endpoint
                   AND idempotency_key = :key
                """)
                .param("merchantId", merchantId)
                .param("endpoint", endpoint)
                .param("key", key)
                .query((rs, rowNum) -> new IdempotencyRecord(
                        rs.getString("request_hash"),
                        rs.getString("status"),
                        rs.getObject("response_status", Integer.class),
                        rs.getString("response_body"),
                        rs.getString("resource_id")))
                .optional();
    }

    /** Stores the response so a later replay returns exactly what the original returned. */
    public void complete(
            String merchantId,
            String endpoint,
            String key,
            int responseStatus,
            String responseBody,
            String resourceId) {
        jdbc.sql("""
                UPDATE idempotency_record
                   SET status = 'COMPLETED',
                       response_status = :responseStatus,
                       response_body = :responseBody,
                       resource_id = :resourceId,
                       completed_at = now()
                 WHERE merchant_id = :merchantId
                   AND endpoint = :endpoint
                   AND idempotency_key = :key
                """)
                .param("merchantId", merchantId)
                .param("endpoint", endpoint)
                .param("key", key)
                .param("responseStatus", responseStatus)
                .param("responseBody", responseBody)
                .param("resourceId", resourceId)
                .update();
    }

    /** Removes records past the retention window promised in the API documentation. */
    public int sweepOlderThanHours(int hours) {
        return jdbc.sql("""
                DELETE FROM idempotency_record
                 WHERE created_at < now() - make_interval(hours => :hours)
                """)
                .param("hours", hours)
                .update();
    }

    public record IdempotencyRecord(
            String requestHash,
            String status,
            Integer responseStatus,
            String responseBody,
            String resourceId) {

        public boolean isCompleted() {
            return "COMPLETED".equals(status);
        }
    }
}
