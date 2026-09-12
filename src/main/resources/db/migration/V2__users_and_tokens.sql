-- Users own wallets and hold the details a wallet alone cannot express.
CREATE TABLE users
(
    id           UUID PRIMARY KEY,
    display_name TEXT        NOT NULL,
    -- UNIQUE permits many NULLs in Postgres, which is exactly the intent:
    -- an email is optional, but no two users may share one.
    email        TEXT UNIQUE,
    phone        TEXT,
    created_at   TIMESTAMPTZ NOT NULL DEFAULT now()
);

-- Issued credentials. Only the SHA-256 of a token is stored: the plaintext is
-- returned once when the user is created and is never persisted, so a dump of
-- this table cannot be replayed against the API.
-- Deliberately no last_used_at: stamping it would mean an UPDATE to the same
-- row on every authenticated request, and a burst sharing one token would then
-- serialise on that row's lock instead of proceeding in parallel.
CREATE TABLE user_tokens
(
    token_hash TEXT PRIMARY KEY,
    user_id    UUID        NOT NULL REFERENCES users (id) ON DELETE CASCADE,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX idx_user_tokens_user ON user_tokens (user_id);

-- Point wallets at a real user instead of the opaque identity string V1 used.
ALTER TABLE wallets
    ADD COLUMN owner_id UUID;

-- Wallets created under V1 keep their balances and transfer history. Their
-- identity string becomes the display name of a generated user; those users
-- have no token, so the wallets are readable but no longer spendable, which
-- suits what was only ever throwaway data.
INSERT INTO users (id, display_name)
SELECT gen_random_uuid(), user_id
FROM wallets;

UPDATE wallets w
SET owner_id = u.id
FROM users u
WHERE u.display_name = w.user_id;

ALTER TABLE wallets
    ALTER COLUMN owner_id SET NOT NULL;

-- One wallet per user, and it is this constraint that keeps get-or-create
-- race-free: concurrent inserts for the same owner collapse to a single row.
ALTER TABLE wallets
    ADD CONSTRAINT wallets_owner_unique UNIQUE (owner_id);

ALTER TABLE wallets
    ADD CONSTRAINT wallets_owner_fk FOREIGN KEY (owner_id) REFERENCES users (id);

ALTER TABLE wallets
    DROP COLUMN user_id;

-- Idempotency claims were scoped by the old identity string. Those strings no
-- longer identify anyone, and a claim only needs to outlive a client's retry
-- window, so the stale rows are discarded rather than mapped across.
DELETE FROM idempotency_keys;

ALTER TABLE idempotency_keys
    ALTER COLUMN user_id TYPE UUID USING user_id::uuid;

ALTER TABLE idempotency_keys
    ADD CONSTRAINT idempotency_user_fk FOREIGN KEY (user_id) REFERENCES users (id);
