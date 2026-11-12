-- Outbound email settings (singleton row), edited by admins at runtime. The
-- defaults match Aliyun enterprise mail SMTP (smtp.qiye.aliyun.com, implicit
-- SSL on 465); username/password are account credentials and never leave the
-- server unmasked.
create table agent.email_settings (
    id           integer     primary key default 1 check (id = 1),
    host         text        not null default 'smtp.qiye.aliyun.com',
    port         integer     not null default 465,
    username     text        not null default '',
    password     text        not null default '',
    from_address text        not null default '',
    use_ssl      boolean     not null default true,
    updated_at   timestamptz not null default now()
);

insert into agent.email_settings (id) values (1);

-- Registration invite codes. The code itself is the credential presented at
-- registration; email is just where it gets delivered. used_by records the
-- account that consumed it (null while unused).
create table agent.invite_codes (
    code       text        primary key,
    email      text        not null,
    note       text        not null default '',
    created_by text        not null,
    created_at timestamptz not null default now(),
    expires_at timestamptz not null,
    sent_at    timestamptz,
    used_by    text,
    used_at    timestamptz
);
