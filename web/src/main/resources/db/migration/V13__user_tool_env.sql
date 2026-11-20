-- Per-user tool credentials (GH_TOKEN and friends) injected into that user's exec
-- runs. Keyed by owner so one user's secrets never enter another user's environment.
create table agent.user_tool_env (
    owner      text        not null references agent.users (username),
    key        text        not null,
    value      text        not null,
    created_at timestamptz not null default now(),
    updated_at timestamptz not null default now(),
    primary key (owner, key)
);
