-- User-scoped MCP server definitions. Unlike the deployment-level mcp.json layer,
-- these rows carry personal credentials (PATs, per-user tokens) and are keyed by
-- owner so one user's servers never leak into another user's tool set.
create table agent.user_mcp_servers (
    owner      text        not null references agent.users (username),
    name       text        not null,
    transport  text        not null,
    command    text,
    args       jsonb       not null default '[]'::jsonb,
    env        jsonb       not null default '{}'::jsonb,
    url        text,
    headers    jsonb       not null default '{}'::jsonb,
    enabled    boolean     not null default true,
    created_at timestamptz not null default now(),
    updated_at timestamptz not null default now(),
    primary key (owner, name)
);
