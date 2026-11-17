-- Semantic memory (see docs/compose/spec/semantic-memory.md).
-- user_profile holds the whitelisted personal archive (nickname / gender /
-- city, city capped at city level); memory_entries holds free-text facts and
-- preferences. Hard identifiers are never written here — the write paths run
-- HardIdentifierDetector before touching these tables.
-- Deletes are hard deletes: cleared memory must not be recoverable.

create table agent.user_profile (
    owner       text primary key,
    nickname    text,
    gender      text check (gender in ('male', 'female', 'unspecified')),
    city        text,
    updated_at  timestamptz not null default now()
);

create table agent.memory_entries (
    id                uuid primary key default gen_random_uuid(),
    owner             text not null,
    kind              text not null check (kind in ('fact', 'preference')),
    content           text not null,
    source            text not null check (source in ('explicit', 'auto', 'confirm')),
    source_session_id text,
    created_at        timestamptz not null default now(),
    updated_at        timestamptz not null default now()
);

create index memory_entries_owner_updated_idx
    on agent.memory_entries (owner, updated_at desc);
