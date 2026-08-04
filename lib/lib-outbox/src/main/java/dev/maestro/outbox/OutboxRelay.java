package dev.maestro.outbox;

import java.util.List;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicBoolean;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Publishes appended outbox rows to Kafka.
 *
 * <p><strong>Ordering across instances.</strong> Several service instances run a relay
 * concurrently, and naive {@code FOR UPDATE SKIP LOCKED} claiming would let instance A
 * hold a payment's first event while instance B publishes its second — reordering
 * events for that payment. Claiming is therefore done with a
 * <em>transaction-scoped advisory lock per aggregate</em>: an instance either owns all
 * currently unpublished rows for a payment or skips that payment entirely. Within an
 * instance, rows are published in creation order and synchronously, so per-payment
 * ordering holds end to end.
 *
 * <p><strong>Duplicates, never losses.</strong> Rows are published first and marked
 * afterwards. A crash between the two republishes an event on the next pass, which
 * consumers absorb through their own idempotency (ADR-0006). The opposite ordering
 * would lose events, and a lost payment instruction is far worse than a repeated one.
 *
 * <p>Transactions are managed with an explicit {@link TransactionTemplate} rather than
 * {@code @Transactional}: the relay calls its own methods, and self-invocation does not
 * pass through the proxy that annotation relies on.
 */
public class OutboxRelay {

    private static final Logger log = LoggerFactory.getLogger(OutboxRelay.class);

    private final JdbcClient jdbc;
    private final KafkaTemplate<String, String> kafka;
    private final OutboxProperties properties;
    private final TransactionTemplate transactions;
    private final Executor executor;
    private final AtomicBoolean running = new AtomicBoolean(false);

    public OutboxRelay(
            JdbcClient jdbc,
            KafkaTemplate<String, String> kafka,
            OutboxProperties properties,
            TransactionTemplate transactions,
            Executor executor) {
        this.jdbc = jdbc;
        this.kafka = kafka;
        this.properties = properties;
        this.transactions = transactions;
        this.executor = executor;
    }

    /** Called after a writer's transaction commits, so latency does not wait for a poll. */
    public void wakeUp() {
        executor.execute(this::drain);
    }

    @Scheduled(
            fixedDelayString = "${maestro.outbox.poll-interval:500ms}",
            initialDelayString = "${maestro.outbox.poll-interval:500ms}")
    public void scheduledDrain() {
        drain();
    }

    /** Publishes everything currently pending, in batches. */
    public void drain() {
        // One pass at a time per instance. A concurrent pass would contend on the same
        // advisory locks and do no useful work.
        if (!running.compareAndSet(false, true)) {
            return;
        }
        try {
            int published;
            do {
                published = publishBatch();
            } while (published == properties.batchSize());
        } catch (RuntimeException e) {
            // Rows stay unpublished and are retried on the next pass. Failing loudly
            // here would only restart the same work.
            log.warn("Outbox relay pass failed; unpublished rows will be retried", e);
        } finally {
            running.set(false);
        }
    }

    private int publishBatch() {
        Integer published = transactions.execute(status -> {
            List<PendingEvent> batch = claim();
            if (batch.isEmpty()) {
                return 0;
            }

            for (PendingEvent event : batch) {
                // Synchronous send: publication order within an aggregate must match
                // creation order, and a failure must stop the batch rather than leave a
                // gap behind it.
                kafka.send(event.topic(), event.aggregateId(), event.payload()).join();
            }

            jdbc.sql("""
                    UPDATE %s.outbox_event
                       SET published_at = now()
                     WHERE id = ANY (:ids)
                    """.formatted(properties.schema()))
                    .param("ids", batch.stream().map(PendingEvent::id).toArray(String[]::new))
                    .update();

            log.debug("Published {} outbox events", batch.size());
            return batch.size();
        });
        return published == null ? 0 : published;
    }

    private List<PendingEvent> claim() {
        // pg_try_advisory_xact_lock claims the aggregate for this transaction and
        // releases it at commit. Rows belonging to an aggregate another instance is
        // already publishing are skipped, which is what preserves per-payment order.
        // The planner may evaluate the lock call on rows that fail the other
        // predicate; taking a surplus transaction-scoped lock is harmless.
        return jdbc.sql("""
                SELECT id, aggregate_id, topic, payload
                  FROM %s.outbox_event
                 WHERE published_at IS NULL
                   AND pg_try_advisory_xact_lock(hashtext(aggregate_id))
                 ORDER BY created_at, id
                 LIMIT :batchSize
                """.formatted(properties.schema()))
                .param("batchSize", properties.batchSize())
                .query((rs, rowNum) -> new PendingEvent(
                        rs.getString("id"),
                        rs.getString("aggregate_id"),
                        rs.getString("topic"),
                        rs.getString("payload")))
                .list();
    }

    /** Removes long-published rows so the table does not grow without bound. */
    @Scheduled(cron = "${maestro.outbox.sweep-cron:0 0 3 * * *}")
    public void sweepPublished() {
        Integer removed = transactions.execute(status -> jdbc.sql("""
                DELETE FROM %s.outbox_event
                 WHERE published_at IS NOT NULL
                   AND published_at < now() - CAST(:retention AS interval)
                """.formatted(properties.schema()))
                .param("retention", properties.retention().toDays() + " days")
                .update());
        if (removed != null && removed > 0) {
            log.info("Swept {} published outbox rows", removed);
        }
    }

    private record PendingEvent(String id, String aggregateId, String topic, String payload) {
    }
}
