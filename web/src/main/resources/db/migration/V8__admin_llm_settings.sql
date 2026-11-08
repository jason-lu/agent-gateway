-- Deployment-level LLM upstream overrides, editable by admins at runtime.
-- A null/blank column means "no override": the effective value falls back to the
-- environment configuration (LLM_BASE_URL / LLM_API_KEY / LLM_MODEL). Admin values
-- win per field. Single row, pinned by the id check.
create table agent.admin_llm_settings (
    id         smallint    primary key default 1 check (id = 1),
    base_url   text,
    api_key    text,
    model      text,
    created_at timestamptz not null default now(),
    updated_at timestamptz not null default now()
);
