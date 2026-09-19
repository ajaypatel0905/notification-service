CREATE TABLE tenants (
    id                      UUID PRIMARY KEY,
    slug                    VARCHAR(64)  NOT NULL UNIQUE,
    name                    VARCHAR(200) NOT NULL,
    status                  VARCHAR(16)  NOT NULL,
    rate_limit_per_second   INTEGER,
    rate_limit_burst        INTEGER,
    created_at              TIMESTAMPTZ  NOT NULL,
    updated_at              TIMESTAMPTZ  NOT NULL
);

CREATE TABLE api_keys (
    id          UUID PRIMARY KEY,
    tenant_id   UUID REFERENCES tenants(id),
    role        VARCHAR(32)  NOT NULL,
    key_hash    VARCHAR(64)  NOT NULL UNIQUE,
    key_prefix  VARCHAR(16)  NOT NULL,
    label       VARCHAR(200),
    created_at  TIMESTAMPTZ  NOT NULL,
    revoked_at  TIMESTAMPTZ
);
CREATE INDEX idx_api_keys_tenant ON api_keys(tenant_id);

CREATE TABLE platform_settings (
    setting_key   VARCHAR(64) PRIMARY KEY,
    setting_value VARCHAR(255) NOT NULL,
    updated_at    TIMESTAMPTZ NOT NULL
);

CREATE TABLE channel_configs (
    id                    UUID PRIMARY KEY,
    tenant_id             UUID NOT NULL REFERENCES tenants(id),
    channel               VARCHAR(16) NOT NULL,
    enabled               BOOLEAN NOT NULL DEFAULT TRUE,
    provider              VARCHAR(64) NOT NULL,
    settings              JSONB NOT NULL DEFAULT '{}'::jsonb,
    max_attempts          INTEGER,
    created_at            TIMESTAMPTZ NOT NULL,
    updated_at            TIMESTAMPTZ NOT NULL,
    UNIQUE (tenant_id, channel)
);

CREATE TABLE templates (
    id          UUID PRIMARY KEY,
    tenant_id   UUID NOT NULL REFERENCES tenants(id),
    code        VARCHAR(64)  NOT NULL,
    channel     VARCHAR(16)  NOT NULL,
    name        VARCHAR(200) NOT NULL,
    subject     TEXT,
    body        TEXT NOT NULL,
    version     INTEGER NOT NULL,
    active      BOOLEAN NOT NULL DEFAULT TRUE,
    created_at  TIMESTAMPTZ NOT NULL,
    updated_at  TIMESTAMPTZ NOT NULL,
    UNIQUE (tenant_id, code, channel)
);

CREATE TABLE template_versions (
    id           UUID PRIMARY KEY,
    template_id  UUID NOT NULL REFERENCES templates(id),
    version      INTEGER NOT NULL,
    subject      TEXT,
    body         TEXT NOT NULL,
    created_at   TIMESTAMPTZ NOT NULL,
    UNIQUE (template_id, version)
);

CREATE TABLE notifications (
    id                   UUID PRIMARY KEY,
    tenant_id            UUID NOT NULL REFERENCES tenants(id),
    channel              VARCHAR(16)  NOT NULL,
    recipient            VARCHAR(320) NOT NULL,
    template_id          UUID REFERENCES templates(id),
    template_version     INTEGER,
    subject              TEXT,
    body                 TEXT NOT NULL,
    variables            JSONB,
    metadata             JSONB,
    idempotency_key      VARCHAR(128) NOT NULL,
    status               VARCHAR(16)  NOT NULL,
    priority             SMALLINT     NOT NULL DEFAULT 0,
    scheduled_at         TIMESTAMPTZ,
    next_attempt_at      TIMESTAMPTZ  NOT NULL,
    attempt_count        INTEGER      NOT NULL DEFAULT 0,
    max_attempts         INTEGER      NOT NULL,
    leased_by            VARCHAR(64),
    lease_expires_at     TIMESTAMPTZ,
    provider_message_id  VARCHAR(128),
    last_error           TEXT,
    version              BIGINT       NOT NULL DEFAULT 0,
    created_at           TIMESTAMPTZ  NOT NULL,
    updated_at           TIMESTAMPTZ  NOT NULL,
    sent_at              TIMESTAMPTZ,
    delivered_at         TIMESTAMPTZ,
    failed_at            TIMESTAMPTZ,
    UNIQUE (tenant_id, idempotency_key)
);
-- Claim path: due work per tenant, highest priority first, oldest first.
CREATE INDEX idx_notifications_claim ON notifications (tenant_id, status, next_attempt_at, priority DESC)
    WHERE status IN ('QUEUED', 'SCHEDULED');
CREATE INDEX idx_notifications_lease ON notifications (lease_expires_at) WHERE status = 'PROCESSING';
CREATE INDEX idx_notifications_tenant_created ON notifications (tenant_id, created_at DESC);
CREATE INDEX idx_notifications_provider_msg ON notifications (provider_message_id) WHERE provider_message_id IS NOT NULL;

CREATE TABLE delivery_attempts (
    id                   UUID PRIMARY KEY,
    notification_id      UUID NOT NULL REFERENCES notifications(id),
    tenant_id            UUID NOT NULL,
    attempt_no           INTEGER NOT NULL,
    outcome              VARCHAR(24) NOT NULL,
    provider             VARCHAR(64) NOT NULL,
    provider_message_id  VARCHAR(128),
    error_code           VARCHAR(64),
    error_message        TEXT,
    worker_id            VARCHAR(64) NOT NULL,
    started_at           TIMESTAMPTZ NOT NULL,
    finished_at          TIMESTAMPTZ NOT NULL,
    UNIQUE (notification_id, attempt_no)
);
CREATE INDEX idx_attempts_tenant_started ON delivery_attempts (tenant_id, started_at);

CREATE TABLE notification_events (
    id               BIGSERIAL PRIMARY KEY,
    notification_id  UUID NOT NULL REFERENCES notifications(id),
    tenant_id        UUID NOT NULL,
    event_type       VARCHAR(32) NOT NULL,
    from_status      VARCHAR(16),
    to_status        VARCHAR(16),
    actor            VARCHAR(64) NOT NULL,
    detail           TEXT,
    occurred_at      TIMESTAMPTZ NOT NULL
);
CREATE INDEX idx_events_notification ON notification_events (notification_id, id);

CREATE TABLE inbox_messages (
    id               UUID PRIMARY KEY,
    tenant_id        UUID NOT NULL REFERENCES tenants(id),
    notification_id  UUID NOT NULL UNIQUE REFERENCES notifications(id),
    recipient        VARCHAR(320) NOT NULL,
    subject          TEXT,
    body             TEXT NOT NULL,
    read_at          TIMESTAMPTZ,
    created_at       TIMESTAMPTZ NOT NULL
);
CREATE INDEX idx_inbox_recipient ON inbox_messages (tenant_id, recipient, created_at DESC);

INSERT INTO platform_settings (setting_key, setting_value, updated_at) VALUES
    ('default_rate_limit_per_second', '50', now()),
    ('default_rate_limit_burst', '100', now()),
    ('default_max_attempts', '5', now()),
    ('max_rate_limit_per_second', '1000', now());
