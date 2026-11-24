-- Per-message token accounting: the usage block an OpenAI-style upstream
-- reports for the model call that produced the assistant turn. Null on rows
-- written before this migration (and on user/tool turns). Held in the same
-- jsonb style as tool_calls: {"promptTokens":..,"completionTokens":..,"totalTokens":..,
-- "reasoningTokens":..,"cachedTokens":..} — the foundation for token-driven
-- memory compression (context size = last assistant row's promptTokens).
alter table agent.session_messages add column token_usage jsonb;
