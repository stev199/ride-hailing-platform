-- P1-3e follow-up (D33 forward-fix): the hash chain covers the CANONICAL payload
-- string byte-for-byte. event_log.payload is JSONB, which NORMALIZES its text
-- output (e.g. {"n":1} becomes {"n": 1}) - reloading from payload::text breaks
-- every reloaded hash. The exact canonical string is stored alongside (V2 pattern:
-- occurred_at_text); JSONB stays for queryability.
ALTER TABLE event_log ADD COLUMN payload_text TEXT NOT NULL DEFAULT '';
