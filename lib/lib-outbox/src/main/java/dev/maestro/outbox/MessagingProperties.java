package dev.maestro.outbox;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Shape of the platform's topics, shared by every service on the bus.
 *
 * @param topicPartitions how many partitions the platform's topics are created with.
 *                        Not decoration: ADR-0005 partitions by payment so that one slow
 *                        payment cannot pin a consumer thread, and a topic with a single
 *                        partition silently repeals that decision. Six is enough to keep
 *                        the local stack parallel without making the broker's memory
 *                        footprint noticeable on a laptop
 * @param topicReplicas   replication factor for those topics. One, because everything
 *                        here runs on a single local broker (ADR-0010); a real
 *                        deployment provisions its topics outside the application
 */
@ConfigurationProperties("maestro.messaging")
public record MessagingProperties(int topicPartitions, int topicReplicas) {

    public MessagingProperties {
        topicPartitions = topicPartitions <= 0 ? 6 : topicPartitions;
        topicReplicas = topicReplicas <= 0 ? 1 : topicReplicas;
    }
}
