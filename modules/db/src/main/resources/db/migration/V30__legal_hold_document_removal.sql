-- Removing a single document from a legal hold (DMS-106, ADR 0047, follow-up of ADR 0031).
--
-- A document wrongly added to a hold can be taken out of it with a reason while the hold is active. The row
-- stays as history: it gets the removal time, who removed it and why, and no longer counts as covering the
-- document. The document can be added to the same hold again later; that is a new row. Rows are still never
-- deleted, and a released hold does not change any more.

alter table dms.legal_hold_document
    add column removed_at     timestamptz,
    add column removed_by     text,
    add column removal_reason text check (removal_reason is null or length(trim(removal_reason)) > 0),
    add column entry_id       bigint generated always as identity;

alter table dms.legal_hold_document
    add constraint legal_hold_document_removal_complete
        check ((removed_at is null) = (removed_by is null) and (removed_at is null) = (removal_reason is null)),
    add constraint legal_hold_document_removal_after_add check (removed_at is null or removed_at >= added_at);

-- A document is covered by a hold at most once at a time; removed rows are history.
alter table dms.legal_hold_document drop constraint legal_hold_document_pkey;
alter table dms.legal_hold_document add primary key (entry_id);
create unique index legal_hold_document_current
    on dms.legal_hold_document (hold_id, document_id) where removed_at is null;

-- Rows change only by their removal, once, while the hold is active; they are never deleted. The share lock
-- waits for a concurrent release of the hold.
drop trigger legal_hold_document_immutable on dms.legal_hold_document;

create function dms.legal_hold_document_change_guard() returns trigger
    language plpgsql as
$$
begin
    if tg_op = 'DELETE' then
        raise exception 'dms.legal_hold_document keeps its history: DELETE is not allowed';
    end if;
    if old.removed_at is not null or new.removed_at is null
        or (new.entry_id, new.tenant_id, new.hold_id, new.document_id, new.added_via, new.reason, new.added_by,
            new.added_at)
        is distinct from (old.entry_id, old.tenant_id, old.hold_id, old.document_id, old.added_via, old.reason,
                          old.added_by, old.added_at) then
        raise exception 'a document of a legal hold cannot change; it can only be removed once';
    end if;
    if not exists (select 1 from dms.legal_hold h
                   where h.id = new.hold_id and h.released_at is null
                   for share) then
        raise exception 'legal hold % is released; its documents cannot be removed', new.hold_id;
    end if;
    return new;
end
$$;

create trigger legal_hold_document_change_guard
    before update or delete on dms.legal_hold_document
    for each row execute function dms.legal_hold_document_change_guard();

grant update (removed_at, removed_by, removal_reason) on dms.legal_hold_document to dms_app;

-- Whether an active hold covers the document: removed rows do not count.
create or replace function dms.document_under_legal_hold(document uuid) returns boolean
    language sql stable as
$$
select exists (select 1
               from dms.legal_hold_document m
               join dms.legal_hold h on h.id = m.hold_id
               where m.document_id = document
                 and m.removed_at is null
                 and h.released_at is null)
$$;
