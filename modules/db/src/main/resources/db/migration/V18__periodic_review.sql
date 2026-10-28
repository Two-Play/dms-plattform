-- Periodic review and recertification of valid versions (business concept 5.1, 6.2; US-07.1; ADR 0030).
--
-- A valid version has a review date. When the class's lead time before that date is reached, the document owner
-- gets a review task and decides: recertify (new review date, same version), revise (new draft) or withdraw (the
-- version goes out of force). On the review date a version still not recertified becomes ReviewDue; it stays
-- valid. The review date is not part of the frozen version package: it is set when the version becomes valid and
-- changed only by recertification, which never creates a new version.
--
-- This migration adds columns, a table and separate triggers. It replaces the V10 lifecycle guard function only
-- to add the withdrawal of a valid version; later migrations that replace it must start from this version.

-- Review settings of a class version: the review interval (none: no periodic review unless the document sets
-- a date in its nextReview field) and the lead time of the review task before the review date.
alter table dms.document_class_version
    add column review_interval_months integer check (review_interval_months between 1 and 120),
    add column review_lead_days       integer not null default 60 check (review_lead_days between 0 and 365);

-- The review date of a valid version, and when a valid version was withdrawn (end of its validity).
alter table dms.document_version
    add column review_due_on date,
    add column withdrawn_at  timestamptz;

-- Existing valid versions: the nextReview date of their metadata snapshot, if any (no class had an interval).
update dms.document_version
set review_due_on = (metadata_snapshot #>> '{values,nextReview,value}')::date
where status in ('Published', 'ReviewDue')
  and metadata_snapshot #>> '{values,nextReview,type}' = 'Date';

-- A withdrawn released version keeps its number; only withdrawn drafts give it back (V9).
drop index dms.document_version_number;
create unique index document_version_number on dms.document_version (document_id, major, minor)
    where status <> 'Withdrawn' or released_at is not null;

-- The review date is set when the version becomes valid and changes only while it is valid (recertification);
-- the withdrawal time is recorded by the database when a valid version is withdrawn.
create function dms.document_version_review_guard() returns trigger
    language plpgsql as
$$
begin
    if tg_op = 'INSERT' then
        if new.review_due_on is not null or new.withdrawn_at is not null then
            raise exception 'a new document version has no review date and is not withdrawn';
        end if;
        return new;
    end if;
    if new.review_due_on is distinct from old.review_due_on and new.status not in ('Published', 'ReviewDue') then
        raise exception 'the review date of document version %.% of % changes only while it is valid', old.major,
            old.minor, old.document_id;
    end if;
    if old.status in ('Published', 'ReviewDue') and new.status = 'Withdrawn' then
        new.withdrawn_at := new.status_changed_at;
    elsif new.withdrawn_at is distinct from old.withdrawn_at then
        raise exception 'the withdrawal time of a document version is recorded only on withdrawal';
    end if;
    return new;
end
$$;

create trigger document_version_review_guard
    before insert or update on dms.document_version
    for each row execute function dms.document_version_review_guard();

grant update (review_due_on) on dms.document_version to dms_app;

-- V10's guard, with the withdrawal of a valid version (Published or ReviewDue -> Withdrawn) among the allowed
-- status changes.
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
        ('Published', 'Withdrawn'), ('ReviewDue', 'Withdrawn'),
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

-- Review tasks: one per valid version and review date, created when the lead time is reached. The owner's
-- decision is recorded once: Recertified (with the next review date), Revised (with the new draft) or Withdrawn
-- (with the reason). Obsolete: the version stopped being valid without a decision (e.g. superseded).
create table dms.document_review (
    id             uuid primary key,
    tenant_id      uuid        not null,
    document_id    uuid        not null,
    version_id     uuid        not null,
    due_on         date        not null,
    created_at     timestamptz not null default now(),
    status         text        not null default 'Open'
        check (status in ('Open', 'Recertified', 'Revised', 'Withdrawn', 'Obsolete')),
    decided_at     timestamptz,
    decided_by     text,
    next_due_on    date,
    new_version_id uuid,
    comment        text check (length(comment) <= 4000),
    unique (tenant_id, id),
    unique (version_id, due_on),
    foreign key (tenant_id, document_id) references dms.document (tenant_id, id),
    foreign key (tenant_id, version_id) references dms.document_version (tenant_id, id),
    foreign key (tenant_id, new_version_id) references dms.document_version (tenant_id, id),
    check ((status = 'Open') = (decided_at is null)),
    check (status in ('Open', 'Obsolete') or decided_by is not null),
    check ((status = 'Recertified') = (next_due_on is not null)),
    check ((status = 'Revised') = (new_version_id is not null)),
    check (status <> 'Withdrawn' or length(trim(coalesce(comment, ''))) > 0)
);

-- At most one open review task per version.
create unique index document_review_one_open on dms.document_review (version_id) where status = 'Open';
create index document_review_open on dms.document_review (tenant_id, status, due_on);

-- An open task is decided once; its identity and the decision never change afterwards.
create function dms.document_review_guard() returns trigger
    language plpgsql as
$$
begin
    if tg_op = 'DELETE' then
        raise exception 'dms.document_review is append-only: DELETE is not allowed';
    end if;
    if tg_op = 'INSERT' then
        if new.status <> 'Open' then
            raise exception 'a review task starts open';
        end if;
        return new;
    end if;
    if old.status <> 'Open' then
        raise exception 'review task % is already %', old.id, old.status;
    end if;
    if (new.id, new.tenant_id, new.document_id, new.version_id, new.due_on, new.created_at)
        is distinct from (old.id, old.tenant_id, old.document_id, old.version_id, old.due_on, old.created_at) then
        raise exception 'the identity of a review task cannot change';
    end if;
    return new;
end
$$;

create trigger document_review_guard
    before insert or update or delete on dms.document_review
    for each row execute function dms.document_review_guard();

alter table dms.document_review enable row level security;
create policy tenant_isolation on dms.document_review
    using (tenant_id = dms.current_tenant())
    with check (tenant_id = dms.current_tenant());

grant select, insert on dms.document_review to dms_app;
grant update (status, decided_at, decided_by, next_due_on, new_version_id, comment) on dms.document_review to dms_app;
