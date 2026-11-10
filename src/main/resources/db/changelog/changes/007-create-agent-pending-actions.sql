alter table agent_conversation add constraint uk_agent_conversation_id_owner unique (id, tenant_id, user_id);

create table agent_pending_action(
    id uuid not null primary key,
    tenant_id bigint not null check ( tenant_id > 0 ),
    user_id bigint not null check ( user_id > 0 ),
    conversation_id bigint not null check ( conversation_id > 0 ),
    request_id uuid not null,
    source_turn_no bigint,
    action_type varchar(32) not null constraint ck_pending_action_type check ( action_type = 'CREATE_TICKET' ),
    status varchar(32) not null constraint ck_pending_action_status check (status in ('DRAFT','PENDING_CONFIRMATION','SUCCEEDED','CANCELLED')),
    params_json jsonb not null constraint ck_pending_action_params_object check ( jsonb_typeof(params_json) = 'object' ),
    preview_hash varchar(64) not null constraint ck_pending_action_preview_hash check (preview_hash ~ '^[0-9a-f]{64}$'),
    expires_at timestamptz not null,
    result_json jsonb,
    created_at timestamptz not null default current_timestamp,
    updated_at timestamptz not null default current_timestamp,

    -- 供审计表按“操作 + 租户 + 用户”关联
    constraint uk_pending_action_id_owner
        unique (id, tenant_id, user_id),

    -- 同一次编排中的相同操作参数，只保存一份草稿
    constraint uk_pending_action_request_params
        unique (
                tenant_id, user_id, request_id, action_type, preview_hash
            ),

    -- 操作必须关联同一租户、同一用户的会话
    constraint fk_pending_action_owned_conversation
        foreign key (conversation_id, tenant_id, user_id)
            references agent_conversation (id, tenant_id, user_id)
            on delete restrict,

    -- 草稿没有来源轮次；激活后必须绑定实际保存的正数轮次
    constraint ck_pending_action_source_turn check (
        (status = 'DRAFT' and source_turn_no is null)
            or
        (
            status <> 'DRAFT'
                and source_turn_no is not null
                and source_turn_no > 0
            )
        ),

    -- 成功必须有对象类型的结果；其他状态不能带成功结果
    constraint ck_pending_action_result_status check (
        (
            status = 'SUCCEEDED'
                and result_json is not null
                and jsonb_typeof(result_json) = 'object'
            )
            or
        (
            status <> 'SUCCEEDED'
                and result_json is null
            )
        )
);

create index idx_pending_action_owner_status_created
    on agent_pending_action (
             tenant_id, user_id, status, created_at desc, id
        );

create table agent_action_audit(
    id bigint generated always as identity primary key,
    action_id uuid not null,
    tenant_id bigint not null check (tenant_id > 0),
    user_id bigint not null check (user_id > 0),
    event_type varchar(32) not null constraint ck_action_audit_event_type check (event_type in ('DRAFT_CREATED','PREVIEW_READY','CONFIRMED','CANCELLED')),
    params_hash varchar(64) not null constraint ck_action_audit_params_hash check (params_hash ~ '^[0-9a-f]{64}$'),
    -- 与 business_ticket.ticket_no 的长度一致
    business_ref varchar(64),
    created_at timestamptz not null default current_timestamp,

    -- 审计必须关联相同归属的操作
    constraint fk_action_audit_owned_action
        foreign key (action_id, tenant_id, user_id)
            references agent_pending_action (id, tenant_id, user_id)
            on delete restrict,

    -- 一个操作的同一种成功事件只记录一次
    constraint uk_action_audit_action_event
        unique (action_id, event_type)
);
