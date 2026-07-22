-- Token numbers are provider-reported only when an OpenAI-compatible response includes usage;
-- otherwise they are local estimates. Cost always remains a local estimate, not a provider bill.
ALTER TABLE ai_model_call_log
  ADD COLUMN usage_estimated BOOLEAN NOT NULL DEFAULT TRUE AFTER cost;

ALTER TABLE ai_message
  ADD COLUMN token_estimated BOOLEAN NOT NULL DEFAULT TRUE AFTER token_count;
