-- Add processing_started_at as epoch millis so watchdog can check elapsed processing time
-- instead of blindly resetting all PROCESSING documents
ALTER TABLE documents ADD COLUMN processing_started_at BIGINT NULL;
