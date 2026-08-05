package dev.maestro.ledger.web;

import dev.maestro.ledger.core.LedgerQueries;
import dev.maestro.ledger.hold.HoldRepository;
import dev.maestro.ledger.verification.BalanceVerifier;
import java.util.List;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Read access to the books for operators, demos and runbooks.
 *
 * <p>Everything here is under {@code /ops} rather than {@code /v1} because it is not
 * merchant-scoped. The merchant-facing {@code GET /v1/balances} described in the API design
 * needs JWT authentication, which arrives in Phase 6; serving it now would mean reading
 * {@code payment.api_key} from this service, and the database deliberately refuses that
 * cross-schema access.
 *
 * <p>Nothing here can write. Corrections to a ledger are reversing transactions raised
 * deliberately, never an operator adjusting a balance through an endpoint.
 */
@RestController
@RequestMapping("/ops/ledger")
public class OpsController {

    private final LedgerQueries queries;
    private final HoldRepository holds;
    private final BalanceVerifier verifier;

    public OpsController(LedgerQueries queries, HoldRepository holds, BalanceVerifier verifier) {
        this.queries = queries;
        this.holds = holds;
        this.verifier = verifier;
    }

    /** Every posting made against a payment, in the order it was recorded. */
    @GetMapping("/payments/{paymentId}")
    public PaymentLedgerView payment(@PathVariable String paymentId) {
        return new PaymentLedgerView(
                paymentId,
                queries.transactionsForPayment(paymentId),
                holds.find(paymentId).orElse(null));
    }

    @GetMapping("/balances")
    public List<LedgerQueries.BalanceView> balances() {
        return queries.balances();
    }

    /** Runs the verification immediately rather than waiting for the schedule. */
    @PostMapping("/verify")
    public BalanceVerifier.VerificationResult verify() {
        return verifier.verify();
    }

    @GetMapping("/verify")
    public BalanceVerifier.VerificationResult lastVerification() {
        return verifier.latest();
    }

    public record PaymentLedgerView(
            String paymentId,
            List<LedgerQueries.TransactionView> transactions,
            HoldRepository.Hold hold) {
    }
}
