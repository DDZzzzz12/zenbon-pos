ALTER TABLE documents ADD COLUMN processing_status VARCHAR(20) DEFAULT 'NONE';
ALTER TABLE documents ADD COLUMN processing_retry_count INT DEFAULT 0;
ALTER TABLE documents ADD COLUMN processing_error TEXT;
