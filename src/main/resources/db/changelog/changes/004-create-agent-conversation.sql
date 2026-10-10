create table agent_conversation (
    id bigint generated always as identity primary key,
    tenant_id bigint not null check (tenant_id > 0),
    user_id bigint not null check (user_id > 0),
    title varchar(255) not null default '新会话' check (btrim(title) <> ''),
    status varchar(20) not null default 'ACTIVE' check (status in ('ACTIVE', 'ARCHIVED')),
    created_at timestamptz not null default current_timestamp,
    updated_at timestamptz not null default current_timestamp
);

create index idx_agent_conversation_tenant_user_update_id
    on agent_conversation (tenant_id, user_id, updated_at DESC, id DESC);