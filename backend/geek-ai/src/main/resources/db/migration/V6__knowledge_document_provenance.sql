-- The Air 14 demo manual is curated text. Its old URL was never ingested.
UPDATE knowledge_document
SET file_url = NULL
WHERE id = 96201 AND kb_id = 96101 AND source_type = 'PRODUCT_MANUAL'
  AND source_id = 'SPU-1001' AND version_no = 1 AND file_url = '/docs/air14-manual.pdf';

-- Legacy ingestion recorded vl even when it saved caller-supplied text without reading the file.
UPDATE knowledge_document d
SET d.parse_status = 'REPARSE_REQUIRED'
WHERE d.parse_status = 'READY'
  AND REGEXP_LIKE(d.file_url, '[^[:space:]]')
  AND EXISTS (
    SELECT 1 FROM knowledge_chunk c
    WHERE c.document_id = d.id AND JSON_CONTAINS_PATH(c.metadata_json, 'one', '$.vl') = 1
  );

UPDATE knowledge_document
SET source_id = CONCAT('legacy-document-', id)
WHERE source_id IS NULL OR NOT REGEXP_LIKE(source_id, '[^[:space:]]');
