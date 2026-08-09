-- The routing audit trail, as merchants see it -------------------------------

-- A projection of events the router publishes, not a copy of the router's table.
--
-- The attempt history is owned by the router and lives in its schema, which this
-- service's database role deliberately cannot read (ADR-0010, ADR-0014). But the API
-- design puts `GET /v1/payments/{id}/attempts` on the merchant-facing surface, because
-- that is where merchants already are and one base URL is the contract.
--
-- So the router publishes each attempt and this service keeps its own copy (ADR-0017).
-- The alternative — calling the router synchronously on every read — would make the
-- merchant-facing read path depend on the availability of the service most likely to be
-- degraded during precisely the incident someone is asking about.
CREATE TABLE payment_attempt_view (
    payment_id       TEXT        NOT NULL,
    merchant_id      TEXT        NOT NULL,
    operation        TEXT        NOT NULL,
    attempt_no       INTEGER     NOT NULL,
    acquirer_id      TEXT        NOT NULL,
    corridor         TEXT        NOT NULL,
    selection_reason TEXT        NOT NULL,
    health_score     NUMERIC(6, 4),
    outcome          TEXT        NOT NULL,
    response_code    TEXT,
    response_message TEXT,
    latency_ms       INTEGER,
    final_attempt    BOOLEAN     NOT NULL,
    recorded_at      TIMESTAMPTZ NOT NULL DEFAULT now(),

    -- The natural key of an attempt, which is what makes the projection idempotent:
    -- events are delivered at least once (ADR-0006), so a redelivery must overwrite
    -- rather than accumulate.
    CONSTRAINT payment_attempt_view_pk PRIMARY KEY (payment_id, operation, attempt_no)
);

-- Every read is "the attempts for this payment, in order", and every read is scoped to a
-- merchant before it is served.
CREATE INDEX payment_attempt_view_by_payment
    ON payment_attempt_view (merchant_id, payment_id, recorded_at);
