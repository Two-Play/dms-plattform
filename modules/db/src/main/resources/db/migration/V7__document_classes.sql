-- Document classes and versioned field definitions (business concept 3.1, 3.2; ADR 0019).
--
-- All three tables keep their history: a change inserts a new version, rows are never updated or deleted.
-- Core and system fields (document id, title, status, ...) are built into the application and not stored.
-- The append-only guard reuses dms.reject_modification() from V4.

-- One row per version of a central field definition. field_type holds the type and its validation rules.
create table dms.field_definition (
    tenant_id   uuid        not null references dms.tenant (id),
    key         text        not null check (key ~ '^[a-z][a-zA-Z0-9]{0,62}$'),
    version     integer     not null check (version >= 1),
    label       text        not null check (length(trim(label)) > 0),
    description text,
    field_type  jsonb       not null,
    multiple    boolean     not null default false,
    created_at  timestamptz not null default now(),
    created_by  text        not null,
    primary key (tenant_id, key, version)
);

-- Identity and position in the hierarchy of a document class; the parent never changes.
create table dms.document_class (
    id         uuid primary key default gen_random_uuid(),
    tenant_id  uuid        not null references dms.tenant (id),
    key        text        not null check (key ~ '^[a-z][a-z0-9-]{0,62}$'),
    parent_id  uuid,
    created_at timestamptz not null default now(),
    created_by text        not null,
    unique (tenant_id, key),
    unique (tenant_id, id),
    -- Composite key: a parent of another tenant cannot be referenced (foreign keys bypass RLS).
    foreign key (tenant_id, parent_id) references dms.document_class (tenant_id, id),
    check (parent_id is null or parent_id <> id)
);

-- One row per profile version of a class. fields: own field bindings with field version and the statuses in
-- which the field is required; parent_version pins the parent's profile version.
create table dms.document_class_version (
    tenant_id      uuid        not null,
    class_id       uuid        not null,
    version        integer     not null check (version >= 1),
    parent_version integer check (parent_version >= 1),
    label          text        not null check (length(trim(label)) > 0),
    description    text,
    fields         jsonb       not null default '[]',
    created_at     timestamptz not null default now(),
    created_by     text        not null,
    primary key (class_id, version),
    foreign key (tenant_id, class_id) references dms.document_class (tenant_id, id)
);

create trigger field_definition_guard
    before update or delete on dms.field_definition
    for each row execute function dms.reject_modification();
create trigger document_class_guard
    before update or delete on dms.document_class
    for each row execute function dms.reject_modification();
create trigger document_class_version_guard
    before update or delete on dms.document_class_version
    for each row execute function dms.reject_modification();

alter table dms.field_definition enable row level security;
create policy tenant_isolation on dms.field_definition
    using (tenant_id = dms.current_tenant());

alter table dms.document_class enable row level security;
create policy tenant_isolation on dms.document_class
    using (tenant_id = dms.current_tenant());

alter table dms.document_class_version enable row level security;
create policy tenant_isolation on dms.document_class_version
    using (tenant_id = dms.current_tenant());

-- Insert-only for the application: versions are added, never changed.
grant select, insert on dms.field_definition to dms_app;
grant select, insert on dms.document_class to dms_app;
grant select, insert on dms.document_class_version to dms_app;
