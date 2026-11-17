-- Content-moderation audit log (see docs/compose/spec/content-moderation.md).
-- Privacy: excerpt stores a masked fragment of the hit, text_sha256 identifies
-- the normalized text for appeals — the raw violating text is never stored.

create table agent.moderation_events (
    id              bigserial primary key,
    created_at      timestamptz not null default now(),
    owner           text        not null,
    session_id      uuid,
    direction       text        not null,
    category        text        not null,
    severity        text        not null,
    action          text        not null,
    detector        text        not null,
    excerpt         text,
    text_sha256     text,
    needs_review    boolean     not null default false,
    reviewed        boolean     not null default false,
    review_note     text,
    review_override text
);

create index agent_moderation_events_owner_created_idx
    on agent.moderation_events (owner, created_at desc);

create index agent_moderation_events_pending_idx
    on agent.moderation_events (created_at desc)
    where not reviewed;
