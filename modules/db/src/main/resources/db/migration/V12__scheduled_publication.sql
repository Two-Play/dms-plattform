-- Scheduled publication and editorial minor versions (business concept 5.2, 6.3; US-06.3, US-05.2; ADR 0026).
--
-- An approved version becomes valid at 00:00 of its valid-from date in the tenant's time zone: a job publishes it
-- and supersedes the valid version of the same scope in one transaction. The database guarantees that there is
-- never a moment with two valid versions (unique index document_version_one_valid of V9) or with none: a valid
-- version can only be superseded by naming its successor, which must be valid in the same scope when the
-- transaction commits (deferred constraint trigger). Readers in other transactions see either the old or the new
-- valid version, never both or neither.
--
-- This migration adds columns and separate triggers; it does not replace the guard function of V9/V10.

-- Time zone of the tenant: scheduled publications switch at 00:00 local time.
alter table dms.tenant
    add column time_zone text not null default 'Europe/Berlin';

-- Only names PostgreSQL knows (IANA time zone database), so that "at time zone" never fails later.
create function dms.tenant_time_zone_known() returns trigger
    language plpgsql as
$$
begin
    if not exists (select 1 from pg_timezone_names where name = new.time_zone) then
        raise exception 'unknown time zone %', new.time_zone using errcode = '22023';
    end if;
    return new;
end
$$;

create trigger tenant_time_zone_known
    before insert or update of time_zone on dms.tenant
    for each row execute function dms.tenant_time_zone_known();

alter table dms.document_version
    -- Valid-from date of the approved version, taken from its validFrom field on approval; frozen afterwards.
    add column valid_from               date,
    -- The version that replaced this one; set when the version is superseded. Readers are pointed to it.
    add column superseded_by            uuid,
    -- Whether publishing the version asks readers for a new acknowledgement (business concept 6.3): by default
    -- for major versions only. Set when the version is created.
    add column requires_acknowledgement boolean not null default true,
    add foreign key (tenant_id, superseded_by) references dms.document_version (tenant_id, id);

-- Existing versions: minor versions never asked for acknowledgement, approved ones get their valid-from date from
-- the metadata snapshot, superseded ones their successor (the next released version of the same scope). The V9/V10
-- guard does not watch these columns; the guard below is created afterwards.
update dms.document_version
set requires_acknowledgement = false
where change_kind = 'Minor';

update dms.document_version
set valid_from = (metadata_snapshot #>> '{values,validFrom,value}')::date
where metadata_snapshot is not null
  and metadata_snapshot #>> '{values,validFrom,type}' = 'Date';

update dms.document_version v
set superseded_by = (select s.id
                     from dms.document_version s
                     where s.document_id = v.document_id
                       and s.scope = v.scope
                       and s.released_at is not null
                       and (s.major, s.minor) > (v.major, v.minor)
                     order by s.major, s.minor
                     limit 1)
where v.status = 'Superseded';

-- Approved versions waiting for their valid-from date, as the publication job reads them.
create index document_version_scheduled on dms.document_version (tenant_id, valid_from)
    where status = 'Approved';

create function dms.document_version_publication_guard() returns trigger
    language plpgsql as
$$
begin
    if tg_op = 'INSERT' then
        if new.valid_from is not null or new.superseded_by is not null then
            raise exception 'a new document version has no valid-from date and no successor yet';
        end if;
        return new;
    end if;
    if new.requires_acknowledgement is distinct from old.requires_acknowledgement then
        raise exception 'whether document version %.% of % requires acknowledgement is decided on creation',
            old.major, old.minor, old.document_id;
    end if;
    -- The valid-from date is recorded once, on approval, like the metadata snapshot it comes from.
    if new.valid_from is distinct from old.valid_from
        and not (old.status = 'InApproval' and new.status = 'Approved') then
        raise exception 'the valid-from date of a document version is recorded only on approval';
    end if;
    -- The successor is named exactly when a valid version is superseded, and never changes afterwards.
    if new.superseded_by is distinct from old.superseded_by
        and not (old.status in ('Published', 'ReviewDue') and new.status = 'Superseded') then
        raise exception 'the successor of a document version is recorded only when it is superseded';
    end if;
    if new.status = 'Superseded' and old.status in ('Published', 'ReviewDue') and new.superseded_by is null then
        raise exception 'document version %.% of % can only be superseded by naming its successor', old.major,
            old.minor, old.document_id;
    end if;
    return new;
end
$$;

create trigger document_version_publication_guard
    before insert or update on dms.document_version
    for each row execute function dms.document_version_publication_guard();

-- Never a moment without a valid version: when the transaction that superseded a valid version commits, its
-- successor must be valid in the same document and scope. Superseding and publishing therefore happen in one
-- transaction, in either order (the unique index requires superseding first).
create function dms.document_version_successor_valid() returns trigger
    language plpgsql as
$$
begin
    if not exists (select 1
                   from dms.document_version s
                   where s.id = new.superseded_by
                     and s.document_id = new.document_id
                     and s.scope = new.scope
                     and s.status in ('Published', 'ReviewDue')) then
        raise exception 'document version %.% of % was superseded, but its successor is not valid in scope ''%''',
            new.major, new.minor, new.document_id, new.scope;
    end if;
    return null;
end
$$;

create constraint trigger document_version_successor_valid
    after update of status on dms.document_version
    deferrable initially deferred
    for each row
    when (old.status in ('Published', 'ReviewDue') and new.status = 'Superseded')
    execute function dms.document_version_successor_valid();

grant update (valid_from, superseded_by) on dms.document_version to dms_app;
