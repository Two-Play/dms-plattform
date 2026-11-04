-- Withdrawing an approved version before it takes effect (DMS-143, ADR 0059).
--
-- 1. The withdrawal is part of the version row: who withdrew it and why, next to the withdrawal time of V18. The status
--    change and the reason are one UPDATE, so that no version is ever half withdrawn; the guard refuses a withdrawal of
--    an approved version without a reason. A version withdrawn while it was valid keeps its reason in its review task
--    (V18) and has neither column set, so that a set `withdrawal_reason` means "approved, but never in force".
-- 2. dms.document_version_guard allows Approved -> Withdrawn (dms.documents.Lifecycle). The rendition, the metadata
--    snapshot and the approval stay frozen: the withdrawal changes none of them. The trigger of V36 queues the Object
--    Lock job of the version as for every version that goes out of force.
-- 3. dms.job gets the status `cancelled`, and dms.job_cancel ends the queued publication jobs of the withdrawn version
--    in the withdrawing transaction. A job already running waits for the row lock and finds the version withdrawn.

alter table dms.document_version
    add column withdrawn_by      text,
    add column withdrawal_reason text,
    add constraint document_version_withdrawal_complete
        check ((withdrawn_by is null) = (withdrawal_reason is null)),
    add constraint document_version_withdrawal_reason
        check (withdrawal_reason is null or length(trim(withdrawal_reason)) between 1 and 4000);

grant update (withdrawn_by, withdrawal_reason) on dms.document_version to dms_app;

-- V18's guard of the review date and the withdrawal time, with the withdrawal of an approved version: it needs its
-- reason, and nothing else records one.
create or replace function dms.document_version_review_guard() returns trigger
    language plpgsql as
$$
begin
    if tg_op = 'INSERT' then
        if new.review_due_on is not null or new.withdrawn_at is not null or new.withdrawn_by is not null then
            raise exception 'a new document version has no review date and is not withdrawn';
        end if;
        return new;
    end if;
    if new.review_due_on is distinct from old.review_due_on and new.status not in ('Published', 'ReviewDue') then
        raise exception 'the review date of document version %.% of % changes only while it is valid', old.major,
            old.minor, old.document_id;
    end if;
    if old.status = 'Approved' and new.status = 'Withdrawn' then
        if new.withdrawal_reason is null then
            raise exception 'an approved document version is withdrawn only with a reason';
        end if;
        new.withdrawn_at := new.status_changed_at;
    else
        if (new.withdrawn_by, new.withdrawal_reason) is distinct from (old.withdrawn_by, old.withdrawal_reason) then
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

-- V33's guard, with the withdrawal of an approved version among the allowed status changes.
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
        ('Approved', 'Withdrawn'),
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

-- A job that will never run: its reason is why it was cancelled.
alter table dms.job drop constraint job_status_check;
alter table dms.job
    add constraint job_status_check check (status in ('queued', 'running', 'done', 'dead', 'cancelled'));

-- Cancels the queued jobs of the current tenant of a kind whose idempotency key starts with `key_prefix`. Returns how
-- many. A running job is left alone: its handler decides under the lock of what it works on.
create function dms.job_cancel(job_kind text, key_prefix text, reason text) returns int
    language sql security definer
    set search_path = dms, pg_temp as
$$
with cancelled as (
    update dms.job
    set status       = 'cancelled',
        finished_at  = now(),
        locked_until = null,
        locked_by    = null,
        last_error   = reason
    where kind = job_kind
      and starts_with(idempotency_key, key_prefix)
      and tenant_id = dms.current_tenant()
      and status = 'queued'
    returning 1
)
select count(*)::int from cancelled
$$;

revoke all on function dms.job_cancel(text, text, text) from public;
grant execute on function dms.job_cancel(text, text, text) to dms_app;
