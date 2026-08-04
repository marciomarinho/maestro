package dev.maestro.router.acquirer;

import dev.maestro.router.RouterProperties;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.Set;
import org.springframework.stereotype.Component;

/**
 * Phase 1 selection: the single configured acquirer.
 *
 * <p>Deliberately not a stub of the real thing. There is one acquirer, so there is one
 * answer, and the reason recorded on every attempt is {@code PINNED} — which is true.
 * Writing a scoring function over a set of size one would produce code that looks like
 * routing while demonstrating none of it, and would be harder to replace in Phase 3
 * than an honest placeholder.
 */
@Component
public class PinnedAcquirerSelector implements AcquirerSelector {

    private final List<String> acquirerIds;

    public PinnedAcquirerSelector(RouterProperties properties) {
        this.acquirerIds = properties.acquirers().stream()
                .map(RouterProperties.Acquirer::id)
                .toList();
    }

    @Override
    public Selection select(String corridor, Set<String> excludedAcquirerIds) {
        return acquirerIds.stream()
                .filter(id -> !excludedAcquirerIds.contains(id))
                .findFirst()
                .map(id -> new Selection(id, Selection.REASON_PINNED, null))
                .orElseThrow(() -> new NoSuchElementException(
                        "No acquirer available for corridor %s (excluded: %s)"
                                .formatted(corridor, excludedAcquirerIds)));
    }
}
