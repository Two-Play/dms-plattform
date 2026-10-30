-- Destruction after retention, compaction of working states, removal of discarded drafts' content and clean-up of
-- orphaned objects (DMS-82, ADR 0054; follow-ups of ADR 0021, 0031, 0041, 0042 and 0050).
--
-- Destruction is the privileged path ADR 0021 asked for: a version whose retention ended is proposed by a records
-- manager, approved by a second one (four eyes, checked here as well) and only then destroyed. The version row stays
-- as a tombstone (status Destroyed, number, approval, rendition checksum, a reduced metadata snapshot); its content,
-- field values, working states, attachment links and original are removed, and the objects no version references
-- any more are deleted from object storage after the commit unless Object Lock protects them. The audit trail is
-- not touched, so the hash chain and the evidence reports stay intact.

-- Retention rules, referenced by the key in the core field `retentionRule` of a version's metadata snapshot.
create table dms.retention_rule (
    tenant_id     uuid        not null references dms.tenant (id),
    key           text        not null check (key ~ '^[a-z0-9][a-z0-9._-]{0,62}$'),
    name          text        not null check (length(trim(name)) > 0 and length(name) <= 200),
    -- Retention period in months after the trigger event.
    period_months integer     not null check (period_months between 1 and 1200),
    -- EndOfValidity: the version went out of force (withdrawn or superseded); Approval: the version was approved;
    -- Creation: the document was created.
    trigger_event text        not null check (trigger_event in ('EndOfValidity', 'Approval', 'Creation')),
    -- The period starts at the end of the year of the trigger event (typical for commercial and tax law).
    from_year_end boolean     not null default true,
    created_at    timestamptz not null default now(),
    created_by    text        not null,
    updated_at    timestamptz not null default now(),
    updated_by    text        not null,
    primary key (tenant_id, key)
);

create trigger retention_rule_no_delete
    before delete on dms.retention_rule
    for each row execute function dms.reject_modification();

alter table dms.retention_rule enable row level security;
create policy tenant_isolation on dms.retention_rule
    using (tenant_id = dms.current_tenant());

grant select, insert on dms.retention_rule to dms_app;
grant update (name, period_months, trigger_event, from_year_end, updated_at, updated_by) on dms.retention_rule
    to dms_app;

-- A destruction proposal: versions whose retention ended, proposed with a reason and decided once by a second
-- records manager (approved: destroyed in the same transaction; rejected: nothing happens).
create table dms.destruction_proposal (
    id              uuid primary key,
    tenant_id       uuid        not null references dms.tenant (id),
    status          text        not null default 'Proposed' check (status in ('Proposed', 'Approved', 'Rejected')),
    reason          text        not null check (length(trim(reason)) > 0 and length(reason) <= 4000),
    proposed_by     text        not null,
    proposed_at     timestamptz not null default now(),
    decided_by      text,
    decided_at      timestamptz,
    decision_reason text check (decision_reason is null or
                                (length(trim(decision_reason)) > 0 and length(decision_reason) <= 4000)),
    unique (tenant_id, id),
    check ((status = 'Proposed') = (decided_by is null)),
    check ((decided_by is null) = (decided_at is null)),
    check ((decided_by is null) = (decision_reason is null)),
    -- Four eyes (business concept 7.4): whoever proposed a destruction cannot decide it.
    constraint destruction_proposal_four_eyes check (decided_by is null or decided_by <> proposed_by),
    check (decided_at is null or decided_at >= proposed_at)
);

create index destruction_proposal_status on dms.destruction_proposal (tenant_id, status, proposed_at);

-- The versions of a proposal with the retention that ended and, once decided, what happened to each.
create table dms.destruction_item (
    tenant_id          uuid        not null,
    proposal_id        uuid        not null,
    version_id         uuid        not null,
    document_id        uuid        not null,
    retention_rule     text        not null,
    retention_ended_on date        not null,
    -- Pending: in an undecided (or currently executed) proposal; Destroyed; Blocked: not destroyed on approval (legal
    -- hold, retention not ended any more, status changed); Rejected: the proposal was rejected.
    outcome            text        not null default 'Pending'
        check (outcome in ('Pending', 'Destroyed', 'Blocked', 'Rejected')),
    outcome_detail     text,
    outcome_at         timestamptz,
    -- What was destroyed (tombstone of the destruction record): checksums of the rendition, of the approved content
    -- and of the files of the version ([{"name", "sha256", "role", "released"}]).
    rendition_sha256   text check (rendition_sha256 ~ '^[0-9a-f]{64}$'),
    content_sha256     text check (content_sha256 ~ '^[0-9a-f]{64}$'),
    files              jsonb       not null default '[]' check (jsonb_typeof(files) = 'array'),
    primary key (proposal_id, version_id),
    foreign key (tenant_id, proposal_id) references dms.destruction_proposal (tenant_id, id),
    foreign key (tenant_id, version_id) references dms.document_version (tenant_id, id),
    foreign key (tenant_id, document_id) references dms.document (tenant_id, id),
    check ((outcome = 'Pending') = (outcome_at is null))
);

-- A version is in at most one open proposal.
create unique index destruction_item_pending on dms.destruction_item (version_id) where outcome = 'Pending';
create index destruction_item_document on dms.destruction_item (tenant_id, document_id);

-- A proposal is decided once; it is never deleted.
create function dms.destruction_proposal_guard() returns trigger
    language plpgsql as
$$
begin
    if tg_op = 'DELETE' then
        raise exception 'dms.destruction_proposal keeps its history: DELETE is not allowed';
    end if;
    if old.status <> 'Proposed'
        or (new.id, new.tenant_id, new.reason, new.proposed_by, new.proposed_at)
        is distinct from (old.id, old.tenant_id, old.reason, old.proposed_by, old.proposed_at) then
        raise exception 'a destruction proposal cannot change; it is decided once';
    end if;
    return new;
end
$$;

create trigger destruction_proposal_guard
    before update or delete on dms.destruction_proposal
    for each row execute function dms.destruction_proposal_guard();

-- Items are added only to undecided proposals and only for versions out of force; their outcome is set once.
create function dms.destruction_item_guard() returns trigger
    language plpgsql as
$$
declare
    version_status text;
    version_doc    uuid;
begin
    if tg_op = 'DELETE' then
        raise exception 'dms.destruction_item keeps its history: DELETE is not allowed';
    end if;
    if tg_op = 'INSERT' then
        if not exists (select 1 from dms.destruction_proposal p
                       where p.id = new.proposal_id and p.status = 'Proposed' for share) then
            raise exception 'destruction proposal % is decided or does not exist', new.proposal_id;
        end if;
        select status, document_id into version_status, version_doc
        from dms.document_version where id = new.version_id;
        if version_doc is distinct from new.document_id then
            raise exception 'version % does not belong to document %', new.version_id, new.document_id;
        end if;
        if version_status is null or version_status not in ('Superseded', 'Withdrawn', 'Archived') then
            raise exception 'version % is %: only versions out of force can be proposed for destruction',
                new.version_id, version_status;
        end if;
        if new.outcome <> 'Pending' then
            raise exception 'a destruction item starts pending';
        end if;
        return new;
    end if;
    if old.outcome <> 'Pending' or new.outcome = 'Pending'
        or (new.tenant_id, new.proposal_id, new.version_id, new.document_id, new.retention_rule,
            new.retention_ended_on)
        is distinct from (old.tenant_id, old.proposal_id, old.version_id, old.document_id, old.retention_rule,
                          old.retention_ended_on) then
        raise exception 'the outcome of a destruction item is set once';
    end if;
    return new;
end
$$;

create trigger destruction_item_guard
    before insert or update or delete on dms.destruction_item
    for each row execute function dms.destruction_item_guard();

alter table dms.destruction_proposal enable row level security;
create policy tenant_isolation on dms.destruction_proposal
    using (tenant_id = dms.current_tenant());

alter table dms.destruction_item enable row level security;
create policy tenant_isolation on dms.destruction_item
    using (tenant_id = dms.current_tenant());

grant select, insert on dms.destruction_proposal to dms_app;
grant update (status, decided_by, decided_at, decision_reason) on dms.destruction_proposal to dms_app;
grant select, insert on dms.destruction_item to dms_app;
grant update (outcome, outcome_detail, outcome_at, rendition_sha256, content_sha256, files) on dms.destruction_item
    to dms_app;

-- When the content of a version was removed: on destruction, or when a discarded draft's content was removed after
-- the grace period (data protection, ADR 0050).
alter table dms.document_version
    add column content_removed_at timestamptz,
    add column content_removed_by text,
    add constraint document_version_content_removed_complete
        check ((content_removed_at is null) = (content_removed_by is null)),
    add constraint document_version_destroyed_content_removed
        check (status <> 'Destroyed' or content_removed_at is not null);

grant update (content_removed_at, content_removed_by) on dms.document_version to dms_app;

-- Whether the change from `before_row` to `after_row` is the removal of a version's content: on destruction (Archived to
-- Destroyed, only for a version of an approved destruction proposal) or of a discarded draft's content. The content
-- becomes empty structured content, the values and the deviation report empty, the metadata snapshot keeps a subset
-- of its values; identity, number, approval and rendition reference stay. Happens once.
create function dms.document_version_content_removal(before_row dms.document_version,
                                                     after_row dms.document_version) returns boolean
    language sql stable as
$$
select before_row.content_removed_at is null
   and after_row.content_removed_at is not null
   and after_row.content_removed_by is not null
   and ((before_row.status = 'Archived' and after_row.status = 'Destroyed')
        or (before_row.status = 'Discarded' and after_row.status = 'Discarded'))
   and after_row.content = '{"format": 1, "sections": []}'::jsonb
   and after_row.field_values = '{}'::jsonb
   and after_row.deviation_report = '{"format": 1, "deviations": []}'::jsonb
   and (after_row.working_state, after_row.scope, after_row.change_reason, after_row.class_version,
        after_row.template_version, after_row.released_by, after_row.released_at)
       is not distinct from (before_row.working_state, before_row.scope, before_row.change_reason,
                             before_row.class_version, before_row.template_version, before_row.released_by,
                             before_row.released_at)
   and (after_row.metadata_snapshot is not distinct from before_row.metadata_snapshot
        or ((after_row.metadata_snapshot -> 'takenAt') = (before_row.metadata_snapshot -> 'takenAt')
            and (after_row.metadata_snapshot -> 'values') <@ (before_row.metadata_snapshot -> 'values')))
$$;

-- V31's guard, with the removal of the content on destruction and of discarded drafts. Status transitions are those
-- of V31 (dms.documents.Lifecycle): a version becomes Destroyed only through the removal of its content, and only
-- while it is part of an approved destruction proposal.
create or replace function dms.document_version_guard() returns trigger
    language plpgsql as
$$
declare
    removal boolean;
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
        and not (old.status = 'Draft' and new.status = 'Draft') and not removal then
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
        ('InReview', 'InApproval'), ('InApproval', 'Draft'), ('InApproval', 'Approved'), ('Approved', 'Published'),
        ('Published', 'ReviewDue'), ('ReviewDue', 'Published'), ('Published', 'Superseded'),
        ('ReviewDue', 'Superseded'), ('Published', 'Withdrawn'), ('ReviewDue', 'Withdrawn'),
        ('Superseded', 'Archived'), ('Withdrawn', 'Archived'), ('Archived', 'Destroyed')) then
        raise exception 'document version status cannot change from % to %', old.status, new.status;
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

-- Working states (V9) were insert-only. They are removed when their version is destroyed or a discarded draft's
-- content is removed, and compacted otherwise: never the current state of a version, never a state a vote or a
-- deviation request was given on (the latest state saved before it), never while the document is under legal
-- hold. Compaction deletes in ascending order, so that the reference check sees the later states.
create function dms.document_working_state_guard() returns trigger
    language plpgsql as
$$
declare
    version_status text;
    version_state  integer;
    version_doc    uuid;
begin
    if tg_op = 'UPDATE' then
        raise exception 'dms.document_working_state cannot be changed';
    end if;
    select status, working_state, document_id into version_status, version_state, version_doc
    from dms.document_version where id = old.version_id for share;
    if version_status = 'Destroyed' then
        return old;
    end if;
    if dms.document_under_legal_hold(version_doc) then
        raise exception 'working states of document % under legal hold cannot be removed', version_doc;
    end if;
    if version_status = 'Discarded' then
        return old;
    end if;
    if old.working_state >= version_state then
        raise exception 'the current working state of version % cannot be removed', old.version_id;
    end if;
    if exists (select 1 from dms.workflow_vote w
               where w.version_id = old.version_id and w.voted_at >= old.saved_at
                 and not exists (select 1 from dms.document_working_state s
                                 where s.version_id = old.version_id and s.working_state > old.working_state
                                   and s.saved_at <= w.voted_at))
        or exists (select 1 from dms.document_deviation_request r
                   where r.version_id = old.version_id and r.requested_at >= old.saved_at
                     and not exists (select 1 from dms.document_working_state s
                                     where s.version_id = old.version_id and s.working_state > old.working_state
                                       and s.saved_at <= r.requested_at)) then
        raise exception 'working state % of version % was reviewed and is kept', old.working_state, old.version_id;
    end if;
    return old;
end
$$;

drop trigger document_working_state_guard on dms.document_working_state;
create trigger document_working_state_guard
    before update or delete on dms.document_working_state
    for each row execute function dms.document_working_state_guard();

grant delete on dms.document_working_state to dms_app;

-- V26's guard of attachment links: a link is also removed when its version is destroyed.
create or replace function dms.document_version_attachment_guard() returns trigger
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
    if tg_op = 'DELETE' and version_status = 'Destroyed' then
        return old;
    end if;
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

-- V31's guard of a file document's original: also removed when its version is destroyed. Links of other versions
-- that were taken over from it (based_on) keep pointing at the version row, which stays.
create or replace function dms.document_version_file_guard() returns trigger
    language plpgsql as
$$
declare
    version_status text;
    version_doc    uuid;
    document_kind  text;
begin
    if tg_op = 'DELETE' then
        select status into version_status from dms.document_version where id = old.version_id for share;
        if version_status = 'Destroyed' then
            return old;
        end if;
        if version_status is distinct from 'Draft' then
            raise exception 'the file of document version % is frozen: the version is %', old.version_id,
                coalesce(version_status, 'missing');
        end if;
        return old;
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

-- Runs of the clean-up of orphaned objects (dry runs included), with their report. Insert-only.
create table dms.storage_cleanup_run (
    id           uuid primary key,
    tenant_id    uuid        not null references dms.tenant (id),
    started_at   timestamptz not null,
    finished_at  timestamptz not null,
    dry_run      boolean     not null,
    triggered_by text        not null,
    grace_hours  integer     not null check (grace_hours >= 0),
    -- Counts: scanned, orphaned, deleted, keptLocked, keptHeld, keptRecent, failed, filesDeleted.
    summary      jsonb       not null check (jsonb_typeof(summary) = 'object'),
    -- The orphaned objects and what happened to them (at most a few hundred entries): [{"key", "version", ...}].
    entries      jsonb       not null check (jsonb_typeof(entries) = 'array'),
    check (finished_at >= started_at)
);

create index storage_cleanup_run_recent on dms.storage_cleanup_run (tenant_id, started_at desc);

create trigger storage_cleanup_run_immutable
    before update or delete on dms.storage_cleanup_run
    for each row execute function dms.reject_modification();

alter table dms.storage_cleanup_run enable row level security;
create policy tenant_isolation on dms.storage_cleanup_run
    using (tenant_id = dms.current_tenant());

grant select, insert on dms.storage_cleanup_run to dms_app;
