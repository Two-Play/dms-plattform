-- File documents and Word templates (DMS-115, ADR 0042).
--
-- A document is either structured (its content is the structured content of its template, ADR 0007) or a file
-- document: its content is a finished file (e.g. a Word document) uploaded by the author or taken from a Word
-- template. The kind is chosen when the document is created and never changes (no update grant).
--
-- The uploaded original of a version is stored like an attachment (V26): one object per document and SHA-256 in
-- dms.document_attachment_file (content-addressed within the document, scanned before it is stored, covered by the
-- legal hold synchronization), unchanged. dms.document_version_file links it to the version with its file name and,
-- for Word documents, the field definitions found in it (content controls, ADR 0042) and the values entered by hand.
-- The link changes (a new upload, new values) only while the version is a draft; from submission on it is frozen
-- like the content. A new version takes the link of the version it is based on over; the bytes are not copied.
--
-- A Word template is a template version (V8) with a Word file: dms.template_word_file holds the DOCX (one object per
-- template version) and its field definitions. It changes only while the template version is a draft, so the
-- template lifecycle (test, four-eyes release) applies unchanged.

alter table dms.document
    add column kind text not null default 'Structured' check (kind in ('Structured', 'File'));

create table dms.document_version_file (
    version_id  uuid primary key,
    tenant_id   uuid        not null,
    file_id     uuid        not null,
    -- File name of the original, used for downloads; no path separators or control characters.
    name        text        not null check (length(trim(name)) > 0 and length(name) <= 255
        and name !~ '[/\\[:cntrl:]]'),
    -- Field definitions of a Word original: [{"key", "label", "type", "required", "source"}], see ADR 0042.
    word_fields jsonb       not null default '[]' check (jsonb_typeof(word_fields) = 'array'),
    -- Values of the fields filled by hand, by field key (strings).
    word_values jsonb       not null default '{}' check (jsonb_typeof(word_values) = 'object'),
    set_at      timestamptz not null default now(),
    set_by      text        not null,
    -- The version whose file was taken over when this version was started; null once a new file is uploaded.
    based_on    uuid,
    unique (tenant_id, version_id),
    foreign key (tenant_id, version_id) references dms.document_version (tenant_id, id),
    foreign key (tenant_id, file_id) references dms.document_attachment_file (tenant_id, id),
    foreign key (tenant_id, based_on) references dms.document_version (tenant_id, id)
);

create index document_version_file_file on dms.document_version_file (file_id);

-- The file of a version is set and replaced only while the version is a draft of a file document; it is never
-- deleted. The file must belong to the version's document.
create function dms.document_version_file_guard() returns trigger
    language plpgsql as
$$
declare
    version_status text;
    version_doc    uuid;
    document_kind  text;
begin
    if tg_op = 'DELETE' then
        raise exception 'the file of a document version cannot be deleted';
    end if;
    if tg_op = 'UPDATE' and (new.version_id, new.tenant_id) is distinct from (old.version_id, old.tenant_id) then
        raise exception 'the file of a document version cannot move to another version';
    end if;
    -- Locks the version row so that a concurrent status change waits (or is seen).
    select v.status, v.document_id, d.kind into version_status, version_doc, document_kind
    from dms.document_version v join dms.document d on d.id = v.document_id
    where v.id = new.version_id for share of v;
    if document_kind is distinct from 'File' then
        raise exception 'document version % does not belong to a file document', new.version_id;
    end if;
    if version_status is distinct from 'Draft' then
        raise exception 'the file of document version % is frozen: the version is %', new.version_id,
            coalesce(version_status, 'missing');
    end if;
    if not exists (select 1 from dms.document_attachment_file f
                   where f.id = new.file_id and f.document_id = version_doc) then
        raise exception 'file % does not belong to the document of version %', new.file_id, new.version_id;
    end if;
    return new;
end
$$;

create trigger document_version_file_guard
    before insert or update or delete on dms.document_version_file
    for each row execute function dms.document_version_file_guard();

alter table dms.document_version_file enable row level security;
create policy tenant_isolation on dms.document_version_file
    using (tenant_id = dms.current_tenant());

grant select, insert on dms.document_version_file to dms_app;
grant update (file_id, name, word_fields, word_values, set_at, set_by, based_on) on dms.document_version_file
    to dms_app;

create table dms.template_word_file (
    template_id    uuid        not null,
    version        integer     not null,
    tenant_id      uuid        not null,
    name           text        not null check (length(trim(name)) > 0 and length(name) <= 255
        and name !~ '[/\\[:cntrl:]]'),
    sha256         text        not null check (sha256 ~ '^[0-9a-f]{64}$'),
    size_bytes     bigint      not null check (size_bytes > 0),
    object_key     text        not null check (length(trim(object_key)) > 0),
    object_version text        not null check (length(trim(object_version)) > 0),
    scan_status    text        not null check (scan_status in ('Clean', 'NotScanned')),
    -- Field definitions: [{"key", "label", "type", "required", "source"}], see ADR 0042.
    fields         jsonb       not null default '[]' check (jsonb_typeof(fields) = 'array'),
    set_at         timestamptz not null default now(),
    set_by         text        not null,
    primary key (template_id, version),
    foreign key (template_id, version) references dms.template_version (template_id, version),
    foreign key (tenant_id, template_id) references dms.template (tenant_id, id)
);

-- The Word file of a template version changes only while the version is a draft; it is never deleted.
create function dms.template_word_file_guard() returns trigger
    language plpgsql as
$$
declare
    version_status text;
begin
    if tg_op = 'DELETE' then
        raise exception 'the Word file of a template version cannot be deleted';
    end if;
    if tg_op = 'UPDATE' and (new.template_id, new.version, new.tenant_id)
        is distinct from (old.template_id, old.version, old.tenant_id) then
        raise exception 'the Word file of a template version cannot move to another version';
    end if;
    select status into version_status from dms.template_version
    where template_id = new.template_id and version = new.version for share;
    if version_status is distinct from 'Draft' then
        raise exception 'the Word file of template version %.% is frozen: the version is %', new.template_id,
            new.version, coalesce(version_status, 'missing');
    end if;
    return new;
end
$$;

create trigger template_word_file_guard
    before insert or update or delete on dms.template_word_file
    for each row execute function dms.template_word_file_guard();

alter table dms.template_word_file enable row level security;
create policy tenant_isolation on dms.template_word_file
    using (tenant_id = dms.current_tenant());

grant select, insert on dms.template_word_file to dms_app;
grant update (name, sha256, size_bytes, object_key, object_version, scan_status, fields, set_at, set_by)
    on dms.template_word_file to dms_app;
