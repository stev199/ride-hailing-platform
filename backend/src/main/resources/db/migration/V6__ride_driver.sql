-- V6 (P5): driver assignment on rides. The trip state machine (P4-2) already
-- defines ACCEPTED/ARRIVED/IN_PROGRESS with driver-owned transitions; the claim
-- (REQUESTED -> ACCEPTED + driver) is atomic single-winner in the stores.

ALTER TABLE rides ADD COLUMN driver_id UUID REFERENCES app_user(id);
CREATE INDEX idx_rides_driver ON rides (driver_id, created_at DESC);
