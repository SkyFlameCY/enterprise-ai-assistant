-- 文档表
create table knowledge_document (
    id bigint generated always as identity primary key,
    tenant_id bigint not null check ( tenant_id > 0 ),
    document_key varchar(128) not null check ( btrim(document_key) <> '' ),
    version integer not null check ( version >= 1 ),
    title varchar(255) not null check ( btrim(title) <> '' ),
    source_ref varchar(1024),
    content_sha256 varchar(64) not null check ( content_sha256 ~ '^[0-9a-f]{64}$' ),
    status varchar(20) not null default 'DRAFT' check ( status in ('DRAFT', 'ACTIVE', 'SUPERSEDED') ),
    permission_metadata jsonb not null default '{}'::jsonb check ( jsonb_typeof(permission_metadata) = 'object' ),
    created_at timestamptz not null default current_timestamp,
    updated_at timestamptz not null default current_timestamp,

    constraint uk_document_version unique (tenant_id, document_key, version)
);

-- 片段表
create table knowledge_chunk (
    id bigint generated always as identity primary key,
    document_id bigint not null,
    chunk_index integer not null check ( chunk_index >= 0 ),
    content text not null check ( btrim(content) <> '' ),
    content_sha256 varchar(64) not null check ( content_sha256 ~ '^[0-9a-f]{64}$' ),
    embedding vector(1024) not null,
    embedding_model varchar(128) not null check ( btrim(embedding_model) <> '' ),
    embedding_revision varchar(128) not null check ( btrim(embedding_revision) <> '' ),
    created_at timestamptz not null default current_timestamp,

    constraint fk_chunk_document foreign key (document_id) references knowledge_document(id) on delete restrict,
    constraint uk_chunk_position unique (document_id, chunk_index)
);

-- 查询某租户的有效文档
create index idx_document_tenant_status on knowledge_document (tenant_id, status);

-- 同一组户的一份文档，最多只有一个 ACTIVE 版本
create unique index uk_document_active_version on knowledge_document (tenant_id, document_key) where status = 'ACTIVE';