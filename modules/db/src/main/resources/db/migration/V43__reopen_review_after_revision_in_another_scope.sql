-- A review task also opens again when its revision took effect in another validity scope (DMS-148, ADR 0063).
--
-- Publication only replaces the valid version of the same scope. A revision moved to another scope leaves the reviewed
-- version valid while its task stays decided, so the periodic review of that version would end. V42's guard accepted a
-- reopening row only for a revision that was never in force; it now also accepts one for a revision that was, as long
-- as the reviewed version is still valid. Everything else in the guard stays as in V42.
--
-- RLS and grants: no new table or column.

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
            join dms.document_version reviewed on reviewed.id = decided.version_id
            where decided.id = new.reopens_id
              and decided.status = 'Revised'
              and decided.version_id = new.version_id
              and decided.due_on = new.due_on
              and reviewed.status in ('Published', 'ReviewDue')
              and (revision.published_at is not null or revision.status in ('Withdrawn', 'Discarded'))) then
            raise exception 'a review task reopens only a task of the same version and date whose revision did not replace it';
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
