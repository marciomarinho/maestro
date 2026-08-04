-- Tenancy -------------------------------------------------------------------

CREATE TABLE merchant (
    id               TEXT PRIMARY KEY,
    name             TEXT        NOT NULL,
    status           TEXT        NOT NULL DEFAULT 'ACTIVE',
    default_currency CHAR(3)     NOT NULL,
    created_at       TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT merchant_status_known CHECK (status IN ('ACTIVE', 'SUSPENDED'))
);

-- An API key's secret is stored only as a hash. It is returned once at creation and
-- is unrecoverable thereafter; the prefix exists so a key can be identified in a
-- support conversation without revealing it.
CREATE TABLE api_key (
    id          TEXT PRIMARY KEY,
    merchant_id TEXT        NOT NULL REFERENCES merchant (id),
    key_prefix  TEXT        NOT NULL,
    key_hash    TEXT        NOT NULL UNIQUE,
    role        TEXT        NOT NULL,
    created_at  TIMESTAMPTZ NOT NULL DEFAULT now(),
    revoked_at  TIMESTAMPTZ
);

CREATE INDEX api_key_by_merchant ON api_key (merchant_id);

-- Payments ------------------------------------------------------------------

CREATE TABLE payment (
    id                       TEXT PRIMARY KEY,
    merchant_id              TEXT        NOT NULL REFERENCES merchant (id),
    amount_minor             BIGINT      NOT NULL,
    currency                 CHAR(3)     NOT NULL,
    captured_amount_minor    BIGINT      NOT NULL DEFAULT 0,
    refunded_amount_minor    BIGINT      NOT NULL DEFAULT 0,
    card_token               TEXT        NOT NULL,
    card_network             TEXT        NOT NULL,
    card_last4               CHAR(4),
    card_country             CHAR(2),
    status                   TEXT        NOT NULL,
    capture_method           TEXT        NOT NULL,
    reference                TEXT,
    metadata                 JSONB       NOT NULL DEFAULT '{}'::jsonb,
    acquirer_id              TEXT,
    acquirer_reference       TEXT,
    authorization_code       TEXT,
    decline_code             TEXT,
    failure_reason           TEXT,
    authorized_at            TIMESTAMPTZ,
    authorization_expires_at TIMESTAMPTZ,
    created_at               TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at               TIMESTAMPTZ NOT NULL DEFAULT now(),

    -- Domain invariants live in the database, so they hold regardless of which code
    -- path writes the row (docs/domain.md, section 5).
    CONSTRAINT payment_amount_positive
        CHECK (amount_minor > 0),
    CONSTRAINT payment_captured_within_authorized
        CHECK (captured_amount_minor >= 0 AND captured_amount_minor <= amount_minor),
    CONSTRAINT payment_refunded_within_captured
        CHECK (refunded_amount_minor >= 0 AND refunded_amount_minor <= captured_amount_minor),
    CONSTRAINT payment_currency_is_uppercase
        CHECK (currency = upper(currency)),
    CONSTRAINT payment_capture_method_known
        CHECK (capture_method IN ('AUTOMATIC', 'MANUAL')),
    CONSTRAINT payment_status_known
        CHECK (status IN ('CREATED', 'AUTHORIZING', 'AUTHORIZED', 'DECLINED', 'FAILED',
                          'CAPTURING', 'CAPTURED', 'PARTIALLY_REFUNDED', 'REFUNDED',
                          'VOIDED', 'EXPIRED', 'CANCELLED'))
);

-- Supports the merchant-scoped payment list, which is always ordered newest first.
CREATE INDEX payment_by_merchant_created ON payment (merchant_id, created_at DESC, id DESC);

-- Idempotency ---------------------------------------------------------------

-- Written in the same transaction as the effect it guards, so there is no window in
-- which a payment exists without the key that would suppress its duplicate (ADR-0013).
CREATE TABLE idempotency_record (
    merchant_id     TEXT        NOT NULL,
    endpoint        TEXT        NOT NULL,
    idempotency_key TEXT        NOT NULL,
    request_hash    TEXT        NOT NULL,
    status          TEXT        NOT NULL,
    response_status INTEGER,
    response_body   TEXT,
    resource_id     TEXT,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    completed_at    TIMESTAMPTZ,
    PRIMARY KEY (merchant_id, endpoint, idempotency_key),
    CONSTRAINT idempotency_status_known CHECK (status IN ('IN_PROGRESS', 'COMPLETED'))
);

CREATE INDEX idempotency_by_age ON idempotency_record (created_at);

-- Transactional outbox ------------------------------------------------------

-- Lives beside the data whose transaction it shares; that adjacency is the entire
-- mechanism (ADR-0004).
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

-- Partial index: the relay only ever asks for unpublished rows, and this keeps that
-- query independent of how much history the table has accumulated.
CREATE INDEX outbox_unpublished ON outbox_event (created_at, id) WHERE published_at IS NULL;
