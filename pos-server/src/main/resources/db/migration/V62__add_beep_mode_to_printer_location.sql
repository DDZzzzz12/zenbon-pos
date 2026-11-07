-- Add beep mode to printer location for audible order alerts
ALTER TABLE printer_location ADD COLUMN IF NOT EXISTS beep_mode VARCHAR(20) NOT NULL DEFAULT 'DISABLED';
