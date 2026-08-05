package dev.maestro.ledger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.maestro.domain.id.Ids;
import dev.maestro.domain.money.Money;
import dev.maestro.ledger.core.AccountRef;
import dev.maestro.ledger.core.JournalEntry;
import dev.maestro.ledger.core.LedgerQueries;
import dev.maestro.ledger.core.LedgerRepository;
import dev.maestro.ledger.hold.HoldRepository;
import dev.maestro.ledger.verification.BalanceVerifier;
import dev.maestro.testing.MaestroInfrastructure;
import java.sql.Connection;
import java.sql.SQLException;
import java.time.Instant;
import java.util.Currency;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.stream.IntStream;
import javax.sql.DataSource;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * The invariants that make this a ledger rather than a table of numbers.
 *
 * <p>Each of these exercises a guarantee the <em>database</em> makes, not one the
 * application makes. That distinction is the point: application code has defects, and a
 * ledger that stops balancing because of one is not recoverable by reasoning.
 *
 * <p>Nothing is truncated between tests. The application role cannot delete a posting —
 * which is itself one of the guarantees under test — so the suite is written to be
 * order-independent instead.
 */
@SpringBootTest
class LedgerIntegrityIntegrationTest {

    private static final Currency AUD = Currency.getInstance("AUD");

    @Autowired
    private LedgerRepository ledger;

    @Autowired
    private LedgerQueries queries;

    @Autowired
    private HoldRepository holds;

    @Autowired
    private BalanceVerifier verifier;

    @Autowired
    private DataSource dataSource;

    @DynamicPropertySource
    static void infrastructure(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", MaestroInfrastructure::jdbcUrl);
        registry.add("spring.datasource.username", () -> "maestro_ledger");
        registry.add("spring.datasource.password", () -> "maestro_ledger");
        registry.add("spring.flyway.url", MaestroInfrastructure::jdbcUrl);
        registry.add("spring.flyway.user", () -> "maestro_ledger_migrator");
        registry.add("spring.flyway.password", () -> "maestro_ledger_migrator");
        registry.add("spring.kafka.bootstrap-servers", MaestroInfrastructure::kafkaBootstrapServers);
    }

    @Test
    @DisplayName("an unbalanced transaction cannot be committed, even by raw SQL")
    void unbalancedTransactionIsRejectedAtCommit() throws SQLException {
        // Bypasses the application entirely. The deferred constraint trigger fires at
        // COMMIT, so the insert appears to succeed and the transaction is then refused —
        // which is exactly what makes multi-row entries legal in flight and unbalanced
        // ones impossible to persist.
        String transactionId = Ids.generate("txn");

        try (Connection connection = dataSource.getConnection()) {
            connection.setAutoCommit(false);
            insertAccount(connection, AccountRef.platformFeeRevenue(AUD));
            insertTransaction(connection, transactionId, Ids.event());
            insertPosting(connection, transactionId, AccountRef.platformFeeRevenue(AUD), "DEBIT", 100);

            assertThatThrownBy(connection::commit)
                    .isInstanceOf(SQLException.class)
                    .hasMessageContaining("does not balance");
        }

        assertThat(queries.postingsFor(transactionId)).isEmpty();
    }

    @Test
    @DisplayName("postings cannot be updated or deleted by the application role")
    void postingsAreAppendOnly() throws SQLException {
        // ADR-0008 promises this is impossible rather than merely forbidden. The
        // application connects as a role holding SELECT and INSERT and nothing else, so
        // the absence of the privilege is the enforcement.
        String paymentId = Ids.payment();
        recordCapture(paymentId, Money.of(1000, AUD), Money.of(50, AUD));
        String transactionId = queries.transactionsForPayment(paymentId).getFirst().id();

        try (Connection connection = dataSource.getConnection()) {
            assertThatThrownBy(() -> {
                try (var statement = connection.prepareStatement(
                        "UPDATE ledger.posting SET amount_minor = 1 WHERE transaction_id = ?")) {
                    statement.setString(1, transactionId);
                    statement.executeUpdate();
                }
            }).isInstanceOf(SQLException.class);

            assertThatThrownBy(() -> {
                try (var statement = connection.prepareStatement(
                        "DELETE FROM ledger.posting WHERE transaction_id = ?")) {
                    statement.setString(1, transactionId);
                    statement.executeUpdate();
                }
            }).isInstanceOf(SQLException.class);
        }

        assertThat(queries.postingsFor(transactionId)).hasSize(3);
    }

    @Test
    @DisplayName("a transaction cannot mix currencies")
    void currenciesCannotBeMixedWithinATransaction() {
        // Caught in the builder before it reaches the database; the composite foreign key
        // on (transaction_id, currency) is the backstop if it ever did.
        assertThatThrownBy(() -> JournalEntry
                .forEvent(Ids.event(), JournalEntry.TransactionType.CAPTURE, Instant.now())
                .debit(AccountRef.acquirerReceivable("northbank", AUD), Money.of(100, AUD))
                .credit(AccountRef.merchantPayable("mch_x", Currency.getInstance("USD")),
                        Money.of(100, "USD"))
                .build())
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("cannot mix");
    }

    @Test
    @DisplayName("replaying an event records the transaction exactly once")
    void replayedEventsAreRecordedOnce() {
        String paymentId = Ids.payment();
        String eventId = Ids.event();

        boolean first = ledger.record(captureEntry(eventId, paymentId, Money.of(2000, AUD), Money.of(65, AUD)));
        boolean second = ledger.record(captureEntry(eventId, paymentId, Money.of(2000, AUD), Money.of(65, AUD)));

        assertThat(first).isTrue();
        assertThat(second).as("the unique constraint on source_event_id absorbs the replay").isFalse();
        assertThat(queries.transactionsForPayment(paymentId)).hasSize(1);
    }

    @Test
    @DisplayName("concurrent replays of one event still record it exactly once")
    void concurrentReplaysRecordOnce() throws Exception {
        String paymentId = Ids.payment();
        String eventId = Ids.event();
        int concurrency = 8;

        try (ExecutorService pool = Executors.newVirtualThreadPerTaskExecutor()) {
            var results = pool.invokeAll(IntStream.range(0, concurrency)
                    .<Callable<Boolean>>mapToObj(i -> () -> {
                        try {
                            return ledger.record(captureEntry(
                                    eventId, paymentId, Money.of(2000, AUD), Money.of(65, AUD)));
                        } catch (RuntimeException e) {
                            // A loser of the race may surface as a constraint violation
                            // rather than a clean false; either way it recorded nothing.
                            return false;
                        }
                    })
                    .toList());

            long recorded = results.stream().filter(future -> {
                try {
                    return future.get();
                } catch (Exception e) {
                    return false;
                }
            }).count();

            assertThat(recorded).isEqualTo(1);
            assertThat(queries.transactionsForPayment(paymentId)).hasSize(1);
        }
    }

    @Test
    @DisplayName("materialised balances always agree with the postings that produced them")
    void verificationReportsNoDrift() {
        // Writes a randomised but deterministic workload, then recomputes every balance
        // from raw postings and compares. Drift here is not a slow-burning inaccuracy; it
        // means the books are wrong.
        for (int i = 0; i < 25; i++) {
            long gross = 500L + (i * 137L);
            recordCapture(Ids.payment(), Money.of(gross, AUD), Money.of(gross / 20, AUD));
        }

        BalanceVerifier.VerificationResult result = verifier.verify();

        assertThat(result.drifts()).isEmpty();
        assertThat(result.currencyImbalances())
                .as("every posting in the ledger must sum to zero per currency")
                .isEmpty();
        assertThat(result.isClean()).isTrue();
    }

    @Test
    @DisplayName("a hold is placed once and released once, however many times events arrive")
    void holdLifecycleIsIdempotent() {
        String paymentId = Ids.payment();

        assertThat(holds.place(paymentId, "mch_demo", "northbank", Money.of(1500, AUD),
                Instant.now().plusSeconds(3600))).isTrue();
        assertThat(holds.place(paymentId, "mch_demo", "northbank", Money.of(1500, AUD),
                Instant.now().plusSeconds(3600)))
                .as("a redelivered authorization must not create a second hold")
                .isFalse();

        assertThat(holds.find(paymentId).orElseThrow().status())
                .isEqualTo(HoldRepository.HoldStatus.ACTIVE);

        assertThat(holds.release(paymentId, HoldRepository.HoldStatus.RELEASED)).isTrue();
        assertThat(holds.release(paymentId, HoldRepository.HoldStatus.RELEASED))
                .as("releasing an already-released hold changes nothing")
                .isFalse();

        assertThat(holds.find(paymentId).orElseThrow().status())
                .isEqualTo(HoldRepository.HoldStatus.RELEASED);
    }

    @Test
    @DisplayName("an authorization produces a hold and no postings")
    void authorizationsMoveNoMoney() {
        // The modelling decision at the heart of ADR-0008. If this ever starts producing
        // postings, receivables and merchant balances are being inflated with money
        // nobody has.
        String paymentId = Ids.payment();

        holds.place(paymentId, "mch_demo", "northbank", Money.of(9900, AUD), null);

        assertThat(queries.transactionsForPayment(paymentId)).isEmpty();
        assertThat(holds.find(paymentId)).isPresent();
    }

    // --- helpers -----------------------------------------------------------

    private void recordCapture(String paymentId, Money gross, Money fee) {
        ledger.record(captureEntry(Ids.event(), paymentId, gross, fee));
    }

    private static JournalEntry captureEntry(
            String eventId, String paymentId, Money gross, Money fee) {
        return JournalEntry
                .forEvent(eventId, JournalEntry.TransactionType.CAPTURE, Instant.now())
                .payment(paymentId)
                .debit(AccountRef.acquirerReceivable("northbank", AUD), gross)
                .credit(AccountRef.merchantPayable("mch_demo", AUD), gross.minus(fee))
                .credit(AccountRef.platformFeeRevenue(AUD), fee)
                .build();
    }

    private static void insertAccount(Connection connection, AccountRef account) throws SQLException {
        try (var statement = connection.prepareStatement("""
                INSERT INTO ledger.account
                    (id, kind, account_type, normal_balance, currency)
                VALUES (?, ?, ?, ?, ?) ON CONFLICT (id) DO NOTHING
                """)) {
            statement.setString(1, account.id());
            statement.setString(2, account.kind().name());
            statement.setString(3, account.kind().type().name());
            statement.setString(4, account.kind().normalBalance().name());
            statement.setString(5, account.currency().getCurrencyCode());
            statement.executeUpdate();
        }
    }

    private static void insertTransaction(Connection connection, String id, String eventId)
            throws SQLException {
        try (var statement = connection.prepareStatement("""
                INSERT INTO ledger.journal_transaction
                    (id, source_event_id, transaction_type, currency, occurred_at)
                VALUES (?, ?, 'ADJUSTMENT', 'AUD', now())
                """)) {
            statement.setString(1, id);
            statement.setString(2, eventId);
            statement.executeUpdate();
        }
    }

    private static void insertPosting(
            Connection connection, String transactionId, AccountRef account, String direction, long amount)
            throws SQLException {
        try (var statement = connection.prepareStatement("""
                INSERT INTO ledger.posting
                    (id, transaction_id, account_id, direction, amount_minor, currency)
                VALUES (?, ?, ?, ?, ?, 'AUD')
                """)) {
            statement.setString(1, Ids.generate("pst"));
            statement.setString(2, transactionId);
            statement.setString(3, account.id());
            statement.setString(4, direction);
            statement.setLong(5, amount);
            statement.executeUpdate();
        }
    }
}
