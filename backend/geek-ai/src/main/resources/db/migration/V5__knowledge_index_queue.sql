ALTER TABLE knowledge_chunk
    ADD COLUMN index_profile VARCHAR(190) NULL,
    ADD COLUMN index_retry_at TIMESTAMP(3) NULL,
    ADD INDEX idx_knowledge_index_due (index_retry_at, id);

ALTER TABLE knowledge_document
    ADD INDEX idx_knowledge_document_source (kb_id, source_type, source_id, version_no);

UPDATE knowledge_document d
JOIN knowledge_chunk c ON c.document_id=d.id
SET d.parse_status='REPARSE_REQUIRED'
WHERE d.parse_status='READY'
  AND (c.content LIKE '【OCR】%' OR c.content LIKE '【解析】%');

UPDATE ai_model_config
SET status='DISABLED'
WHERE model_type='LOCAL_VECTOR_PROTOTYPE';
