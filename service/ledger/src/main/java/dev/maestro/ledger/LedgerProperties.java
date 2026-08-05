package dev.maestro.ledger;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * @param opsToken shared secret for the {@code /ops} read endpoints. A placeholder for
 *                 Phase 6: merchant-facing balances need JWT authentication, and reading
 *                 {@code payment.api_key} from here would cross a service boundary the
 *                 database deliberately forbids.
 */
@ConfigurationProperties("maestro.ledger")
public record LedgerProperties(String opsToken) {

    public LedgerProperties {
        if (opsToken == null || opsToken.isBlank()) {
            throw new IllegalArgumentException("maestro.ledger.ops-token must be set");
        }
    }
}
