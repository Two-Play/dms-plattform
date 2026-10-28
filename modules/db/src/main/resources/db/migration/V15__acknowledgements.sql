-- Acknowledgement of published versions (business concept 6.3; US-06.2; ADR 0027).
--
-- Readers of a valid version that requires acknowledgement (dms.document_version.requires_acknowledgement, V12)
-- confirm that they read it. Proof: who acknowledged which version (id and label) when, after opening it when.
-- Acknowledging is only possible after the reader opened the version; both are recorded insert-only. The
-- guards below enforce this for every database user; the application checks it before.

-- When the version became valid: set by the database on Approved -> Published, never changed afterwards.
alter table dms.document_version
    add column published_at timestamptz;

-- Existing versions: the time of their publication from the audit trail (document.published carries the
-- version id), or the status change for versions still published.
update dms.document_version v
set published_at = coalesce(
        (select min(a.occurred_at)
         from dms.audit_event a
         where a.tenant_id = v.tenant_id
           and a.action = 'document.published'
           and a.object_id = v.document_id
           and a.details ->> 'versionId' = v.id::text),
        case when v.status = 'Published' then v.status_changed_at end)
where v.status in ('Published', 'ReviewDue', 'Superseded', 'Archived', 'Destroyed')
  and v.released_at is not null;

create function dms.document_version_published_at() returns trigger
    language plpgsql as
$$
begin
    if tg_op = 'INSERT' then
        if new.published_at is not null then
            raise exception 'a new document version is not published yet';
        end if;
        return new;
    end if;
    if old.status = 'Approved' and new.status = 'Published' then
        new.published_at := new.status_changed_at;
    elsif new.published_at is distinct from old.published_at then
        raise exception 'the publication time of a document version is recorded only on publication';
    end if;
    return new;
end
$$;

create trigger document_version_published_at
    before insert or update on dms.document_version
    for each row execute function dms.document_version_published_at();

-- Who opened which version that asks for acknowledgement, first time only (idempotent: on conflict do nothing).
create table dms.document_version_opened (
    tenant_id  uuid        not null,
    version_id uuid        not null,
    user_id    text        not null check (length(trim(user_id)) > 0),
    opened_at  timestamptz not null default now(),
    primary key (version_id, user_id),
    foreign key (tenant_id, version_id) references dms.document_version (tenant_id, id)
);

-- Acknowledgements: one per reader and version, never changed or deleted.
create table dms.document_acknowledgement (
    id              uuid primary key,
    tenant_id       uuid        not null,
    document_id     uuid        not null,
    version_id      uuid        not null,
    -- The version number as the reader saw it, e.g. 2.0.
    version_label   text        not null,
    user_id         text        not null check (length(trim(user_id)) > 0),
    -- When the reader opened the version first, copied from dms.document_version_opened.
    opened_at       timestamptz not null,
    acknowledged_at timestamptz not null default now(),
    unique (version_id, user_id),
    unique (tenant_id, id),
    check (acknowledged_at >= opened_at),
    foreign key (tenant_id, document_id) references dms.document (tenant_id, id),
    foreign key (tenant_id, version_id) references dms.document_version (tenant_id, id)
);

create index document_acknowledgement_user on dms.document_acknowledgement (tenant_id, user_id, acknowledged_at);

-- Opening is recorded only for a valid version that requires acknowledgement.
create function dms.document_version_opened_guard() returns trigger
    language plpgsql as
$$
begin
    if not exists (select 1
                   from dms.document_version v
                   where v.id = new.version_id
                     and v.status in ('Published', 'ReviewDue')
                     and v.requires_acknowledgement) then
        raise exception 'document version % is not a valid version that requires acknowledgement', new.version_id;
    end if;
    return new;
end
$$;

create trigger document_version_opened_guard
    before insert on dms.document_version_opened
    for each row execute function dms.document_version_opened_guard();

create trigger document_version_opened_immutable
    before update or delete on dms.document_version_opened
    for each row execute function dms.reject_modification();

-- An acknowledgement names a valid version of its document that requires acknowledgement, with its number,
-- and follows the reader's first opening of that version.
create function dms.document_acknowledgement_guard() returns trigger
    language plpgsql as
$$
begin
    if not exists (select 1
                   from dms.document_version v
                   where v.id = new.version_id
                     and v.document_id = new.document_id
                     and v.status in ('Published', 'ReviewDue')
                     and v.requires_acknowledgement
                     and new.version_label = v.major || '.' || v.minor) then
        raise exception 'document version % of % is not a valid version that requires acknowledgement as %',
            new.version_id, new.document_id, new.version_label;
    end if;
    if not exists (select 1
                   from dms.document_version_opened o
                   where o.version_id = new.version_id
                     and o.user_id = new.user_id
                     and o.opened_at = new.opened_at) then
        raise exception 'document version % was not opened by % before acknowledging it', new.version_id,
            new.user_id;
    end if;
    return new;
end
$$;

create trigger document_acknowledgement_guard
    before insert on dms.document_acknowledgement
    for each row execute function dms.document_acknowledgement_guard();

create trigger document_acknowledgement_immutable
    before update or delete on dms.document_acknowledgement
    for each row execute function dms.reject_modification();

alter table dms.document_version_opened enable row level security;
create policy tenant_isolation on dms.document_version_opened
    using (tenant_id = dms.current_tenant());

alter table dms.document_acknowledgement enable row level security;
create policy tenant_isolation on dms.document_acknowledgement
    using (tenant_id = dms.current_tenant());

grant select, insert on dms.document_version_opened to dms_app;
grant select, insert on dms.document_acknowledgement to dms_app;
