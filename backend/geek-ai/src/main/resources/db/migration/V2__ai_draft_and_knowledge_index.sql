CREATE TABLE IF NOT EXISTS ai_business_draft (
  id BIGINT PRIMARY KEY,
  draft_no VARCHAR(64) NOT NULL,
  user_id BIGINT NOT NULL,
  draft_type VARCHAR(64) NOT NULL,
  status VARCHAR(32) NOT NULL,
  payload_json JSON NOT NULL,
  created_at TIMESTAMP(3) NOT NULL,
  updated_at TIMESTAMP(3) NOT NULL,
  UNIQUE KEY uk_ai_draft_no(draft_no),
  KEY idx_ai_draft_user(user_id,created_at)
);

CREATE TABLE IF NOT EXISTS knowledge_vector (
  id BIGINT PRIMARY KEY,
  chunk_id BIGINT NOT NULL,
  vector_id VARCHAR(64) NOT NULL,
  dim INT NOT NULL,
  embedding_json JSON NOT NULL,
  created_at TIMESTAMP(3) NOT NULL,
  UNIQUE KEY uk_kv_chunk(chunk_id),
  KEY idx_kv_vector(vector_id)
);
