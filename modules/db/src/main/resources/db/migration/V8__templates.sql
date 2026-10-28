-- Templates and template versions (business concept 4.1; ADR 0007, 0020).
--
-- A template is a stable identity; its content lives in numbered versions. Only drafts can be edited; the status
-- moves Draft -> Testing -> Released -> Deprecated -> Archived (Testing -> Draft is allowed). Once a version has
-- left Draft and Testing, its row is frozen. Releasing requires a second person (four-eyes principle): the
-- releaser neither created nor edited the version. The guard below enforces these rules for every database
-- user, the application also checks them before.

create table dms.template (
    id         uuid primary key,
    tenant_id  uuid        not null references dms.tenant (id),
    created_at timestamptz not null default now(),
    created_by text        not null,
    unique (tenant_id, id)
);

create table dms.template_version (
    tenant_id              uuid        not null,
    template_id            uuid        not null,
    version                integer     not null check (version >= 1),
    name                   text        not null check (length(trim(name)) > 0),
    description            text,
    -- The document class profile version the placeholders are checked against (V7).
    document_class_id      uuid        not null,
    document_class_version integer     not null,
    status                 text        not null default 'Draft'
        check (status in ('Draft', 'Testing', 'Released', 'Deprecated', 'Archived')),
    -- Structured content (ADR 0007) as versioned JSON, see modules/templates/README.md.
    content                jsonb       not null,
    content_format         smallint    not null default 1,
    created_at             timestamptz not null default now(),
    created_by             text        not null,
    -- Users other than the creator who changed the draft, in order of their first change.
    edited_by              text[]      not null default '{}',
    updated_at             timestamptz not null default now(),
    status_changed_at      timestamptz not null default now(),
    status_changed_by      text        not null,
    released_at            timestamptz,
    released_by            text,
    primary key (template_id, version),
    foreign key (tenant_id, template_id) references dms.template (tenant_id, id),
    -- Composite key: a class of another tenant cannot be referenced (foreign keys bypass RLS).
    foreign key (tenant_id, document_class_id) references dms.document_class (tenant_id, id),
    foreign key (document_class_id, document_class_version) references dms.document_class_version (class_id, version),
    check ((released_by is null) = (released_at is null)),
    check (released_by is null or (released_by <> created_by and not released_by = any (edited_by)))
);

-- At most one version per template is being worked on.
create unique index template_version_one_open on dms.template_version (template_id)
    where status in ('Draft', 'Testing');

create index template_version_released on dms.template_version (tenant_id) where status = 'Released';

create trigger template_guard
    before update or delete on dms.template
    for each row execute function dms.reject_modification();

create function dms.template_version_guard() returns trigger
    language plpgsql as
$$
begin
    if tg_op = 'DELETE' then
        raise exception 'dms.template_version is append-only: DELETE is not allowed';
    end if;
    if (new.tenant_id, new.template_id, new.version, new.created_at, new.created_by)
        is distinct from (old.tenant_id, old.template_id, old.version, old.created_at, old.created_by) then
        raise exception 'the identity of a template version cannot change';
    end if;
    if (new.name, new.description, new.document_class_id, new.document_class_version, new.content,
        new.content_format, new.edited_by)
        is distinct from (old.name, old.description, old.document_class_id, old.document_class_version,
                          old.content, old.content_format, old.edited_by)
        and not (old.status = 'Draft' and new.status = 'Draft') then
        raise exception 'template version %.% is %: only drafts can be edited', old.template_id, old.version,
            old.status;
    end if;
    if new.status is distinct from old.status and not (old.status, new.status) in (
        ('Draft', 'Testing'), ('Testing', 'Draft'), ('Testing', 'Released'), ('Released', 'Deprecated'),
        ('Deprecated', 'Archived')) then
        raise exception 'template version status cannot change from % to %', old.status, new.status;
    end if;
    if (new.released_by, new.released_at) is distinct from (old.released_by, old.released_at)
        and not (old.status = 'Testing' and new.status = 'Released') then
        raise exception 'the release of a template version is recorded only when it is released';
    end if;
    if new.status = 'Released' and old.status = 'Testing' and new.released_by is null then
        raise exception 'a released template version needs released_by';
    end if;
    return new;
end
$$;

create trigger template_version_guard
    before update or delete on dms.template_version
    for each row execute function dms.template_version_guard();

alter table dms.template enable row level security;
create policy tenant_isolation on dms.template
    using (tenant_id = dms.current_tenant());

alter table dms.template_version enable row level security;
create policy tenant_isolation on dms.template_version
    using (tenant_id = dms.current_tenant());

grant select, insert on dms.template to dms_app;
-- Updates are limited to the columns a draft edit or a status change touches; the guard checks the rest.
grant select, insert on dms.template_version to dms_app;
grant update (name, description, document_class_id, document_class_version, content, content_format, edited_by,
              updated_at, status, status_changed_at, status_changed_by, released_at, released_by)
    on dms.template_version to dms_app;
