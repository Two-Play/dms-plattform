-- A review task whose revision never took effect opens again (DMS-145, ADR 0061).
--
-- A task decided as "revise" points to the new version. If that version is withdrawn before it took effect or discarded
-- as a draft, the valid version needs a decision again, for the same review date. The first decision stays as it is:
-- the guard keeps decided rows, and the row says what the owner decided and when. So a new row opens the task again and
-- names the decided one in `reopens_id`.
--
-- One row per version and review date (V18) becomes one first row per version and review date, plus at most one row
-- that reopens each decided task. The guard accepts such a row only for a task decided as revised, of the same version
-- and date, whose revision was never in force. V18's index of at most one open task per version stays.
--
-- RLS and grants: the policy of V18 covers the new column, and so do the table-wide select and insert of dms_app. The
-- column grant for update of V18 leaves it out on purpose: the link is written once.

alter table dms.document_review
    add column reopens_id uuid,
    add constraint document_review_reopens
        foreign key (tenant_id, reopens_id) references dms.document_review (tenant_id, id),
    add constraint document_review_reopened_once unique (reopens_id),
    drop constraint document_review_version_id_due_on_key;

create unique index document_review_first_of_date on dms.document_review (version_id, due_on)
    where reopens_id is null;

-- V18's guard, with the rule for a row that reopens a task. Decided rows stay as they are.
create or replace function dms.document_review_guard() returns trigger
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
        if new.reopens_id is not null and not exists (
            select 1
            from dms.document_review decided
            join dms.document_version revision on revision.id = decided.new_version_id
            where decided.id = new.reopens_id
              and decided.status = 'Revised'
              and decided.version_id = new.version_id
              and decided.due_on = new.due_on
              and revision.status in ('Withdrawn', 'Discarded')
              and revision.published_at is null) then
            raise exception 'a review task reopens only a task of the same version and date whose revision never took effect';
        end if;
        return new;
    end if;
    if old.status <> 'Open' then
        raise exception 'review task % is already %', old.id, old.status;
    end if;
    if (new.id, new.tenant_id, new.document_id, new.version_id, new.due_on, new.created_at, new.reopens_id)
        is distinct from (old.id, old.tenant_id, old.document_id, old.version_id, old.due_on, old.created_at,
                          old.reopens_id) then
        raise exception 'the identity of a review task cannot change';
    end if;
    return new;
end
$$;
