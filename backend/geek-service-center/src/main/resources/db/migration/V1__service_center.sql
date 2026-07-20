CREATE TABLE service_skill_group (
  id BIGINT PRIMARY KEY,
  group_code VARCHAR(64) NOT NULL,
  group_name VARCHAR(128) NOT NULL,
  status VARCHAR(32) NOT NULL,
  UNIQUE KEY uk_skill_code(group_code)
);

CREATE TABLE service_agent (
  id BIGINT PRIMARY KEY,
  user_id BIGINT NOT NULL,
  display_name VARCHAR(64) NOT NULL,
  skill_group_id BIGINT NOT NULL,
  status VARCHAR(32) NOT NULL,
  max_sessions INT NOT NULL DEFAULT 5,
  UNIQUE KEY uk_agent_user(user_id)
);

CREATE TABLE service_session (
  id BIGINT PRIMARY KEY,
  session_no VARCHAR(64) NOT NULL,
  user_id BIGINT NOT NULL,
  agent_id BIGINT NULL,
  skill_group_id BIGINT NOT NULL,
  status VARCHAR(32) NOT NULL,
  queue_position INT NOT NULL DEFAULT 0,
  context_json JSON NULL,
  created_at TIMESTAMP(3) NOT NULL,
  assigned_at TIMESTAMP(3) NULL,
  closed_at TIMESTAMP(3) NULL,
  updated_at TIMESTAMP(3) NOT NULL,
  UNIQUE KEY uk_session_no(session_no),
  KEY idx_session_user(user_id,status),
  KEY idx_session_agent(agent_id,status)
);

CREATE TABLE service_message (
  id BIGINT PRIMARY KEY,
  session_id BIGINT NOT NULL,
  sender_type VARCHAR(32) NOT NULL,
  sender_id BIGINT NOT NULL,
  content_type VARCHAR(32) NOT NULL,
  content VARCHAR(2000) NOT NULL,
  sent_at TIMESTAMP(3) NOT NULL,
  KEY idx_msg_session(session_id,sent_at)
);

CREATE TABLE service_ticket (
  id BIGINT PRIMARY KEY,
  ticket_no VARCHAR(64) NOT NULL,
  user_id BIGINT NOT NULL,
  merchant_id BIGINT NULL,
  category VARCHAR(64) NOT NULL,
  priority VARCHAR(32) NOT NULL,
  status VARCHAR(32) NOT NULL,
  assignee_id BIGINT NULL,
  title VARCHAR(200) NOT NULL,
  content VARCHAR(2000) NOT NULL,
  sla_due_at TIMESTAMP(3) NOT NULL,
  created_at TIMESTAMP(3) NOT NULL,
  updated_at TIMESTAMP(3) NOT NULL,
  resolved_at TIMESTAMP(3) NULL,
  UNIQUE KEY uk_ticket_no(ticket_no),
  KEY idx_ticket_user(user_id,status),
  KEY idx_ticket_assignee(assignee_id,status)
);

CREATE TABLE service_ticket_log (
  id BIGINT PRIMARY KEY,
  ticket_id BIGINT NOT NULL,
  action VARCHAR(64) NOT NULL,
  operator_id BIGINT NOT NULL,
  content VARCHAR(1000) NULL,
  created_at TIMESTAMP(3) NOT NULL
);

CREATE TABLE notification_template (
  id BIGINT PRIMARY KEY,
  scene VARCHAR(64) NOT NULL,
  channel VARCHAR(32) NOT NULL,
  template_code VARCHAR(64) NOT NULL,
  content VARCHAR(1000) NOT NULL,
  status VARCHAR(32) NOT NULL,
  UNIQUE KEY uk_tpl(scene,channel)
);

CREATE TABLE notification_task (
  id BIGINT PRIMARY KEY,
  task_no VARCHAR(64) NOT NULL,
  scene VARCHAR(64) NOT NULL,
  channel VARCHAR(32) NOT NULL,
  receiver_id BIGINT NOT NULL,
  payload_json JSON NOT NULL,
  status VARCHAR(32) NOT NULL,
  retry_count INT NOT NULL DEFAULT 0,
  last_error VARCHAR(500) NULL,
  created_at TIMESTAMP(3) NOT NULL,
  updated_at TIMESTAMP(3) NOT NULL,
  UNIQUE KEY uk_task_no(task_no),
  KEY idx_task_status(status,created_at)
);

CREATE TABLE notification_record (
  id BIGINT PRIMARY KEY,
  task_id BIGINT NOT NULL,
  provider_message_id VARCHAR(96) NULL,
  result VARCHAR(32) NOT NULL,
  detail VARCHAR(500) NULL,
  sent_at TIMESTAMP(3) NOT NULL
);
