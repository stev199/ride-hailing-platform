-- Phase 1 schema subset (B-01..B-05, P1-5). Flyway up-migrations only; schema changes
-- are forward-fix (see DECISIONS). Re-run safe due to existence checks not needed on
-- first version, kept minimal.

CREATE TABLE app_user (
    id            UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    phone         TEXT UNIQUE,
    email         TEXT UNIQUE,
    password_hash TEXT,
    display_name  TEXT NOT NULL,
    role          TEXT NOT NULL CHECK (role IN
        ('RIDER','DRIVER','ADMIN_SUPER','ADMIN_OPS','ADMIN_SUPPORT','ADMIN_FINANCE','ADMIN_VERIFIER','ADMIN_READONLY')),
    status        TEXT NOT NULL DEFAULT 'ACTIVE' CHECK (status IN ('ACTIVE','BANNED_TEMP','BANNED_PERM','DELETED')),
    locale        TEXT NOT NULL DEFAULT 'en',
    created_at    TIMESTAMPTZ NOT NULL DEFAULT now(),
    deleted_at    TIMESTAMPTZ
);

CREATE TABLE device (
    id          UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id     UUID NOT NULL REFERENCES app_user(id),
    device_name TEXT NOT NULL,
    mesh_public_key BYTEA,
    last_seen   TIMESTAMPTZ,
    created_at  TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE event_log (
    seq         BIGSERIAL PRIMARY KEY,
    event_id    UUID NOT NULL DEFAULT gen_random_uuid(),
    type        TEXT NOT NULL,
    actor_id    UUID REFERENCES app_user(id),
    payload     JSONB NOT NULL,
    occurred_at TIMESTAMPTZ NOT NULL,
    prev_hash   TEXT NOT NULL,
    hash        TEXT NOT NULL
);

CREATE TABLE config_version (
    id        BIGSERIAL PRIMARY KEY,
    version   BIGINT NOT NULL UNIQUE,
    config    JSONB NOT NULL,
    issued_at TIMESTAMPTZ NOT NULL,
    signature BYTEA NOT NULL
);

CREATE TABLE wallet (
    id       UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    owner_id UUID NOT NULL REFERENCES app_user(id),
    currency TEXT NOT NULL DEFAULT 'POINTS',
    balance  BIGINT NOT NULL DEFAULT 0 CHECK (balance >= 0),
    UNIQUE (owner_id, currency)
);

CREATE TABLE ledger_entry (
    id               UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    wallet_id        UUID NOT NULL REFERENCES wallet(id),
    counter_entry_id UUID REFERENCES ledger_entry(id),
    amount           BIGINT NOT NULL CHECK (amount > 0),
    direction        TEXT NOT NULL CHECK (direction IN ('DEBIT','CREDIT')),
    reason           TEXT NOT NULL,
    created_at       TIMESTAMPTZ NOT NULL DEFAULT now()
);
