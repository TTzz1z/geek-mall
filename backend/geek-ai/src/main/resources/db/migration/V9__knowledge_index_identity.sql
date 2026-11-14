-- SQL success markers belong to a physical ES index, not just a reusable index name.
ALTER TABLE knowledge_index_state ADD COLUMN index_uuid VARCHAR(64) NULL;
