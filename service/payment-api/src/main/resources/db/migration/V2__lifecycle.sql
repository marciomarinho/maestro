-- Refunds, and the reservation column that makes concurrent refunds safe.

CREATE TABLE refund (
    id                 TEXT PRIMARY KEY,
    payment_id         TEXT        NOT NULL REFERENCES payment (id),
    merchant_id        TEXT        NOT NULL REFERENCES merchant (id),
    amount_minor       BIGINT      NOT NULL,
    currency           CHAR(3)     NOT NULL,
    reason             TEXT,
    status             TEXT        NOT NULL,
    acquirer_reference TEXT,
    failure_reason     TEXT,
    created_at         TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at         TIMESTAMPTZ NOT NULL DEFAULT now(),

    CONSTRAINT refund_amount_positive CHECK (amount_minor > 0),
    CONSTRAINT refund_status_known CHECK (status IN ('PENDING', 'SUCCEEDED', 'FAILED'))
);

CREATE INDEX refund_by_payment ON refund (payment_id, created_at);
CREATE INDEX refund_by_merchant ON refund (merchant_id, created_at DESC);

-- Refunds are reserved when requested and settled when the acquirer confirms.
--
-- Without the reservation column, two concurrent refunds would each check the settled
-- total, each find room, and together exceed what was captured. Reserving up front means
-- the second request fails the guard immediately; a refund that later fails gives its
-- reservation back.
ALTER TABLE payment
    ADD COLUMN refund_reserved_minor BIGINT NOT NULL DEFAULT 0;

ALTER TABLE payment
    ADD CONSTRAINT payment_reservation_within_captured
        CHECK (refund_reserved_minor >= 0 AND refund_reserved_minor <= captured_amount_minor),
    ADD CONSTRAINT payment_refunded_within_reserved
        CHECK (refunded_amount_minor <= refund_reserved_minor);

-- Supports the expiry sweeper, which only ever looks at authorizations that have lapsed.
CREATE INDEX payment_awaiting_capture
    ON payment (authorization_expires_at)
    WHERE status = 'AUTHORIZED';
