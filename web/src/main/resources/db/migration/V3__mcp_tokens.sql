create table agent.mcp_tokens (
    server_name   text primary key,
    access_token  text        not null,
    refresh_token text,
    expires_at    bigint      not null default 9223372036854775807,
    updated_at    timestamptz not null default now()
);
