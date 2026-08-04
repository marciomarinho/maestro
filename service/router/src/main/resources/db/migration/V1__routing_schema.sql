-- Routing attempts ----------------------------------------------------------

-- The audit trail behind every routing decision, and one of the more interesting
-- tables in the platform. Recording *why* an acquirer was chosen, and the score that
-- justified it at the moment of choosing, turns "why did this payment go to
-- Northbank?" from an investigation into a query (ADR-0007).
--
-- It is also the router's idempotency guard: the unique key below means a redelivered
-- command cannot start a second attempt for the same operation.
CREATE TABLE payment_attempt (
    id                        TEXT PRIMARY KEY,
    payment_id                TEXT        NOT NULL,
    merchant_id               TEXT        NOT NULL,
    attempt_no                INTEGER     NOT NULL,
    operation                 TEXT        NOT NULL,
    acquirer_id               TEXT        NOT NULL,
    corridor                  TEXT        NOT NULL,
    selection_reason          TEXT        NOT NULL,
    health_score_at_selection NUMERIC(6, 4),
    outcome                   TEXT        NOT NULL,
    response_code             TEXT,
    response_message          TEXT,
    latency_ms                INTEGER,
    acquirer_reference        TEXT,
    started_at                TIMESTAMPTZ NOT NULL DEFAULT now(),
    completed_at              TIMESTAMPTZ,

    CONSTRAINT payment_attempt_unique_per_operation
        UNIQUE (payment_id, operation, attempt_no),
    CONSTRAINT payment_attempt_no_positive
        CHECK (attempt_no >= 1),
    CONSTRAINT payment_attempt_operation_known
        CHECK (operation IN ('AUTHORIZE', 'CAPTURE', 'REFUND', 'VOID')),
    CONSTRAINT payment_attempt_selection_reason_known
        CHECK (selection_reason IN ('BEST_SCORE', 'EXPLORATION', 'FAILOVER', 'PINNED')),
    CONSTRAINT payment_attempt_outcome_known
        CHECK (outcome IN ('IN_FLIGHT', 'APPROVED', 'DECLINED_BUSINESS', 'DECLINED_TECHNICAL',
                           'TIMEOUT', 'THROTTLED'))
);

CREATE INDEX payment_attempt_by_payment ON payment_attempt (payment_id, started_at);

-- Transactional outbox ------------------------------------------------------

-- The router publishes its outcomes through an outbox for the same reason payment-api
-- does: an acquirer's answer recorded in the database but never published would leave
-- a payment stuck in AUTHORIZING forever (ADR-0004).
CREATE TABLE outbox_event (
    id             TEXT PRIMARY KEY,
    aggregate_type TEXT        NOT NULL,
    aggregate_id   TEXT        NOT NULL,
    topic          TEXT        NOT NULL,
    event_type     TEXT        NOT NULL,
    payload        TEXT        NOT NULL,
    trace_parent   TEXT,
    created_at     TIMESTAMPTZ NOT NULL DEFAULT now(),
    published_at   TIMESTAMPTZ
);

CREATE INDEX outbox_unpublished ON outbox_event (created_at, id) WHERE published_at IS NULL;
