-- P7-6 promo codes: rides record the applied code + the platform-side discount.
-- quote_amount_minor keeps the SIGNED fare (the quote signature still covers it);
-- the charge is quote_amount_minor - discount_minor (hold/capture/cash owed).
ALTER TABLE rides ADD COLUMN IF NOT EXISTS promo_code TEXT;
ALTER TABLE rides ADD COLUMN IF NOT EXISTS discount_minor BIGINT NOT NULL DEFAULT 0;
CREATE INDEX IF NOT EXISTS rides_promo_idx ON rides (promo_code, rider_id);
