package dev.maestro.router.health;

/**
 * An acquirer on a corridor — the unit everything in routing is a property of.
 *
 * <p>Not an acquirer, and not a corridor: an acquirer can be healthy for domestic Visa
 * and failing for cross-border Mastercard, and a single figure per acquirer would average
 * those into one that describes neither (ADR-0007).
 */
public record CorridorKey(String acquirerId, String corridor) {

    @Override
    public String toString() {
        return acquirerId + "@" + corridor;
    }
}
