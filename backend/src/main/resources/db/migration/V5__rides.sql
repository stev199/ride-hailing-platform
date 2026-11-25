-- V5 (P4-2): rides + stops. Forward-fix (D33) - no ride data exists anywhere
-- (Phase 4 is the first writer). Multi-stop lands later; the stops table is
-- created now with the PICKUP/DROPOFF rows every ride seeds, so the schema
-- never needs a destructive pass when multi-stop arrives.

CREATE TABLE rides (
    id UUID PRIMARY KEY,
    rider_id UUID NOT NULL REFERENCES app_user(id),
    status TEXT NOT NULL,
    city TEXT NOT NULL,
    ride_type TEXT NOT NULL,
    payment_method TEXT NOT NULL,
    note TEXT,
    pickup_lat DOUBLE PRECISION NOT NULL,
    pickup_lon DOUBLE PRECISION NOT NULL,
    dropoff_lat DOUBLE PRECISION NOT NULL,
    dropoff_lon DOUBLE PRECISION NOT NULL,
    -- The server-SIGNED final quote document (spec §pricing), verbatim.
    quote_json TEXT NOT NULL,
    quote_signature TEXT NOT NULL,
    quote_amount_minor BIGINT NOT NULL,
    quote_currency TEXT NOT NULL,
    quote_valid_until TIMESTAMPTZ NOT NULL,
    quote_issued_at TIMESTAMPTZ NOT NULL,
    quote_mode TEXT NOT NULL,
    -- Cancel fee actually recorded at cancellation (0 = none).
    cancel_fee_minor BIGINT NOT NULL DEFAULT 0,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX idx_rides_rider ON rides (rider_id, created_at DESC);

CREATE TABLE ride_stops (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    ride_id UUID NOT NULL REFERENCES rides(id),
    kind TEXT NOT NULL CHECK (kind IN ('PICKUP','DROPOFF')),
    sequence INT NOT NULL,
    name TEXT,
    lat DOUBLE PRECISION NOT NULL,
    lon DOUBLE PRECISION NOT NULL
);

CREATE INDEX idx_ride_stops_ride ON ride_stops (ride_id);
