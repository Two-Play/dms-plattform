-- The additional approval for lowering the confidentiality of a later version (business concept 3.2, DMS-162, ADR 0083).
--
-- 1. dms.document_confidentiality_lowering: one row per request of a draft, never deleted. A request is recorded and
--    withdrawn while the version is a draft, and decided while it is in approval, in the additional approval step of
--    its workflow. At most one request of a version is open or approved.
-- 2. The approved level enters the version in that step, the only change of field values outside a draft: exactly the
--    level, and only in the transaction that approved it. It belongs to the version package like every other value, so
--    the rendition and the metadata snapshot state it. Access stays at from_level until the version is published
--    (DocumentVersion.accessMetadata).
-- 3. A second person decides: neither whoever asked, nor the author, nor an editor, nor someone standing in for one of
--    them. Whoever asks counts as an editor (ADR 0075), the check below is the last line.
-- 4. The workflow of a case that gets or loses the step between two rounds changes with it.

create table dms.document_confidentiality_lowering (
    id              uuid primary key,
    tenant_id       uuid        not null,
    version_id      uuid        not null,
    -- The level the version stands at, and keeps for access until it is published.
    from_level      text        not null check (from_level in ('Internal', 'Confidential', 'StrictlyConfidential')),
    to_level        text        not null check (to_level in ('Public', 'Internal', 'Confidential')),
    reason          text        not null check (length(trim(reason)) > 0),
    requested_by    text        not null,
    requested_at    timestamptz not null default now(),
    status          text        not null default 'Requested'
        check (status in ('Requested', 'Approved', 'Refused', 'Withdrawn')),
    -- Who ended the request: the deciding person, or for 'Withdrawn' who withdrew it.
    decided_by      text,
    decided_at      timestamptz,
    decision_reason text,
    -- The candidate a deputy decided for (ADR 0028).
    acting_for      text,
    unique (tenant_id, id),
    foreign key (tenant_id, version_id) references dms.document_version (tenant_id, id),
    check (array_position(array['Public', 'Internal', 'Confidential', 'StrictlyConfidential'], to_level)
        < array_position(array['Public', 'Internal', 'Confidential', 'StrictlyConfidential'], from_level)),
    check ((status = 'Requested') = (decided_by is null)),
    check ((decided_by is null) = (decided_at is null))
);

create unique index document_confidentiality_lowering_active on dms.document_confidentiality_lowering (version_id)
    where status in ('Requested', 'Approved');

create index document_confidentiality_lowering_version on dms.document_confidentiality_lowering (version_id);

-- What may happen to a request, by the status of its version. Rows are never deleted.
create function dms.document_confidentiality_lowering_guard() returns trigger
    language plpgsql as
$$
declare
    version_status text;
    version_author text;
begin
    if tg_op = 'DELETE' then
        raise exception 'dms.document_confidentiality_lowering cannot be deleted; withdraw the request instead';
    end if;
    -- Locks the version row so that a concurrent status change waits (or is seen).
    select status, created_by into version_status, version_author
    from dms.document_version where id = new.version_id for share;
    if tg_op = 'INSERT' then
        if new.status <> 'Requested' or version_status is distinct from 'Draft' then
            raise exception 'a lowering of the confidentiality is requested while the version is a draft';
        end if;
        return new;
    end if;
    if (new.id, new.tenant_id, new.version_id, new.to_level, new.reason, new.requested_by, new.requested_at)
        is distinct from (old.id, old.tenant_id, old.version_id, old.to_level, old.reason, old.requested_by,
                          old.requested_at) then
        raise exception 'a request to lower the confidentiality cannot change; withdraw it and ask again';
    end if;
    if old.status <> 'Requested' then
        raise exception 'request % to lower the confidentiality is already closed', old.id;
    end if;
    -- The level the version stands at can rise in the draft; the application follows it at submission.
    if new.from_level is distinct from old.from_level
        and not (new.status = 'Requested' and version_status = 'Draft') then
        raise exception 'request % names the level of a draft, which is frozen now', old.id;
    end if;
    if new.status = 'Withdrawn' and version_status is distinct from 'Draft' then
        raise exception 'request % is withdrawn only while the version is a draft', old.id;
    end if;
    if new.status in ('Approved', 'Refused') then
        if version_status is distinct from 'InApproval' then
            raise exception 'request % is decided in the approval of the version', old.id;
        end if;
        if new.decided_by = old.requested_by or new.decided_by = version_author
            or new.acting_for = old.requested_by or new.acting_for = version_author
            or exists (select 1 from dms.document_version_editor e
                       where e.version_id = old.version_id and e.user_id in (new.decided_by, new.acting_for)) then
            raise exception 'segregation of duties: % changed version % or asked for this and cannot decide it',
                new.decided_by, old.version_id;
        end if;
    end if;
    return new;
end
$$;

create trigger document_confidentiality_lowering_guard
    before insert or update or delete on dms.document_confidentiality_lowering
    for each row execute function dms.document_confidentiality_lowering_guard();

alter table dms.document_confidentiality_lowering enable row level security;
create policy tenant_isolation on dms.document_confidentiality_lowering
    using (tenant_id = dms.current_tenant());

grant select, insert on dms.document_confidentiality_lowering to dms_app;
grant update (from_level, status, decided_by, decided_at, decision_reason, acting_for)
    on dms.document_confidentiality_lowering to dms_app;

-- Whether a change of a version in approval is the approved lowering: nothing but the level, to what was approved.
create function dms.document_version_lowering(old_row dms.document_version, new_row dms.document_version)
    returns boolean
    language sql
    stable as
$$
select old_row.status = 'InApproval' and new_row.status = 'InApproval'
    and (new_row.content, new_row.working_state, new_row.scope, new_row.change_reason, new_row.class_version,
         new_row.template_version, new_row.deviation_report)
        is not distinct from (old_row.content, old_row.working_state, old_row.scope, old_row.change_reason,
                              old_row.class_version, old_row.template_version, old_row.deviation_report)
    and new_row.field_values - 'confidentiality' = old_row.field_values - 'confidentiality'
    and exists (select 1 from dms.document_confidentiality_lowering l
                where l.version_id = old_row.id and l.status = 'Approved'
                  and l.to_level = new_row.field_values #>> '{confidentiality,value,code}');
$$;

-- V55's guard, with the approved lowering next to the removal of content.
create or replace function dms.document_version_guard() returns trigger
    language plpgsql as
$$
declare
    removal boolean;
    lowering boolean;
begin
    if tg_op = 'DELETE' then
        raise exception 'dms.document_version is append-only: DELETE is not allowed';
    end if;
    -- A version starts as a draft; everything else happens through status changes.
    if tg_op = 'INSERT' then
        if new.status <> 'Draft' or new.metadata_snapshot is not null or new.released_by is not null
            or new.content_removed_at is not null then
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
    removal := dms.document_version_content_removal(old, new);
    lowering := dms.document_version_lowering(old, new);
    if (new.content_removed_at, new.content_removed_by)
        is distinct from (old.content_removed_at, old.content_removed_by) and not removal then
        raise exception 'the content of document version %.% of % cannot be removed this way', old.major,
            old.minor, old.document_id;
    end if;
    -- Only drafts change: content, field values, working state, scope, template and class version, deviation report.
    if (new.content, new.field_values, new.working_state, new.scope, new.change_reason, new.class_version,
        new.template_version, new.deviation_report)
        is distinct from (old.content, old.field_values, old.working_state, old.scope, old.change_reason,
                          old.class_version, old.template_version, old.deviation_report)
        and not (old.status = 'Draft' and new.status = 'Draft') and not removal and not lowering then
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
        ('Draft', 'InReview'), ('Draft', 'Withdrawn'), ('Draft', 'Discarded'), ('InReview', 'Draft'),
        ('InReview', 'InApproval'), ('InApproval', 'Draft'), ('InApproval', 'Approved'), ('InApproval', 'Withdrawn'),
        ('Approved', 'Published'),
        ('Approved', 'Withdrawn'),
        ('Published', 'ReviewDue'), ('ReviewDue', 'Published'), ('Published', 'Superseded'),
        ('ReviewDue', 'Superseded'), ('Published', 'Withdrawn'), ('ReviewDue', 'Withdrawn'),
        ('Superseded', 'Archived'), ('Withdrawn', 'Archived'), ('Archived', 'Destroyed')) then
        raise exception 'document version status cannot change from % to %', old.status, new.status;
    end if;
    -- Stopped in approval only because its rendition failed for good (ADR 0078).
    if old.status = 'InApproval' and new.status = 'Withdrawn'
        and not exists (select 1 from dms.job j
                        where j.kind = 'document.render-rendition'
                          and j.idempotency_key = 'rendition:' || old.id and j.status = 'dead') then
        raise exception 'document version %.% of % is withdrawn in approval only after its rendition failed for good',
            old.major, old.minor, old.document_id;
    end if;
    -- Destruction is the privileged path (ADR 0054): the content goes, and a second records manager approved it.
    if new.status = 'Destroyed' and old.status is distinct from 'Destroyed' then
        if not removal then
            raise exception 'document version %.% of % is destroyed only together with its content', old.major,
                old.minor, old.document_id;
        end if;
        if not exists (select 1 from dms.destruction_item i
                       join dms.destruction_proposal p on p.id = i.proposal_id
                       where i.version_id = new.id and i.outcome = 'Pending' and p.status = 'Approved'
                         and p.decided_by <> p.proposed_by) then
            raise exception 'document version %.% of % is not part of an approved destruction proposal', old.major,
                old.minor, old.document_id;
        end if;
    end if;
    -- The metadata snapshot and the approval are recorded once, on approval; the removal keeps a subset.
    if (new.metadata_snapshot, new.released_by, new.released_at)
        is distinct from (old.metadata_snapshot, old.released_by, old.released_at)
        and not (old.status = 'InApproval' and new.status = 'Approved') and not removal then
        raise exception 'the metadata snapshot and approval of a document version are recorded only on approval';
    end if;
    return new;
end
$$;

-- A case that gets or loses the additional approval between two rounds runs on another process from then on.
grant update (definition, process_definition_id) on dms.workflow_case to dms_app;
