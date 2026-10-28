-- Deviation requests and deviation reports of document versions (business concept 4.1, US-01.1; ADR 0022).
--
-- An author who wants to change the standard text of a locked template section records a deviation request
-- (section, reason, requester, time) on the draft. A request permits the change; the additional review step of the
-- workflow approves it. Requests can be withdrawn while the version is a draft and are frozen once it leaves Draft.
-- The deviation report (every change of locked sections relative to the template, with the requests of its
-- section) is computed by the application on every draft change, request, withdrawal and submission and stored
-- with the version; like the content, it only changes while the version is a draft.

create table dms.document_deviation_request (
    id           uuid primary key,
    tenant_id    uuid        not null,
    version_id   uuid        not null,
    -- Section id of a locked section of the template content.
    section_id   text        not null check (length(trim(section_id)) > 0),
    reason       text        not null check (length(trim(reason)) > 0),
    requested_by text        not null,
    requested_at timestamptz not null default now(),
    -- A request carried over from the previous version when a new version starts (DocumentService.newVersion).
    based_on     uuid,
    withdrawn_by text,
    withdrawn_at timestamptz,
    unique (tenant_id, id),
    foreign key (tenant_id, version_id) references dms.document_version (tenant_id, id),
    foreign key (tenant_id, based_on) references dms.document_deviation_request (tenant_id, id),
    check ((withdrawn_by is null) = (withdrawn_at is null))
);

-- At most one active request per version and section.
create unique index document_deviation_request_active on dms.document_deviation_request (version_id, section_id)
    where withdrawn_at is null;

create index document_deviation_request_version on dms.document_deviation_request (version_id);

-- Requests are recorded, and withdrawn, only while their version is a draft; they are never deleted and nothing
-- but the withdrawal ever changes.
create function dms.document_deviation_request_guard() returns trigger
    language plpgsql as
$$
declare
    version_status text;
begin
    if tg_op = 'DELETE' then
        raise exception 'dms.document_deviation_request cannot be deleted; withdraw the request instead';
    end if;
    if tg_op = 'UPDATE' then
        if (new.id, new.tenant_id, new.version_id, new.section_id, new.reason, new.requested_by, new.requested_at,
            new.based_on)
            is distinct from (old.id, old.tenant_id, old.version_id, old.section_id, old.reason, old.requested_by,
                              old.requested_at, old.based_on) then
            raise exception 'a deviation request cannot change; withdraw it and record a new one';
        end if;
        if old.withdrawn_at is not null then
            raise exception 'deviation request % is already withdrawn', old.id;
        end if;
    end if;
    -- Locks the version row so that a concurrent status change waits (or is seen).
    select status into version_status from dms.document_version where id = new.version_id for share;
    if version_status is distinct from 'Draft' then
        raise exception 'deviation requests of document version % are frozen: the version is %', new.version_id,
            coalesce(version_status, 'missing');
    end if;
    return new;
end
$$;

create trigger document_deviation_request_guard
    before insert or update or delete on dms.document_deviation_request
    for each row execute function dms.document_deviation_request_guard();

alter table dms.document_deviation_request enable row level security;
create policy tenant_isolation on dms.document_deviation_request
    using (tenant_id = dms.current_tenant());

grant select, insert on dms.document_deviation_request to dms_app;
grant update (withdrawn_by, withdrawn_at) on dms.document_deviation_request to dms_app;

-- The deviation report of a version, in the stored format of the templates module (modules/templates/README.md).
-- Existing versions cannot deviate from their template: without requests, locked sections could not change.
alter table dms.document_version
    add column deviation_report jsonb not null default '{"format":1,"deviations":[]}';

grant update (deviation_report) on dms.document_version to dms_app;

-- V9's guard, with the deviation report among the columns that only change while the version is a draft.
create or replace function dms.document_version_guard() returns trigger
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
    -- Only drafts change: content, field values, working state, scope, template and class version, deviation report.
    if (new.content, new.field_values, new.working_state, new.scope, new.change_reason, new.class_version,
        new.template_version, new.deviation_report)
        is distinct from (old.content, old.field_values, old.working_state, old.scope, old.change_reason,
                          old.class_version, old.template_version, old.deviation_report)
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
