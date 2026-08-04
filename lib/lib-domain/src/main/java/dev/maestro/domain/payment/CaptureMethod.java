package dev.maestro.domain.payment;

/** When captured funds are taken relative to authorization. */
public enum CaptureMethod {
    /** Capture immediately on a successful authorization. The usual digital-goods flow. */
    AUTOMATIC,
    /** Hold the authorization; the merchant captures later, typically on dispatch. */
    MANUAL
}
