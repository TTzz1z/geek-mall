CREATE TABLE ai_conversation (
  id BIGINT PRIMARY KEY,
  conversation_no VARCHAR(64) NOT NULL,
  user_id BIGINT NOT NULL,
  scene VARCHAR(64) NOT NULL,
  status VARCHAR(32) NOT NULL,
  context_json JSON NULL,
  summary VARCHAR(2000) NULL,
  created_at TIMESTAMP(3) NOT NULL,
  updated_at TIMESTAMP(3) NOT NULL,
  UNIQUE KEY uk_ai_conv_no(conversation_no),
  KEY idx_ai_conv_user(user_id,created_at)
);

CREATE TABLE ai_message (
  id BIGINT PRIMARY KEY,
  conversation_id BIGINT NOT NULL,
  role VARCHAR(32) NOT NULL,
  content MEDIUMTEXT NOT NULL,
  token_count INT NOT NULL DEFAULT 0,
  model_name VARCHAR(64) NULL,
  agent_code VARCHAR(64) NULL,
  created_at TIMESTAMP(3) NOT NULL,
  KEY idx_ai_msg_conv(conversation_id,created_at)
);

CREATE TABLE ai_tool_call (
  id BIGINT PRIMARY KEY,
  conversation_id BIGINT NOT NULL,
  message_id BIGINT NULL,
  tool_name VARCHAR(64) NOT NULL,
  arguments_json JSON NOT NULL,
  result_summary VARCHAR(2000) NULL,
  status VARCHAR(32) NOT NULL,
  risk_level VARCHAR(8) NOT NULL,
  created_at TIMESTAMP(3) NOT NULL,
  KEY idx_ai_tool_conv(conversation_id,created_at)
);

CREATE TABLE ai_confirmation (
  id BIGINT PRIMARY KEY,
  tool_call_id BIGINT NOT NULL,
  confirmation_token VARCHAR(64) NOT NULL,
  user_id BIGINT NOT NULL,
  expires_at TIMESTAMP(3) NOT NULL,
  status VARCHAR(32) NOT NULL,
  created_at TIMESTAMP(3) NOT NULL,
  UNIQUE KEY uk_ai_confirm_token(confirmation_token)
);

CREATE TABLE ai_model_config (
  id BIGINT PRIMARY KEY,
  provider VARCHAR(64) NOT NULL,
  model_name VARCHAR(64) NOT NULL,
  model_type VARCHAR(32) NOT NULL,
  scene VARCHAR(64) NOT NULL,
  status VARCHAR(32) NOT NULL,
  config_json JSON NULL,
  UNIQUE KEY uk_ai_model(model_name,scene)
);

CREATE TABLE ai_model_call_log (
  id BIGINT PRIMARY KEY,
  request_id VARCHAR(64) NOT NULL,
  conversation_id BIGINT NULL,
  model_name VARCHAR(64) NOT NULL,
  latency_ms INT NOT NULL,
  prompt_tokens INT NOT NULL,
  completion_tokens INT NOT NULL,
  cost DECIMAL(18,6) NOT NULL DEFAULT 0,
  status VARCHAR(32) NOT NULL,
  detail VARCHAR(500) NULL,
  created_at TIMESTAMP(3) NOT NULL,
  KEY idx_ai_call_created(created_at)
);

CREATE TABLE knowledge_base (
  id BIGINT PRIMARY KEY,
  kb_code VARCHAR(64) NOT NULL,
  name VARCHAR(128) NOT NULL,
  scope_type VARCHAR(32) NOT NULL,
  scope_id BIGINT NULL,
  status VARCHAR(32) NOT NULL,
  UNIQUE KEY uk_kb_code(kb_code)
);

CREATE TABLE knowledge_document (
  id BIGINT PRIMARY KEY,
  kb_id BIGINT NOT NULL,
  source_type VARCHAR(64) NOT NULL,
  source_id VARCHAR(64) NULL,
  title VARCHAR(255) NOT NULL,
  file_url VARCHAR(500) NULL,
  parse_status VARCHAR(32) NOT NULL,
  version_no INT NOT NULL DEFAULT 1,
  created_at TIMESTAMP(3) NOT NULL
);

CREATE TABLE knowledge_chunk (
  id BIGINT PRIMARY KEY,
  document_id BIGINT NOT NULL,
  chunk_no INT NOT NULL,
  content TEXT NOT NULL,
  metadata_json JSON NULL,
  vector_id VARCHAR(64) NULL,
  KEY idx_chunk_doc(document_id,chunk_no)
);

CREATE TABLE knowledge_citation (
  id BIGINT PRIMARY KEY,
  message_id BIGINT NOT NULL,
  document_id BIGINT NOT NULL,
  chunk_id BIGINT NOT NULL,
  score DECIMAL(10,4) NOT NULL,
  KEY idx_cite_msg(message_id)
);

CREATE TABLE ai_eval_dataset (
  id BIGINT PRIMARY KEY,
  dataset_code VARCHAR(64) NOT NULL,
  version_no INT NOT NULL,
  status VARCHAR(32) NOT NULL,
  UNIQUE KEY uk_eval_ds(dataset_code,version_no)
);

CREATE TABLE ai_eval_case (
  id BIGINT PRIMARY KEY,
  dataset_id BIGINT NOT NULL,
  scene VARCHAR(64) NOT NULL,
  input_json JSON NOT NULL,
  expected_json JSON NOT NULL
);

CREATE TABLE ai_eval_result (
  id BIGINT PRIMARY KEY,
  case_id BIGINT NOT NULL,
  model_name VARCHAR(64) NOT NULL,
  score_json JSON NOT NULL,
  status VARCHAR(32) NOT NULL,
  created_at TIMESTAMP(3) NOT NULL
);
