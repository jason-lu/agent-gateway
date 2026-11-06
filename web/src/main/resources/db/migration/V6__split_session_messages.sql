-- Split agent.sessions: the messages jsonb blob becomes one row per message
-- in agent.session_messages, and sessions carries a title for listings.
-- Rationale: every turn used to rewrite the whole (ever-growing) history blob;
-- appends now touch only the new rows. Ordering is the explicit seq column —
-- timestamps collide within one turn's tool-call chain.
alter table agent.sessions add column title text;

create table agent.session_messages (
    session_id    uuid        not null references agent.sessions (id) on delete cascade,
    seq           int         not null,
    role          text        not null,
    content       text        not null default '',
    tool_call_id  text,
    tool_calls    jsonb,
    images        jsonb,
    created_at    timestamptz not null default now(),
    primary key (session_id, seq)
);

-- Move each existing message array into rows, preserving order and every
-- field verbatim (tool_calls/images keep their JSON shape). Idempotent per
-- session via on-conflict-do-nothing, so a partial re-run never duplicates.
do $$
declare
    s record;
    m record;
    i int;
begin
    for s in select id, messages from agent.sessions loop
        i := 0;
        for m in select value as msg from jsonb_array_elements(s.messages) loop
            insert into agent.session_messages
                (session_id, seq, role, content, tool_call_id, tool_calls, images)
            values (
                s.id, i,
                m.msg->>'role',
                coalesce(m.msg->>'content', ''),
                m.msg->>'toolCallId',
                m.msg->'toolCalls',
                m.msg->'images')
            on conflict do nothing;
            i := i + 1;
        end loop;
    end loop;
end $$;

-- Backfill titles from the first user message so listings are not empty
-- for pre-V6 sessions. Fill-once: never overwrites an existing title.
update agent.sessions s
set title = left(regexp_replace(m.content, '\s+', ' ', 'g'), 40)
from (
    select distinct on (session_id) session_id, content
    from agent.session_messages
    where role = 'user' and content <> ''
    order by session_id, seq
) m
where s.id = m.session_id and s.title is null;

alter table agent.sessions drop column messages;
