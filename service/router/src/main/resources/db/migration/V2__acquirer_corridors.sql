-- Acquirer corridors --------------------------------------------------------

-- What the platform has agreed with each acquiring bank, per corridor.
--
-- A corridor is a card network and a currency — 'VISA:AUD'. Cost is a property of that
-- pair rather than of an acquirer as a whole, because acquiring agreements price
-- domestic Visa and cross-border Mastercard quite differently (ADR-0007).
--
-- This table holds only what somebody negotiated. It deliberately does not hold health,
-- capacity or any other observation: those are learned from live traffic, and a column
-- here for something the router measures for itself would be a second source of truth
-- that could disagree with the first.
CREATE TABLE acquirer_corridor (
    acquirer_id     TEXT         NOT NULL,
    corridor        TEXT         NOT NULL,
    cost_bps        NUMERIC(6, 2) NOT NULL,
    fixed_fee_minor BIGINT       NOT NULL DEFAULT 0,
    enabled         BOOLEAN      NOT NULL DEFAULT TRUE,
    updated_at      TIMESTAMPTZ  NOT NULL DEFAULT now(),

    CONSTRAINT acquirer_corridor_pk PRIMARY KEY (acquirer_id, corridor),
    CONSTRAINT acquirer_corridor_cost_sane
        CHECK (cost_bps >= 0 AND cost_bps <= 1000),
    CONSTRAINT acquirer_corridor_fixed_fee_not_negative
        CHECK (fixed_fee_minor >= 0),
    -- 'NETWORK:CURRENCY', upper case, so the key the router builds from a payment and
    -- the key an operator types into this table cannot differ by presentation.
    CONSTRAINT acquirer_corridor_well_formed
        CHECK (corridor ~ '^[A-Z]+:[A-Z]{3}$')
);

-- Selection reads every enabled corridor on the hot path for each authorization.
CREATE INDEX acquirer_corridor_enabled ON acquirer_corridor (corridor) WHERE enabled;

-- Cascading failover --------------------------------------------------------

-- An operation may now span several attempts: a technical failure on one acquirer is
-- re-offered to the next-best, with a fresh attempt_no and therefore a fresh acquirer
-- idempotency key (ADR-0007).
--
-- Which creates a question the single-attempt design never had to answer: on redelivery
-- of a command, has this operation already been dealt with? "Does an attempt exist" is no
-- longer the same question, because a completed attempt might be one the router walked
-- away from on its way to another acquirer.
--
-- So the attempt that ends the operation says so. Set in the same transaction that writes
-- the outbox event, which means a payment whose outcome was published always has exactly
-- one final attempt, and a redelivered command can be recognised and dropped by looking
-- for it.
ALTER TABLE payment_attempt
    ADD COLUMN final_attempt BOOLEAN NOT NULL DEFAULT FALSE;

CREATE INDEX payment_attempt_answered
    ON payment_attempt (payment_id, operation) WHERE final_attempt;

-- Health snapshots ----------------------------------------------------------

-- Advisory only. Health is derived from live traffic and lives in memory; this table
-- exists so that a router restarting mid-incident does not begin blind and send a
-- burst of traffic to the acquirer it had just learned to avoid.
--
-- It is explicitly not a shared view of health. Instances converge independently
-- (ADR-0007), and a snapshot written by another instance is treated as a starting
-- hint that live evidence overwrites within seconds.
CREATE TABLE corridor_health_snapshot (
    acquirer_id            TEXT         NOT NULL,
    corridor               TEXT         NOT NULL,
    approval_rate          NUMERIC(6, 4) NOT NULL,
    technical_failure_rate NUMERIC(6, 4) NOT NULL,
    latency_ms             NUMERIC(9, 2) NOT NULL,
    samples                BIGINT       NOT NULL,
    observed_at            TIMESTAMPTZ  NOT NULL,

    CONSTRAINT corridor_health_snapshot_pk PRIMARY KEY (acquirer_id, corridor),
    CONSTRAINT corridor_health_snapshot_rates_are_fractions
        CHECK (approval_rate BETWEEN 0 AND 1 AND technical_failure_rate BETWEEN 0 AND 1),
    CONSTRAINT corridor_health_snapshot_samples_not_negative
        CHECK (samples >= 0)
);
