-- Documents and their versions (business concept 4.3, 5.1, 5.2; ADR 0021).
--
-- A document is a stable identity created from a released template version. Its content lives in versions; each
-- version is a complete version package: structured content, field values with their origin, metadata snapshot,
-- template and class version, rendition and checksum. A version is worked on while it is open (Draft, InReview,
-- InApproval); only drafts change. From approval on the package is frozen. Status changes follow the document
-- lifecycle (dms.documents.Lifecycle). A document has at most one open version and at most one valid version
-- (Published, ReviewDue) per validity scope. The guards below enforce these rules for every database user; the
-- application checks them before.

create table dms.document (
    id               uuid primary key,
    tenant_id        uuid        not null references dms.tenant (id),
    class_id         uuid        not null,
    class_version    integer     not null,
    title            text        not null check (length(trim(title)) > 0),
    owner_id         text,
    author_id        text        not null,
    template_id      uuid        not null,
    template_version integer     not null,
    created_at       timestamptz not null default now(),
    updated_at       timestamptz not null default now(),
    unique (tenant_id, id),
    -- Composite keys: a class or template of another tenant cannot be referenced (foreign keys bypass RLS).
    foreign key (tenant_id, class_id) references dms.document_class (tenant_id, id),
    foreign key (class_id, class_version) references dms.document_class_version (class_id, version),
    foreign key (tenant_id, template_id) references dms.template (tenant_id, id),
    foreign key (template_id, template_version) references dms.template_version (template_id, version)
);

create table dms.document_version (
    id                uuid primary key,
    tenant_id         uuid        not null,
    document_id       uuid        not null,
    -- The number the version gets on approval (business concept 5.2): major for content changes, minor for
    -- editorial ones. Shown as 1.1-draft.3 while open.
    major             integer     not null check (major >= 1),
    minor             integer     not null check (minor >= 0),
    working_state     integer     not null default 1 check (working_state >= 1),
    status            text        not null default 'Draft'
        check (status in ('Draft', 'InReview', 'InApproval', 'Approved', 'Published', 'ReviewDue', 'Superseded',
                          'Withdrawn', 'Archived', 'Destroyed')),
    change_kind       text        not null default 'Major' check (change_kind in ('Major', 'Minor')),
    change_reason     text,
    -- Validity scope derived from the scope field ('' = unrestricted), see Versioning.scopeKey.
    scope             text        not null default '',
    class_version     integer     not null,
    template_id       uuid        not null,
    template_version  integer     not null,
    -- Structured content (ADR 0007) in the versioned template content format, see modules/templates/README.md.
    content           jsonb       not null,
    -- Field values keyed by field key: {"key": {"value": {...}, "origin": {"type": "Manual"}}}.
    field_values      jsonb       not null default '{}',
    -- Complete metadata as approved, including system fields; set once on approval.
    metadata_snapshot jsonb,
    rendition_key     text,
    rendition_version text,
    rendition_sha256  text check (rendition_sha256 ~ '^[0-9a-f]{64}$'),
    created_at        timestamptz not null default now(),
    created_by        text        not null,
    updated_at        timestamptz not null default now(),
    status_changed_at timestamptz not null default now(),
    status_changed_by text        not null,
    released_at       timestamptz,
    released_by       text,
    unique (tenant_id, id),
    foreign key (tenant_id, document_id) references dms.document (tenant_id, id),
    foreign key (template_id, template_version) references dms.template_version (template_id, version),
    check (change_kind = 'Major' or length(trim(coalesce(change_reason, ''))) > 0),
    check ((rendition_key is null) = (rendition_sha256 is null)),
    check ((released_by is null) = (released_at is null)),
    -- Segregation of duties (business concept 2.4): the author of a version cannot approve it.
    check (released_by is null or released_by <> created_by),
    check (status in ('Draft', 'InReview', 'InApproval', 'Withdrawn')
        or (metadata_snapshot is not null and released_by is not null and rendition_sha256 is not null))
);

-- A version number exists once per document; a withdrawn draft gives its number back.
create unique index document_version_number on dms.document_version (document_id, major, minor)
    where status <> 'Withdrawn';

-- At most one open version per document (business concept 5.2: no competing drafts).
create unique index document_version_one_open on dms.document_version (document_id)
    where status in ('Draft', 'InReview', 'InApproval');

-- At most one valid version per document and validity scope.
create unique index document_version_one_valid on dms.document_version (document_id, scope)
    where status in ('Published', 'ReviewDue');

-- Working states of open versions, one row per saved state (1.1-draft.1, 1.1-draft.2, ...). Insert-only; compacting
-- them after approval (business concept 5.2) is a follow-up.
create table dms.document_working_state (
    tenant_id     uuid        not null,
    version_id    uuid        not null,
    working_state integer     not null check (working_state >= 1),
    content       jsonb       not null,
    field_values  jsonb       not null,
    saved_at      timestamptz not null default now(),
    saved_by      text        not null,
    primary key (version_id, working_state),
    foreign key (tenant_id, version_id) references dms.document_version (tenant_id, id)
);

create function dms.document_guard() returns trigger
    language plpgsql as
$$
begin
    if tg_op = 'DELETE' then
        raise exception 'dms.document cannot be deleted; destruction is a lifecycle step';
    end if;
    if (new.id, new.tenant_id, new.class_id, new.class_version, new.author_id, new.template_id,
        new.template_version, new.created_at)
        is distinct from (old.id, old.tenant_id, old.class_id, old.class_version, old.author_id, old.template_id,
                          old.template_version, old.created_at) then
        raise exception 'the identity of a document cannot change';
    end if;
    return new;
end
$$;

create trigger document_guard
    before update or delete on dms.document
    for each row execute function dms.document_guard();

-- Documents are only created from released template versions (business concept 4.1).
create function dms.document_template_released() returns trigger
    language plpgsql as
$$
begin
    if not exists (select 1 from dms.template_version
                   where template_id = new.template_id and version = new.template_version and status = 'Released') then
        raise exception 'template % version % is not released', new.template_id, new.template_version;
    end if;
    return new;
end
$$;

create trigger document_template_released
    before insert on dms.document
    for each row execute function dms.document_template_released();

create function dms.document_version_guard() returns trigger
    language plpgsql as
$$
begin
    if tg_op = 'DELETE' then
        raise exception 'dms.document_version is append-only: DELETE is not allowed';
    end if;
    -- A version starts as a draft; everything else happens through status changes.
    if tg_op = 'INSERT' then
        if new.status <> 'Draft' or new.metadata_snapshot is not null or new.released_by is not null then
            raise exception 'a document version starts as an unapproved draft';
        end if;
        return new;
    end if;
    if (new.id, new.tenant_id, new.document_id, new.major, new.minor, new.change_kind, new.template_id,
        new.created_at, new.created_by)
        is distinct from (old.id, old.tenant_id, old.document_id, old.major, old.minor, old.change_kind,
                          old.template_id, old.created_at, old.created_by) then
        raise exception 'the identity of a document version cannot change';
    end if;
    -- Only drafts change: content, field values, working state, scope, template and class version.
    if (new.content, new.field_values, new.working_state, new.scope, new.change_reason, new.class_version,
        new.template_version)
        is distinct from (old.content, old.field_values, old.working_state, old.scope, old.change_reason,
                          old.class_version, old.template_version)
        and not (old.status = 'Draft' and new.status = 'Draft') then
        raise exception 'document version %.% of % is %: only drafts can be changed', old.major, old.minor,
            old.document_id, old.status;
    end if;
    if new.working_state < old.working_state then
        raise exception 'working states are counted continuously and cannot decrease';
    end if;
    -- The rendition may change while the version is open and is frozen from approval on.
    if (new.rendition_key, new.rendition_version, new.rendition_sha256)
        is distinct from (old.rendition_key, old.rendition_version, old.rendition_sha256)
        and not (old.status in ('Draft', 'InReview', 'InApproval') and new.status in ('Draft', 'InReview', 'InApproval')) then
        raise exception 'the rendition of document version %.% of % is frozen', old.major, old.minor, old.document_id;
    end if;
    -- Status changes follow the document lifecycle (dms.documents.Lifecycle).
    if new.status is distinct from old.status and not (old.status, new.status) in (
        ('Draft', 'InReview'), ('Draft', 'Withdrawn'), ('InReview', 'Draft'), ('InReview', 'InApproval'),
        ('InApproval', 'Draft'), ('InApproval', 'Approved'), ('Approved', 'Published'), ('Published', 'ReviewDue'),
        ('ReviewDue', 'Published'), ('Published', 'Superseded'), ('ReviewDue', 'Superseded'),
        ('Superseded', 'Archived'), ('Withdrawn', 'Archived'), ('Archived', 'Destroyed')) then
        raise exception 'document version status cannot change from % to %', old.status, new.status;
    end if;
    -- The metadata snapshot and the approval are recorded once, on approval.
    if (new.metadata_snapshot, new.released_by, new.released_at)
        is distinct from (old.metadata_snapshot, old.released_by, old.released_at)
        and not (old.status = 'InApproval' and new.status = 'Approved') then
        raise exception 'the metadata snapshot and approval of a document version are recorded only on approval';
    end if;
    return new;
end
$$;

create trigger document_version_guard
    before insert or update or delete on dms.document_version
    for each row execute function dms.document_version_guard();

create trigger document_working_state_guard
    before update or delete on dms.document_working_state
    for each row execute function dms.reject_modification();

alter table dms.document enable row level security;
create policy tenant_isolation on dms.document
    using (tenant_id = dms.current_tenant());

alter table dms.document_version enable row level security;
create policy tenant_isolation on dms.document_version
    using (tenant_id = dms.current_tenant());

alter table dms.document_working_state enable row level security;
create policy tenant_isolation on dms.document_working_state
    using (tenant_id = dms.current_tenant());

-- Updates are limited to the columns a draft edit or a status change touches; the guards check the rest.
grant select, insert on dms.document to dms_app;
grant update (title, owner_id, updated_at) on dms.document to dms_app;
grant select, insert on dms.document_version to dms_app;
grant update (working_state, status, scope, change_reason, class_version, template_version, content, field_values,
              metadata_snapshot, rendition_key, rendition_version, rendition_sha256, updated_at, status_changed_at,
              status_changed_by, released_at, released_by)
    on dms.document_version to dms_app;
grant select, insert on dms.document_working_state to dms_app;
