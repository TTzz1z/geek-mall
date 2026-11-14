ALTER TABLE ai_confirmation
    ADD COLUMN execution_enabled BOOLEAN NOT NULL DEFAULT FALSE,
    ADD COLUMN draft_no VARCHAR(64) NULL,
    ADD INDEX idx_ai_confirmation_status (status, expires_at);
