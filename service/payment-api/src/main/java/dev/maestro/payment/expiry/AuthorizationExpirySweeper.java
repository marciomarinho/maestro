package dev.maestro.payment.expiry;

import dev.maestro.payment.core.PaymentLifecycleService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Expires authorizations nobody captured.
 *
 * <p>Real authorizations lapse, and a platform that never notices accumulates holds
 * against money it will never take — the merchant's dashboard shows pending revenue that
 * does not exist, and the cardholder's available balance stays reduced for no reason.
 *
 * <p>Runs on every instance rather than one elected leader: the claim uses
 * {@code FOR UPDATE SKIP LOCKED}, so instances take disjoint batches and the work
 * parallelises instead of contending.
 */
@Component
public class AuthorizationExpirySweeper {

    private static final Logger log = LoggerFactory.getLogger(AuthorizationExpirySweeper.class);

    private final PaymentLifecycleService lifecycle;

    public AuthorizationExpirySweeper(PaymentLifecycleService lifecycle) {
        this.lifecycle = lifecycle;
    }

    @Scheduled(
            fixedDelayString = "${maestro.payment.expiry-sweep-interval:1m}",
            initialDelayString = "${maestro.payment.expiry-sweep-interval:1m}")
    public void sweep() {
        try {
            int expired;
            // Keep going while batches come back full, so a backlog drains in one pass
            // rather than one batch per interval.
            do {
                expired = lifecycle.expireLapsedAuthorizations();
            } while (expired > 0);
        } catch (RuntimeException e) {
            // The next tick retries. Letting this escape would kill the scheduled task
            // for the lifetime of the process.
            log.warn("Authorization expiry sweep failed; will retry on the next tick", e);
        }
    }
}
