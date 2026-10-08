create table business_project (
    id bigint generated always as identity primary key,
    tenant_id bigint not null check (tenant_id > 0),
    project_code varchar(64) not null check (btrim(project_code) <> ''),
    name varchar(255) not null check (btrim(name) <> ''),
    status varchar(20) not null default 'ACTIVE' check (status in ('ACTIVE', 'PAUSED')),
    created_at timestamptz not null default current_timestamp,
    updated_at timestamptz not null default current_timestamp,

    constraint uk_business_project_tenant_code unique (tenant_id, project_code)
);

create table business_ticket (
    id bigint generated always as identity primary key,
    tenant_id bigint not null check (tenant_id > 0),
    ticket_no varchar(64) not null check (btrim(ticket_no) <> ''),
    project_code varchar(64) not null check (btrim(project_code) <> ''),
    title varchar(255) not null check (btrim(title) <> ''),
    description text not null check (btrim(description) <> ''),
    status varchar(20) not null default 'OPEN' check (status in ('OPEN', 'IN_PROGRESS', 'RESOLVED')),
    priority varchar(20) not null default 'MEDIUM' check (priority in ('LOW', 'MEDIUM', 'HIGH')),
    created_at timestamptz not null default current_timestamp,
    updated_at timestamptz not null default current_timestamp,

    constraint uk_business_ticket_tenant_no unique (tenant_id, ticket_no),

    constraint fk_business_ticket_project foreign key (tenant_id, project_code)
        references business_project (tenant_id, project_code) on delete restrict
);

create index idx_business_ticket_tenant_project_status_id
    on business_ticket (tenant_id, project_code, status, id);