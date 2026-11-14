ALTER TABLE knowledge_document
    ADD COLUMN activation_status VARCHAR(32) NOT NULL DEFAULT 'CANDIDATE',
    ADD COLUMN index_cleanup_pending BOOLEAN NOT NULL DEFAULT FALSE,
    ADD INDEX idx_knowledge_activation (kb_id, activation_status, id);

-- Preserve the current visible version during the first generation rebuild.
UPDATE knowledge_document d
SET activation_status = CASE WHEN parse_status='READY' THEN 'ACTIVE' ELSE 'INACTIVE' END;

-- Legacy rows may contain more than one READY version for a source.
UPDATE knowledge_document d
JOIN knowledge_document newer ON newer.kb_id=d.kb_id AND newer.source_type=d.source_type
    AND newer.source_id=d.source_id AND newer.parse_status='READY'
    AND (newer.version_no>d.version_no OR (newer.version_no=d.version_no AND newer.id>d.id))
SET d.activation_status='SUPERSEDED', d.index_cleanup_pending=TRUE
WHERE d.activation_status='ACTIVE';

CREATE TABLE knowledge_index_state (
    index_name VARCHAR(128) PRIMARY KEY,
    generation BIGINT NOT NULL,
    model_profile VARCHAR(190) NOT NULL,
    status VARCHAR(32) NOT NULL,
    last_error VARCHAR(255) NULL,
    updated_at TIMESTAMP(3) NOT NULL
);
