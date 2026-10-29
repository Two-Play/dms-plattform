-- Attachments of document versions (US-03.x, DMS-114, ADR 0041).
--
-- An attachment is a file (any type within the configured size limit and outside the blocklist of executable
-- types) that belongs to a document version, so that related material is reviewed, approved and archived together
-- with the document. Two tables:
--
--   dms.document_attachment_file   the stored bytes: one object in object storage per document and SHA-256
--                                  (content-addressed within the document), insert-only, never deleted here.
--   dms.document_version_attachment the link of a file to a version with the name it has there.
--
-- Links change (added, renamed, removed) only while their version is a draft; from submission on, and therefore
-- from approval on, they are frozen like the content (guard below, like dms.document_version_guard). A new
-- version takes over the links of the version it is based on; the bytes are not copied. Removing an attachment
-- from a draft deletes only the link: the file stays, other versions may reference it.

create table dms.document_attachment_file (
    id             uuid primary key,
    tenant_id      uuid        not null,
    document_id    uuid        not null,
    sha256         text        not null check (sha256 ~ '^[0-9a-f]{64}$'),
    size_bytes     bigint      not null check (size_bytes > 0),
    content_type   text        not null check (length(trim(content_type)) > 0),
    object_key     text        not null check (length(trim(object_key)) > 0),
    object_version text        not null check (length(trim(object_version)) > 0),
    -- Result of the virus scan before the file became available: Clean (a scanner checked it) or NotScanned (no
    -- scanner configured). Infected files are rejected and never stored.
    scan_status    text        not null check (scan_status in ('Clean', 'NotScanned')),
    scanner        text        not null,
    scanned_at     timestamptz not null,
    created_at     timestamptz not null default now(),
    created_by     text        not null,
    unique (tenant_id, id),
    -- Content-addressed within a document: the same bytes are stored once per document.
    unique (document_id, sha256),
    foreign key (tenant_id, document_id) references dms.document (tenant_id, id)
);

create table dms.document_version_attachment (
    id          uuid primary key,
    tenant_id   uuid        not null,
    version_id  uuid        not null,
    file_id     uuid        not null,
    -- File name shown and used for downloads; no path separators or control characters.
    name        text        not null check (length(trim(name)) > 0 and length(name) <= 255
        and name !~ '[/\\[:cntrl:]]'),
    added_at    timestamptz not null default now(),
    added_by    text        not null,
    -- The link of the previous version this one was taken over from when the version was started.
    based_on    uuid,
    unique (tenant_id, id),
    foreign key (tenant_id, version_id) references dms.document_version (tenant_id, id),
    foreign key (tenant_id, file_id) references dms.document_attachment_file (tenant_id, id),
    foreign key (tenant_id, based_on) references dms.document_version_attachment (tenant_id, id),
    -- A file is attached once per version.
    unique (version_id, file_id)
);

-- Names are unique per version, ignoring case, so downloads are unambiguous.
create unique index document_version_attachment_name on dms.document_version_attachment (version_id, lower(name));
create index document_version_attachment_file on dms.document_version_attachment (file_id);

create trigger document_attachment_file_immutable
    before update or delete on dms.document_attachment_file
    for each row execute function dms.reject_modification();

-- Links are added, renamed and removed only while their version is a draft; only the name changes. The file must
-- belong to the version's document.
create function dms.document_version_attachment_guard() returns trigger
    language plpgsql as
$$
declare
    target         dms.document_version_attachment;
    version_status text;
    version_doc    uuid;
begin
    if tg_op = 'DELETE' then
        target := old;
    else
        target := new;
    end if;
    if tg_op = 'UPDATE' and (new.id, new.tenant_id, new.version_id, new.file_id, new.added_at, new.added_by,
                             new.based_on)
        is distinct from (old.id, old.tenant_id, old.version_id, old.file_id, old.added_at, old.added_by,
                          old.based_on) then
        raise exception 'an attachment link can only be renamed';
    end if;
    -- Locks the version row so that a concurrent status change waits (or is seen).
    select status, document_id into version_status, version_doc
    from dms.document_version where id = target.version_id for share;
    if version_status is distinct from 'Draft' then
        raise exception 'attachments of document version % are frozen: the version is %', target.version_id,
            coalesce(version_status, 'missing');
    end if;
    if tg_op = 'INSERT' and not exists (select 1 from dms.document_attachment_file f
                                        where f.id = new.file_id and f.document_id = version_doc) then
        raise exception 'attachment file % does not belong to the document of version %', new.file_id,
            new.version_id;
    end if;
    return target;
end
$$;

create trigger document_version_attachment_guard
    before insert or update or delete on dms.document_version_attachment
    for each row execute function dms.document_version_attachment_guard();

alter table dms.document_attachment_file enable row level security;
create policy tenant_isolation on dms.document_attachment_file
    using (tenant_id = dms.current_tenant());

alter table dms.document_version_attachment enable row level security;
create policy tenant_isolation on dms.document_version_attachment
    using (tenant_id = dms.current_tenant());

grant select, insert on dms.document_attachment_file to dms_app;
grant select, insert, delete on dms.document_version_attachment to dms_app;
grant update (name) on dms.document_version_attachment to dms_app;

-- An attachment file stored for a document under legal hold gets the object legal hold as well: the
-- synchronization job (archive module, ADR 0031) sets it in object storage after the commit.
create function dms.document_attachment_file_legal_hold() returns trigger
    language plpgsql as
$$
begin
    if dms.document_under_legal_hold(new.document_id) then
        perform dms.job_enqueue(new.tenant_id, 'archive.legal-hold-sync',
                                jsonb_build_object('documentId', new.document_id), null, null, 10);
    end if;
    return new;
end
$$;

create trigger document_attachment_file_legal_hold
    after insert on dms.document_attachment_file
    for each row execute function dms.document_attachment_file_legal_hold();
