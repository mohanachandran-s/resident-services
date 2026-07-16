-- Dropping ip_address and host columns as per security review
ALTER TABLE resident.resident_session DROP COLUMN IF EXISTS ip_address;
ALTER TABLE resident.resident_session DROP COLUMN IF EXISTS host;