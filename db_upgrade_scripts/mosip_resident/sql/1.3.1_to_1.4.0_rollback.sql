-- Restoring ip_address and host columns
ALTER TABLE resident.resident_session ADD COLUMN IF NOT EXISTS ip_address character varying(128);
ALTER TABLE resident.resident_session ADD COLUMN IF NOT EXISTS host character varying(128);
COMMENT ON COLUMN resident.resident_session.ip_address IS 'The ip_address of device from which the user logged in';
COMMENT ON COLUMN resident.resident_session.host IS 'The host of the site';