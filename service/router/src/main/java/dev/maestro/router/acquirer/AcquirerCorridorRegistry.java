package dev.maestro.router.acquirer;

import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * The acquiring agreements, held in memory.
 *
 * <p>Selection needs every candidate for a corridor on every authorization. Reading them
 * from the database each time would put a round trip in front of every payment in the
 * platform for data that changes when somebody signs a contract — a few times a year.
 * So the table is read on a schedule and served from a snapshot.
 *
 * <p>The snapshot is swapped wholesale rather than mutated in place, so a selection in
 * progress sees one consistent set of terms and never a corridor priced under the old
 * agreement beside one priced under the new.
 *
 * <p>Staleness is bounded by the refresh interval and is acceptable here in a way it
 * would not be for health: a minute of routing under yesterday's prices costs basis
 * points, while a minute of routing under yesterday's <em>health</em> costs approvals.
 * That asymmetry is why health is updated on every attempt and this is not.
 */
@Component
public class AcquirerCorridorRegistry implements CorridorCatalogue {

    private static final Logger log = LoggerFactory.getLogger(AcquirerCorridorRegistry.class);

    private final JdbcClient jdbc;
    private final AtomicReference<Map<String, List<AcquirerCorridor>>> byCorridor =
            new AtomicReference<>(Map.of());

    public AcquirerCorridorRegistry(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * Every enabled acquirer that can serve this corridor, cheapest first.
     *
     * <p>Ordering is not the routing decision — it is a stable starting order so that two
     * corridors with identical health produce a repeatable choice rather than one that
     * depends on how the database felt about returning rows.
     *
     * <p>The first call loads the snapshot. Deliberately here rather than in the
     * constructor: a constructor that queries makes this bean's correctness depend on
     * being built after Flyway has run, which is a startup-ordering assumption that holds
     * until the day it does not and then fails as an empty routing table. Loading on
     * demand has no ordering to get wrong, and the only request that pays for it is one
     * that arrives before the first scheduled refresh.
     */
    @Override
    public List<AcquirerCorridor> candidatesFor(String corridor) {
        Map<String, List<AcquirerCorridor>> snapshot = byCorridor.get();
        if (snapshot.isEmpty()) {
            refresh();
            snapshot = byCorridor.get();
        }
        return snapshot.getOrDefault(corridor, List.of());
    }

    /** True when no acquirer at all is configured for a corridor — a misconfiguration. */
    public boolean isUnserviced(String corridor) {
        return candidatesFor(corridor).isEmpty();
    }

    @Scheduled(
            initialDelayString = "${maestro.router.corridor-refresh-interval:60s}",
            fixedDelayString = "${maestro.router.corridor-refresh-interval:60s}")
    public final void refresh() {
        List<AcquirerCorridor> rows = jdbc.sql("""
                SELECT acquirer_id, corridor, cost_bps, fixed_fee_minor, enabled
                  FROM acquirer_corridor
                 WHERE enabled
                 ORDER BY cost_bps, acquirer_id
                """)
                .query((rs, rowNum) -> new AcquirerCorridor(
                        rs.getString("acquirer_id"),
                        rs.getString("corridor"),
                        rs.getBigDecimal("cost_bps"),
                        rs.getLong("fixed_fee_minor"),
                        rs.getBoolean("enabled")))
                .list();

        Map<String, List<AcquirerCorridor>> snapshot = rows.stream()
                .collect(Collectors.groupingBy(
                        AcquirerCorridor::corridor,
                        Collectors.collectingAndThen(Collectors.toList(), List::copyOf)));
        byCorridor.set(Map.copyOf(snapshot));

        log.debug("Loaded {} acquiring agreements across {} corridors",
                rows.size(), snapshot.size());
    }
}
