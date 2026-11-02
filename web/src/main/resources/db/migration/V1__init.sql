create schema if not exists agent;

create table agent.users (
    username      text primary key,
    password_hash text        not null,
    roles         text[]      not null default '{USER}',
    created_at    timestamptz not null default now(),
    updated_at    timestamptz not null default now()
);

create table agent.sessions (
    id         uuid primary key,
    owner      text        not null references agent.users (username),
    created_at timestamptz not null default now(),
    messages   jsonb       not null default '[]'::jsonb
);

create index agent_sessions_owner_created_idx
    on agent.sessions (owner, created_at desc);
