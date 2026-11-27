-- V7: driver verification submissions (P6-1 verification center).
-- Workflow table: one row per submission; the LATEST row per user is the active
-- one (the service resolves latestForUser). Document IMAGES are NOT stored here -
-- S-03 (encrypted document storage) is its own slice; declared_docs names only.
CREATE TABLE verification_submission (
    id                     UUID PRIMARY KEY,
    user_id                UUID NOT NULL REFERENCES app_user(id),
    license_number         VARCHAR(64) NOT NULL,
    vehicle_plate          VARCHAR(32) NOT NULL,
    declared_docs          JSONB NOT NULL,
    status                 VARCHAR(16) NOT NULL CHECK (status IN ('PENDING', 'APPROVED', 'REJECTED')),
    submitted_at_epoch_sec BIGINT NOT NULL,
    reviewed_at_epoch_sec  BIGINT,
    reviewer_id            UUID REFERENCES app_user(id),
    reject_reason          VARCHAR(512)
);

CREATE INDEX idx_verification_user_latest ON verification_submission (user_id, submitted_at_epoch_sec DESC);
CREATE INDEX idx_verification_status_submitted ON verification_submission (status, submitted_at_epoch_sec DESC);
