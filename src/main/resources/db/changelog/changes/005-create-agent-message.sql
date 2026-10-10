create table agent_message(
    id bigint generated always as identity primary key,
    conversation_id bigint not null,
    turn_no bigint not null check (turn_no > 0),
    role varchar(20) not null check (role in ('USER', 'ASSISTANT')),
    content text not null check (btrim(content) <> ''),
    created_at timestamptz not null default current_timestamp,

    constraint uk_agent_message_conversation_no unique (conversation_id, turn_no, role),

    constraint fk_agent_message_conversation foreign key (conversation_id)
        references agent_conversation (id) on delete restrict
);