CREATE TABLE IF NOT EXISTS trade_event_consume_log (
  id BIGINT PRIMARY KEY,
  consumer_group VARCHAR(64) NOT NULL,
  event_id VARCHAR(64) NOT NULL,
  processed_at TIMESTAMP(3) NOT NULL,
  result VARCHAR(32) NOT NULL,
  UNIQUE KEY uk_trade_consume(consumer_group, event_id)
);
