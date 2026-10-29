ALTER TABLE payment_attempts
    ADD COLUMN IF NOT EXISTS provider_capture_reference VARCHAR(150),
    ADD COLUMN IF NOT EXISTS provider_status VARCHAR(30);
