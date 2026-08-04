package dev.maestro.payment.security;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Optional;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/** Resolves a presented API key to the merchant it belongs to. */
@Repository
public class ApiKeyRepository {

    private final JdbcClient jdbc;

    public ApiKeyRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * Looks up an active key by the hash of its secret.
     *
     * <p>The lookup is on the hash, never on the secret, so the plaintext exists only
     * for the life of the request. A revoked key resolves to nothing.
     */
    public Optional<MerchantPrincipal> findActiveBySecret(String presentedSecret) {
        return jdbc.sql("""
                SELECT k.id, k.merchant_id, k.role
                  FROM api_key k
                  JOIN merchant m ON m.id = k.merchant_id
                 WHERE k.key_hash = :hash
                   AND k.revoked_at IS NULL
                   AND m.status = 'ACTIVE'
                """)
                .param("hash", sha256Hex(presentedSecret))
                .query((rs, rowNum) -> new MerchantPrincipal(
                        rs.getString("merchant_id"),
                        rs.getString("id"),
                        rs.getString("role")))
                .optional();
    }

    static String sha256Hex(String value) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is required by every JVM", e);
        }
    }
}
