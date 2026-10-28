-- Legal holds (business concept 7.4, US-08.1, DMS-56, ADR 0031).
--
-- A legal hold suspends the destruction of documents for a litigation or another matter until it is released
-- explicitly. A hold names the matter and covers any number of documents; a document can be covered by several
-- holds and is under legal hold while at least one active (not released) hold covers it. A hold covers the whole
-- document, i.e. all its versions, including versions created later.
--
-- Holds and their documents keep their history: a hold is released once (with reason), never deleted; documents
-- are added to a hold, never removed. The destruction path (DMS-82) must not destroy a document under legal hold;
-- the trigger on dms.document_version enforces this for every database user.

create table dms.legal_hold (
    id               uuid primary key,
    tenant_id        uuid        not null references dms.tenant (id),
    name             text        not null check (length(trim(name)) > 0),
    -- Reference of the matter (Vorgang) in the legal department's systems, e.g. a case number.
    matter_reference text check (matter_reference is null or length(trim(matter_reference)) > 0),
    reason           text        not null check (length(trim(reason)) > 0),
    created_by       text        not null,
    created_at       timestamptz not null default now(),
    released_at      timestamptz,
    released_by      text,
    release_reason   text check (release_reason is null or length(trim(release_reason)) > 0),
    unique (tenant_id, id),
    check ((released_at is null) = (released_by is null)),
    check ((released_at is null) = (release_reason is null)),
    check (released_at is null or released_at >= created_at)
);

create index legal_hold_active on dms.legal_hold (tenant_id, created_at) where released_at is null;
create index legal_hold_matter on dms.legal_hold (tenant_id, matter_reference);

-- The documents a hold covers, with who added them how and why.
create table dms.legal_hold_document (
    tenant_id   uuid        not null,
    hold_id     uuid        not null,
    document_id uuid        not null,
    -- Document: named by the records manager; Search: a hit of a search the records manager confirmed.
    added_via   text        not null check (added_via in ('Document', 'Search')),
    reason      text        not null check (length(trim(reason)) > 0),
    added_by    text        not null,
    added_at    timestamptz not null default now(),
    primary key (hold_id, document_id),
    foreign key (tenant_id, hold_id) references dms.legal_hold (tenant_id, id),
    -- Also serializes against destruction: the foreign key check locks the document row (KEY SHARE), which the
    -- destruction guard below locks FOR UPDATE.
    foreign key (tenant_id, document_id) references dms.document (tenant_id, id)
);

create index legal_hold_document_document on dms.legal_hold_document (tenant_id, document_id);

-- A hold changes only by its release, once; it is never deleted.
create function dms.legal_hold_guard() returns trigger
    language plpgsql as
$$
begin
    if tg_op = 'DELETE' then
        raise exception 'dms.legal_hold keeps its history: DELETE is not allowed';
    end if;
    if old.released_at is not null
        or (new.id, new.tenant_id, new.name, new.matter_reference, new.reason, new.created_by, new.created_at)
        is distinct from (old.id, old.tenant_id, old.name, old.matter_reference, old.reason, old.created_by,
                          old.created_at) then
        raise exception 'a legal hold cannot change; it can only be released once';
    end if;
    return new;
end
$$;

create trigger legal_hold_guard
    before update or delete on dms.legal_hold
    for each row execute function dms.legal_hold_guard();

-- Documents are only added to an active hold. The share lock waits for a concurrent release of the hold.
create function dms.legal_hold_document_guard() returns trigger
    language plpgsql as
$$
begin
    if not exists (select 1 from dms.legal_hold h
                   where h.id = new.hold_id and h.released_at is null
                   for share) then
        raise exception 'legal hold % is released or does not exist; documents cannot be added', new.hold_id;
    end if;
    return new;
end
$$;

create trigger legal_hold_document_guard
    before insert on dms.legal_hold_document
    for each row execute function dms.legal_hold_document_guard();

create trigger legal_hold_document_immutable
    before update or delete on dms.legal_hold_document
    for each row execute function dms.reject_modification();

alter table dms.legal_hold enable row level security;
create policy tenant_isolation on dms.legal_hold
    using (tenant_id = dms.current_tenant());

alter table dms.legal_hold_document enable row level security;
create policy tenant_isolation on dms.legal_hold_document
    using (tenant_id = dms.current_tenant());

grant select, insert on dms.legal_hold to dms_app;
grant update (released_at, released_by, release_reason) on dms.legal_hold to dms_app;
grant select, insert on dms.legal_hold_document to dms_app;

-- Whether an active hold covers the document. For the destruction proposals and the destruction path (DMS-82):
-- documents for which it is true never appear in a proposal and cannot be destroyed. Runs with the caller's
-- rights, so row-level security limits it to the current tenant.
create function dms.document_under_legal_hold(document uuid) returns boolean
    language sql stable as
$$
select exists (select 1
               from dms.legal_hold_document m
               join dms.legal_hold h on h.id = m.hold_id
               where m.document_id = document
                 and h.released_at is null)
$$;

grant execute on function dms.document_under_legal_hold(uuid) to dms_app;

-- No version of a document under legal hold becomes Destroyed. The document row is locked first, so a hold
-- that is being added concurrently is either seen here or waits until the destruction is committed.
create function dms.document_version_legal_hold_guard() returns trigger
    language plpgsql as
$$
begin
    if new.status = 'Destroyed' and old.status is distinct from 'Destroyed' then
        perform 1 from dms.document d where d.id = new.document_id for update;
        if dms.document_under_legal_hold(new.document_id) then
            raise exception 'document % is under legal hold and cannot be destroyed', new.document_id;
        end if;
    end if;
    return new;
end
$$;

create trigger document_version_legal_hold_guard
    before update of status on dms.document_version
    for each row execute function dms.document_version_legal_hold_guard();

-- A rendition attached to a version of a document under legal hold gets the object legal hold as well: the
-- synchronization job (archive module) sets it in object storage after the commit.
create function dms.document_version_rendition_legal_hold() returns trigger
    language plpgsql as
$$
begin
    if new.rendition_version is not null
        and new.rendition_version is distinct from old.rendition_version
        and dms.document_under_legal_hold(new.document_id) then
        perform dms.job_enqueue(new.tenant_id, 'archive.legal-hold-sync',
                                jsonb_build_object('documentId', new.document_id), null, null, 10);
    end if;
    return new;
end
$$;

create trigger document_version_rendition_legal_hold
    after update of rendition_version on dms.document_version
    for each row execute function dms.document_version_rendition_legal_hold();
