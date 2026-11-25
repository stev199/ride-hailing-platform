-- V4 (P1-5): proper double-entry ledger (schema now, services Phase 7).
--
-- The V1 ledger_entry (pairwise counter_entry_id) could not express multi-leg
-- movements (rider + driver + platform fee) and enforced nothing at the DB level.
-- The double-entry model below is the standard: one IMMUTABLE transaction, N legs,
-- and a DATABASE-enforced invariant sum(DEBIT) == sum(CREDIT) per transaction.
-- The old table is empty everywhere (no writer ever shipped), so the DROP is safe
-- and this is a forward-fix (D33), not a data migration.

DROP TABLE IF EXISTS ledger_entry;

CREATE TABLE ledger_transaction (
    id         UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    -- What business event produced this movement (e.g. 'ride.completed', 'admin.adjustment').
    ref_type   TEXT,
    ref_id     TEXT,
    reason     TEXT NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE ledger_entry (
    id             UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    transaction_id UUID NOT NULL REFERENCES ledger_transaction(id),
    wallet_id      UUID NOT NULL REFERENCES wallet(id),
    amount         BIGINT NOT NULL CHECK (amount > 0),
    direction      TEXT NOT NULL CHECK (direction IN ('DEBIT','CREDIT')),
    created_at     TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX idx_ledger_entry_transaction ON ledger_entry (transaction_id);
CREATE INDEX idx_ledger_entry_wallet ON ledger_entry (wallet_id);

-- Double-entry invariant AT THE DATABASE: any transaction touched by a statement must
-- have equal DEBIT and CREDIT sums, checked at COMMIT time (DEFERRABLE INITIALLY
-- DEFERRED) so a service inserts the transaction + all legs in one SQL transaction in
-- any order. An unbalanced post therefore fails the whole commit (no partial writes).
CREATE OR REPLACE FUNCTION check_ledger_transaction_balanced() RETURNS trigger AS $$
DECLARE
    tx_id      UUID;
    debit_sum  BIGINT;
    credit_sum BIGINT;
BEGIN
    tx_id := COALESCE(NEW.transaction_id, OLD.transaction_id);
    SELECT COALESCE(SUM(amount) FILTER (WHERE direction = 'DEBIT'), 0),
           COALESCE(SUM(amount) FILTER (WHERE direction = 'CREDIT'), 0)
      INTO debit_sum, credit_sum
      FROM ledger_entry
     WHERE transaction_id = tx_id;
    IF debit_sum <> credit_sum THEN
        RAISE EXCEPTION 'ledger transaction % unbalanced: debit=% credit=%', tx_id, debit_sum, credit_sum
            USING ERRCODE = '23514';
    END IF;
    RETURN NULL;
END;
$$ LANGUAGE plpgsql;

CREATE CONSTRAINT TRIGGER ledger_transaction_balanced
    AFTER INSERT OR UPDATE OR DELETE ON ledger_entry
    DEFERRABLE INITIALLY DEFERRED
    FOR EACH ROW EXECUTE FUNCTION check_ledger_transaction_balanced();

-- wallet: points wallets must never go negative; CASH wallets may (credit line),
-- selected per wallet. The old single CHECK blocked cash wallets entirely.
ALTER TABLE wallet DROP CONSTRAINT wallet_balance_check;
ALTER TABLE wallet ADD COLUMN allow_negative BOOLEAN NOT NULL DEFAULT FALSE;
ALTER TABLE wallet ADD CONSTRAINT wallet_balance_check CHECK (allow_negative OR balance >= 0);
