-- Who a deputy stood in for when withdrawing an approved version (DMS-145, ADR 0070).
--
-- The withdrawal is part of the version row (V41): the version history and the evidence report show it to people who
-- may not read the audit trail. A deputy whose right rests on a vote cast for someone else names that person here, as
-- votes and approvals do with `acting_for` (V14). Empty for everyone who withdrew on their own right.

alter table dms.document_version
    add column withdrawn_acting_for text,
    add constraint document_version_withdrawal_acting_for
        check (withdrawn_acting_for is null or
               (withdrawn_by is not null and withdrawn_acting_for <> withdrawn_by));

grant update (withdrawn_acting_for) on dms.document_version to dms_app;

-- V41's guard: the new column is recorded with the withdrawal and never changes afterwards.
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
    if old.status = 'Approved' and new.status = 'Withdrawn' then
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
