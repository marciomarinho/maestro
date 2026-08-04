package dev.maestro.domain.id;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.HashSet;
import java.util.Set;
import org.junit.jupiter.api.Test;

class IdsTest {

    @Test
    void identifiersCarryTheirTypePrefix() {
        assertThat(Ids.payment()).startsWith("pay_");
        assertThat(Ids.refund()).startsWith("ref_");
        assertThat(Ids.merchant()).startsWith("mch_");
    }

    @Test
    void identifiersAreFixedLength() {
        assertThat(Ids.payment()).hasSize("pay_".length() + 26);
    }

    @Test
    void prefixCheckRejectsTheWrongType() {
        String payment = Ids.payment();

        assertThat(Ids.hasPrefix(payment, Ids.PAYMENT_PREFIX)).isTrue();
        assertThat(Ids.hasPrefix(payment, Ids.REFUND_PREFIX)).isFalse();
        assertThat(Ids.hasPrefix(null, Ids.PAYMENT_PREFIX)).isFalse();
        assertThat(Ids.hasPrefix("pay_short", Ids.PAYMENT_PREFIX)).isFalse();
    }

    @Test
    void identifiersAreUnique() {
        Set<String> seen = new HashSet<>();
        for (int i = 0; i < 100_000; i++) {
            assertThat(seen.add(Ids.payment())).isTrue();
        }
    }

    @Test
    void identifiersGeneratedLaterSortAfterEarlierOnes() {
        // The timestamp prefix keeps primary-key inserts append-ordered rather than
        // scattered across the index, which is the point of using a ULID over a UUID.
        String earlier = "pay_" + Ids.ulid(1_700_000_000_000L);
        String later = "pay_" + Ids.ulid(1_700_000_001_000L);

        assertThat(earlier).isLessThan(later);
    }

    @Test
    void bodyUsesCrockfordBase32Only() {
        assertThat(Ids.ulid(System.currentTimeMillis()))
                .matches("[0-9ABCDEFGHJKMNPQRSTVWXYZ]{26}");
    }
}
