package dev.maestro.ledger.fee;

import java.util.Currency;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * Resolves the pricing that applies to a capture.
 *
 * <p>Most specific wins: a schedule for this merchant in this currency, then one for the
 * merchant in any currency, then the platform default. The ordering is done in SQL so the
 * lookup is a single round trip and cannot disagree with itself between two code paths.
 */
@Repository
public class FeeScheduleRepository {

    private final JdbcClient jdbc;

    public FeeScheduleRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public FeeSchedule forMerchant(String merchantId, Currency currency) {
        return jdbc.sql("""
                SELECT basis_points, fixed_minor
                  FROM fee_schedule
                 WHERE (merchant_id = :merchantId OR merchant_id IS NULL)
                   AND (currency = :currency OR currency IS NULL)
                 ORDER BY (merchant_id IS NOT NULL) DESC, (currency IS NOT NULL) DESC
                 LIMIT 1
                """)
                .param("merchantId", merchantId)
                .param("currency", currency.getCurrencyCode())
                .query((rs, rowNum) ->
                        new FeeSchedule(rs.getInt("basis_points"), rs.getLong("fixed_minor")))
                .optional()
                .orElseThrow(() -> new IllegalStateException(
                        "No fee schedule applies to merchant %s in %s, and no platform default exists"
                                .formatted(merchantId, currency.getCurrencyCode())));
    }

    /**
     * @param basisPoints proportional component; 175 means 1.75%
     * @param fixedMinor  flat component in the currency's minor units
     */
    public record FeeSchedule(int basisPoints, long fixedMinor) {
    }
}
