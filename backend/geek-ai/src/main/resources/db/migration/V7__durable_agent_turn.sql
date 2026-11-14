CREATE TABLE ai_turn (
  id VARCHAR(36) PRIMARY KEY,
  conversation_id BIGINT NOT NULL,
  user_id BIGINT NOT NULL,
  request_id VARCHAR(100) COLLATE utf8mb4_bin NOT NULL,
  request_digest CHAR(64) NOT NULL,
  status VARCHAR(20) NOT NULL,
  user_message_id BIGINT NOT NULL,
  assistant_message_id BIGINT NOT NULL,
  context_json JSON NOT NULL,
  plan_json JSON NULL,
  result_json JSON NULL,
  created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
  updated_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
  UNIQUE KEY uk_turn_request(user_id,conversation_id,request_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
CREATE TABLE ai_turn_step (
  turn_id VARCHAR(36) NOT NULL,
  step_no INT NOT NULL,
  tool_call_id BIGINT NOT NULL,
  arguments_json JSON NULL,
  result_json JSON NULL,
  PRIMARY KEY(turn_id,step_no)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
