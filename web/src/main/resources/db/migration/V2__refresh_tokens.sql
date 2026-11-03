create table agent.refresh_tokens (
    id           uuid primary key,
    owner        text        not null references agent.users (username),
    token_hash   text        not null unique,
    created_at   timestamptz not null default now(),
    last_used_at timestamptz not null default now()
);

create index agent_refresh_tokens_owner_idx
    on agent.refresh_tokens (owner);
