package dev.maestro.payment.security;

/**
 * The current request's tenant, carried by a {@link ScopedValue}.
 *
 * <p>A {@code ScopedValue} rather than a {@code ThreadLocal} because requests run on
 * virtual threads (ADR-0002): the binding is immutable, is visible only for the
 * duration of the operation it wraps, and cannot be left behind on a pooled carrier
 * thread. There is no {@code remove()} to forget to call, and a leak across requests
 * — which here would mean serving one merchant's data to another — is structurally
 * impossible rather than merely unlikely.
 *
 * <p>This is layer two of the four described in the authorization model. Code that
 * reads merchant-scoped data asks for {@link #requireMerchantId()} rather than
 * accepting a merchant identifier from its caller, so a scoping filter cannot be
 * omitted by forgetting to pass one.
 */
public final class TenantContext {

    private static final ScopedValue<MerchantPrincipal> CURRENT = ScopedValue.newInstance();

    private TenantContext() {
    }

    /** Runs {@code operation} with {@code principal} bound, and unbinds it on return. */
    public static <T> T callWith(MerchantPrincipal principal, ScopedOperation<T> operation)
            throws Exception {
        return ScopedValue.where(CURRENT, principal).call(operation::run);
    }

    public static MerchantPrincipal require() {
        if (!CURRENT.isBound()) {
            throw new IllegalStateException(
                    "No tenant bound to this request. Every entry point must authenticate "
                            + "before reaching merchant-scoped code.");
        }
        return CURRENT.get();
    }

    public static String requireMerchantId() {
        return require().merchantId();
    }

    public static boolean isBound() {
        return CURRENT.isBound();
    }

    /** An operation that may fail, so a servlet chain can be wrapped without unwrapping. */
    @FunctionalInterface
    public interface ScopedOperation<T> {
        T run() throws Exception;
    }
}
