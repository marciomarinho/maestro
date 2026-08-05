-- The books.
--
-- Two properties matter more than anything else here, and both are enforced by the
-- database rather than by application code (ADR-0008):
--
--   1. Every journal transaction balances to exactly zero.
--   2. Postings are append-only. A mistake is corrected with a reversing transaction,
--      which is what an auditor expects to see.
--
-- Application defects are ordinary; a ledger that silently stops balancing is not.

-- Chart of accounts ---------------------------------------------------------

CREATE TABLE account (
    id             TEXT PRIMARY KEY,
    kind           TEXT        NOT NULL,
    account_type   TEXT        NOT NULL,
    normal_balance TEXT        NOT NULL,
    merchant_id    TEXT,
    acquirer_id    TEXT,
    currency       CHAR(3)     NOT NULL,
    created_at     TIMESTAMPTZ NOT NULL DEFAULT now(),

    CONSTRAINT account_kind_known CHECK (kind IN (
        'MERCHANT_PAYABLE', 'ACQUIRER_RECEIVABLE', 'PLATFORM_CASH',
        'PLATFORM_FEE_REVENUE', 'REFUND_CLEARING')),
    CONSTRAINT account_type_known CHECK (account_type IN (
        'ASSET', 'LIABILITY', 'REVENUE', 'EXPENSE')),
    CONSTRAINT account_normal_balance_known CHECK (normal_balance IN ('DEBIT', 'CREDIT')),
    CONSTRAINT account_currency_is_uppercase CHECK (currency = upper(currency))
);

CREATE INDEX account_by_merchant ON account (merchant_id) WHERE merchant_id IS NOT NULL;

-- Journal -------------------------------------------------------------------

CREATE TABLE journal_transaction (
    id              TEXT PRIMARY KEY,
    -- The identifier of the event that caused this transaction. Unique, so a replayed
    -- event violates the constraint and is skipped: this single line is the whole
    -- mechanism by which at-least-once delivery yields exactly-once money effects.
    source_event_id TEXT        NOT NULL UNIQUE,
    transaction_type TEXT       NOT NULL,
    payment_id      TEXT,
    reference       TEXT,
    currency        CHAR(3)     NOT NULL,
    occurred_at     TIMESTAMPTZ NOT NULL,
    recorded_at     TIMESTAMPTZ NOT NULL DEFAULT now(),

    CONSTRAINT journal_transaction_type_known CHECK (transaction_type IN (
        'CAPTURE', 'REFUND', 'SETTLEMENT', 'PAYOUT', 'ADJUSTMENT', 'REVERSAL')),
    -- Lets postings reference (id, currency) so a transaction cannot mix currencies.
    CONSTRAINT journal_transaction_id_currency UNIQUE (id, currency)
);

CREATE INDEX journal_transaction_by_payment ON journal_transaction (payment_id, recorded_at);

CREATE TABLE posting (
    id             TEXT PRIMARY KEY,
    transaction_id TEXT        NOT NULL,
    account_id     TEXT        NOT NULL REFERENCES account (id),
    direction      TEXT        NOT NULL,
    amount_minor   BIGINT      NOT NULL,
    currency       CHAR(3)     NOT NULL,
    created_at     TIMESTAMPTZ NOT NULL DEFAULT now(),

    CONSTRAINT posting_direction_known CHECK (direction IN ('DEBIT', 'CREDIT')),
    -- Signed amounts are a modelling mistake here: the direction carries the sign, and
    -- a negative debit is just a credit written confusingly.
    CONSTRAINT posting_amount_positive CHECK (amount_minor > 0),
    -- Referencing the currency as well as the id means a transaction physically cannot
    -- contain postings in two currencies. Referential integrity rather than a trigger.
    CONSTRAINT posting_matches_transaction_currency
        FOREIGN KEY (transaction_id, currency) REFERENCES journal_transaction (id, currency)
);

CREATE INDEX posting_by_transaction ON posting (transaction_id);
CREATE INDEX posting_by_account ON posting (account_id, created_at);

-- Invariant 1: every transaction balances ------------------------------------

CREATE FUNCTION assert_transaction_balances() RETURNS trigger AS $$
DECLARE
    imbalance BIGINT;
BEGIN
    SELECT COALESCE(SUM(CASE WHEN direction = 'DEBIT' THEN amount_minor ELSE -amount_minor END), 0)
      INTO imbalance
      FROM posting
     WHERE transaction_id = NEW.transaction_id;

    IF imbalance <> 0 THEN
        RAISE EXCEPTION
            'Journal transaction % does not balance: debits minus credits = %',
            NEW.transaction_id, imbalance
            USING ERRCODE = 'check_violation';
    END IF;

    RETURN NULL;
END;
$$ LANGUAGE plpgsql;

-- DEFERRABLE INITIALLY DEFERRED is what makes this workable: the postings of one
-- transaction are inserted one row at a time and are legitimately unbalanced in between,
-- so the check runs at COMMIT. An unbalanced transaction cannot be committed by any
-- code path, including a psql session.
CREATE CONSTRAINT TRIGGER posting_transaction_balances
    AFTER INSERT ON posting
    DEFERRABLE INITIALLY DEFERRED
    FOR EACH ROW EXECUTE FUNCTION assert_transaction_balances();

-- Invariant 2: postings are append-only --------------------------------------

CREATE FUNCTION reject_posting_mutation() RETURNS trigger AS $$
BEGIN
    RAISE EXCEPTION
        'Postings are append-only. Correct a mistake with a reversing transaction.'
        USING ERRCODE = 'insufficient_privilege';
END;
$$ LANGUAGE plpgsql;

-- Belt and braces alongside the grants at the bottom of this file. The grants are the
-- real mechanism; this trigger also covers anyone connecting as the owner.
CREATE TRIGGER posting_is_append_only
    BEFORE UPDATE OR DELETE ON posting
    FOR EACH ROW EXECUTE FUNCTION reject_posting_mutation();

-- Authorization holds --------------------------------------------------------

-- An authorization reserves funds; it does not move them, so it produces no postings
-- (ADR-0008). Modelling authorizations as postings is the most common way a payments
-- ledger ends up wrong — it inflates balances with money nobody has.
CREATE TABLE hold (
    payment_id   TEXT PRIMARY KEY,
    merchant_id  TEXT        NOT NULL,
    acquirer_id  TEXT,
    amount_minor BIGINT      NOT NULL,
    currency     CHAR(3)     NOT NULL,
    status       TEXT        NOT NULL,
    expires_at   TIMESTAMPTZ,
    created_at   TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at   TIMESTAMPTZ NOT NULL DEFAULT now(),

    CONSTRAINT hold_amount_positive CHECK (amount_minor > 0),
    CONSTRAINT hold_status_known CHECK (status IN ('ACTIVE', 'CAPTURED', 'RELEASED', 'EXPIRED'))
);

CREATE INDEX hold_active_by_expiry ON hold (expires_at) WHERE status = 'ACTIVE';

-- Pricing --------------------------------------------------------------------

CREATE TABLE fee_schedule (
    id           TEXT PRIMARY KEY,
    merchant_id  TEXT,
    currency     CHAR(3),
    basis_points INTEGER     NOT NULL,
    fixed_minor  BIGINT      NOT NULL,
    created_at   TIMESTAMPTZ NOT NULL DEFAULT now(),

    CONSTRAINT fee_schedule_basis_points_valid CHECK (basis_points BETWEEN 0 AND 10000),
    CONSTRAINT fee_schedule_fixed_non_negative CHECK (fixed_minor >= 0)
);

-- Most specific match wins: merchant + currency, then merchant, then the default below.
CREATE UNIQUE INDEX fee_schedule_default
    ON fee_schedule ((1)) WHERE merchant_id IS NULL AND currency IS NULL;
CREATE UNIQUE INDEX fee_schedule_by_merchant
    ON fee_schedule (merchant_id) WHERE merchant_id IS NOT NULL AND currency IS NULL;
CREATE UNIQUE INDEX fee_schedule_by_merchant_currency
    ON fee_schedule (merchant_id, currency) WHERE merchant_id IS NOT NULL AND currency IS NOT NULL;

INSERT INTO fee_schedule (id, merchant_id, currency, basis_points, fixed_minor)
VALUES ('fee_default', NULL, NULL, 175, 30);

-- Materialised balances ------------------------------------------------------

-- Aggregating every posting on each read does not stay fast, so balances are
-- materialised. That introduces the possibility of drift, which is why a verification
-- job recomputes them from raw postings and alerts on any difference: the ledger is
-- measured continuously rather than assumed correct because a test once passed.
CREATE TABLE account_balance (
    account_id    TEXT PRIMARY KEY REFERENCES account (id),
    balance_minor BIGINT      NOT NULL,
    currency      CHAR(3)     NOT NULL,
    posting_count BIGINT      NOT NULL DEFAULT 0,
    updated_at    TIMESTAMPTZ NOT NULL DEFAULT now()
);

-- Privileges -----------------------------------------------------------------

-- The application connects as maestro_ledger, which is NOT the owner of these tables
-- (ADR-0016). It is granted exactly what it needs and nothing more — note the absence
-- of UPDATE and DELETE on posting. That absence is the enforcement: append-only is a
-- privilege the application does not hold, not a rule it is asked to follow.
GRANT SELECT, INSERT                 ON account             TO maestro_ledger;
GRANT SELECT, INSERT                 ON journal_transaction TO maestro_ledger;
GRANT SELECT, INSERT                 ON posting             TO maestro_ledger;
GRANT SELECT, INSERT, UPDATE         ON hold                TO maestro_ledger;
GRANT SELECT                         ON fee_schedule        TO maestro_ledger;
GRANT SELECT, INSERT, UPDATE         ON account_balance     TO maestro_ledger;
