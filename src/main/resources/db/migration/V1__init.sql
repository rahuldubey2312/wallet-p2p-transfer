-- Wallets. One per user; the UNIQUE(user_id) constraint is what makes
-- get-or-create race-free: concurrent inserts collapse to a single row.
CREATE TABLE wallets
(
    id            UUID PRIMARY KEY,
    user_id       TEXT        NOT NULL UNIQUE,
    balance_paise BIGINT      NOT NULL CHECK (balance_paise >= 0),
    created_at    TIMESTAMPTZ NOT NULL DEFAULT now()
);

-- Transfers. Declined attempts are persisted too, so an idempotent retry
-- can replay the original outcome instead of re-attempting the debit.
CREATE TABLE transfers
(
    id             UUID PRIMARY KEY,
    from_wallet_id UUID        NOT NULL REFERENCES wallets (id),
    to_wallet_id   UUID        NOT NULL REFERENCES wallets (id),
    amount_paise   BIGINT      NOT NULL CHECK (amount_paise > 0),
    status         TEXT        NOT NULL,
    created_at     TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT transfers_distinct_wallets CHECK (from_wallet_id <> to_wallet_id)
);

CREATE INDEX idx_transfers_from_wallet ON transfers (from_wallet_id);
CREATE INDEX idx_transfers_to_wallet ON transfers (to_wallet_id);

-- Idempotency keys, scoped per caller. The composite primary key is the
-- single point of exactly-once enforcement: the claim row is inserted in the
-- same transaction as the debit and credit, so the key and the money move
-- together or not at all.
CREATE TABLE idempotency_keys
(
    user_id         TEXT        NOT NULL,
    idempotency_key TEXT        NOT NULL,
    request_hash    TEXT        NOT NULL,
    transfer_id     UUID        REFERENCES transfers (id),
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (user_id, idempotency_key)
);
