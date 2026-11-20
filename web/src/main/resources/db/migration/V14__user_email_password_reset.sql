-- Email bound to an account. Invite-based registration copies it from the
-- invite code; config-seeded accounts start without one. Not unique: an address
-- may legitimately own more than one account.
alter table agent.users add column email text;

-- Password-reset tokens for the forgot-password flow. The raw token travels
-- only by email; the store keeps its SHA-256 hash. Single-use and short-lived;
-- a new request drops the outstanding ones for that account.
create table agent.password_reset_tokens (
    token_hash text        primary key,
    username   text        not null references agent.users (username) on delete cascade,
    created_at timestamptz not null default now(),
    expires_at timestamptz not null,
    used_at    timestamptz
);

create index password_reset_tokens_username_idx
    on agent.password_reset_tokens (username);
