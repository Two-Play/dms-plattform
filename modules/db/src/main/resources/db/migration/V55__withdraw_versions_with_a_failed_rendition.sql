-- A way out for a version in approval whose archive rendition failed for good (DMS-163, ADR 0078).
--
-- The approval finishes in the rendering job. If that job dies the version stays InApproval and its case Rendering, and
-- neither a vote nor a withdrawal reaches it: the tasks are done, and only an approved version could be withdrawn. The
-- first case that needs this is a last approver who changed the version (ADR 0075): no retry can ever help them.
--
-- 1. dms.document_version_guard allows InApproval -> Withdrawn, and only with a dead rendering job: every other version
--    in approval is finished by its votes. Only dms.job_retry revives a job, under the row lock of the version.
-- 2. dms.document_version_review_guard records the reason as for an approved version. `withdrawal_reason` still means
--    "never in force" (V41).
-- 3. The workflow case ends in the new status 'Withdrawn', next to 'Discarded': neither is waiting for anything.

alter table dms.workflow_case drop constraint workflow_case_status_check;
alter table dms.workflow_case
    add constraint workflow_case_status_check
        check (status in ('Running', 'Returned', 'Rendering', 'Approved', 'Discarded', 'Withdrawn'));

-- V47's guard, with a version in approval next to an approved one.
create or replace function dms.document_version_review_guard() returns trigger
    language plpgsql as
$$
begin
    if tg_op = 'INSERT' then
        if new.review_due_on is not null or new.withdrawn_at is not null or new.withdrawn_by is not null
            or new.withdrawn_acting_for is not null then
            raise exception 'a new document version has no review date and is not withdrawn';
        end if;
        return new;
    end if;
    if new.review_due_on is distinct from old.review_due_on and new.status not in ('Published', 'ReviewDue') then
        raise exception 'the review date of document version %.% of % changes only while it is valid', old.major,
            old.minor, old.document_id;
    end if;
    if (old.status = 'Approved' or old.status = 'InApproval') and new.status = 'Withdrawn' then
        if new.withdrawal_reason is null then
            raise exception 'an approved document version is withdrawn only with a reason';
        end if;
        new.withdrawn_at := new.status_changed_at;
    else
        if (new.withdrawn_by, new.withdrawal_reason, new.withdrawn_acting_for)
            is distinct from (old.withdrawn_by, old.withdrawal_reason, old.withdrawn_acting_for) then
            raise exception 'the reason of a withdrawal is recorded only when an approved version is withdrawn';
        end if;
        if old.status in ('Published', 'ReviewDue') and new.status = 'Withdrawn' then
            new.withdrawn_at := new.status_changed_at;
        elsif new.withdrawn_at is distinct from old.withdrawn_at then
            raise exception 'the withdrawal time of a document version is recorded only on withdrawal';
        end if;
    end if;
    return new;
end
$$;

-- V41's guard, with the withdrawal of a version in approval among the allowed status changes.
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
