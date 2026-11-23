-- P1-3 (D33 forward-fix): byte-exact event-chain reload. The hash chain covers
-- occurredAt AS A STRING (Instant.toString()); a timestamptz round-trip can lose
-- nanosecond precision, so the exact string is stored alongside the queryable
-- timestamp and the chain reloads from it.
ALTER TABLE event_log ADD COLUMN occurred_at_text TEXT NOT NULL DEFAULT '';
