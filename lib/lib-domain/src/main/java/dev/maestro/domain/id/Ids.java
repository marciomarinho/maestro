package dev.maestro.domain.id;

import java.security.SecureRandom;
import java.util.Objects;

/**
 * Public identifiers: a short type prefix followed by a ULID.
 *
 * <p>The prefix ({@code pay_}, {@code ref_}, {@code mch_}) makes an identifier
 * self-describing in a log line or a support conversation, and makes it impossible
 * to pass a refund identifier where a payment identifier belongs without noticing.
 *
 * <p>The body is a <a href="https://github.com/ulid/spec">ULID</a>: 48 bits of
 * millisecond timestamp followed by 80 bits of randomness, in Crockford base32.
 * Unlike a random UUID it sorts by creation time, which keeps primary-key inserts
 * append-ordered rather than scattered across the index.
 */
public final class Ids {

    public static final String PAYMENT_PREFIX = "pay";
    public static final String REFUND_PREFIX = "ref";
    public static final String MERCHANT_PREFIX = "mch";
    public static final String API_KEY_PREFIX = "ak";
    public static final String EVENT_PREFIX = "evt";
    public static final String ATTEMPT_PREFIX = "att";
    public static final String REQUEST_PREFIX = "req";

    private static final char[] CROCKFORD = "0123456789ABCDEFGHJKMNPQRSTVWXYZ".toCharArray();
    private static final int ULID_LENGTH = 26;
    private static final SecureRandom RANDOM = new SecureRandom();

    private Ids() {
    }

    public static String payment() {
        return generate(PAYMENT_PREFIX);
    }

    public static String refund() {
        return generate(REFUND_PREFIX);
    }

    public static String merchant() {
        return generate(MERCHANT_PREFIX);
    }

    public static String apiKey() {
        return generate(API_KEY_PREFIX);
    }

    public static String event() {
        return generate(EVENT_PREFIX);
    }

    public static String attempt() {
        return generate(ATTEMPT_PREFIX);
    }

    public static String request() {
        return generate(REQUEST_PREFIX);
    }

    public static String generate(String prefix) {
        Objects.requireNonNull(prefix, "prefix must not be null");
        return prefix + "_" + ulid(System.currentTimeMillis());
    }

    /** True if the identifier carries the expected type prefix and a well-formed body. */
    public static boolean hasPrefix(String id, String prefix) {
        if (id == null || prefix == null) {
            return false;
        }
        return id.length() == prefix.length() + 1 + ULID_LENGTH && id.startsWith(prefix + "_");
    }

    static String ulid(long epochMilli) {
        byte[] randomness = new byte[10];
        RANDOM.nextBytes(randomness);
        char[] out = new char[ULID_LENGTH];

        // 48-bit timestamp occupies the first 10 characters (50 bits, top 2 unused).
        for (int i = 9; i >= 0; i--) {
            out[i] = CROCKFORD[(int) (epochMilli & 0x1F)];
            epochMilli >>>= 5;
        }

        // 80 bits of randomness occupy the remaining 16 characters.
        long high = 0L;
        for (int i = 0; i < 5; i++) {
            high = (high << 8) | (randomness[i] & 0xFFL);
        }
        long low = 0L;
        for (int i = 5; i < 10; i++) {
            low = (low << 8) | (randomness[i] & 0xFFL);
        }
        for (int i = 7; i >= 0; i--) {
            out[10 + i] = CROCKFORD[(int) (high & 0x1F)];
            high >>>= 5;
        }
        for (int i = 7; i >= 0; i--) {
            out[18 + i] = CROCKFORD[(int) (low & 0x1F)];
            low >>>= 5;
        }
        return new String(out);
    }
}
