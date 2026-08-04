package dev.maestro.domain.payment;

/**
 * The card scheme a payment runs on.
 *
 * <p>Network and currency together define a <em>corridor</em>, which is the unit an
 * acquirer's cost, capacity and health are properties of (ADR-0007). An acquirer can
 * be perfectly healthy for domestic Visa traffic and failing for cross-border
 * Mastercard, so a single per-acquirer health figure would describe neither.
 */
public enum CardNetwork {
    VISA,
    MASTERCARD,
    AMEX,
    EFTPOS
}
