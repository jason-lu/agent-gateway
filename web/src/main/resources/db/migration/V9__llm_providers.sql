-- Multi-provider LLM upstreams, managed by admins at runtime. Each row is one
-- connection (base URL + API key) offering a list of model IDs; one row is the
-- primary (deployment default). The partial unique index keeps the invariant
-- "exactly one primary" at the database level; the service additionally refuses
-- to delete the primary, so at least one provider always remains.
create table agent.llm_providers (
    id            text        primary key,
    name          text        not null unique,
    base_url      text        not null,
    api_key       text        not null,
    models        jsonb       not null default '[]'::jsonb,
    default_model text        not null,
    is_primary    boolean     not null default false,
    created_at    timestamptz not null default now(),
    updated_at    timestamptz not null default now()
);

create unique index llm_providers_one_primary on agent.llm_providers (is_primary) where is_primary;

-- Which (provider, model) a user picked in the chat window. Cascade keeps the
-- choice honest when an admin removes a provider: the row dies with it and the
-- next turn falls back to the primary default.
create table agent.user_llm_preferences (
    owner       text        primary key references agent.users (username),
    provider_id text        not null references agent.llm_providers (id) on delete cascade,
    model       text        not null,
    updated_at  timestamptz not null default now()
);

-- Carry the old single-row admin settings over as the primary provider. Only a
-- complete row can become one (provider fields are not nullable and the old
-- per-field env fallback is gone); partial rows are dropped and the environment
-- seed rebuilds from LLM_* if it can.
insert into agent.llm_providers (id, name, base_url, api_key, models, default_model, is_primary)
select gen_random_uuid()::text,
       '默认',
       btrim(base_url),
       btrim(api_key),
       jsonb_build_array(btrim(model)),
       btrim(model),
       true
from agent.admin_llm_settings
where id = 1
  and base_url is not null and btrim(base_url) <> ''
  and api_key is not null and btrim(api_key) <> ''
  and model is not null and btrim(model) <> '';

drop table agent.admin_llm_settings;
