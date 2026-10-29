-- Discarding drafts (DMS-129, ADR 0050).
--
-- A draft that was never submitted (e.g. created by accident) can be discarded by its author, the owner or a
-- tenant administrator. The version is not deleted: it moves to the terminal status 'Discarded' and stays as a
-- record, so the audit trail (ADR 0017), the document number (ADR 0040) and the foreign keys of working states,
-- deviation requests and workflow cases keep pointing at a row. Nobody reads a discarded version; a document
-- whose only version is discarded disappears from lists. Its links to attachments and to the original of a file
-- document are removed while it is still a draft; stored files no version references any more are deleted (and
-- their objects removed by a job after the commit) unless the document is under legal hold.

-- The new status.
alter table dms.document_version drop constraint document_version_status_check;
alter table dms.document_version
    add constraint document_version_status_check
        check (status in ('Draft', 'InReview', 'InApproval', 'Approved', 'Published', 'ReviewDue', 'Superseded',
                          'Withdrawn', 'Archived', 'Destroyed', 'Discarded'));

-- Only approved versions carry a snapshot, an approval and a rendition (V9); a discarded draft has none.
alter table dms.document_version drop constraint document_version_check4;
alter table dms.document_version
    add constraint document_version_approval_check
        check (status in ('Draft', 'InReview', 'InApproval', 'Withdrawn', 'Discarded')
            or (metadata_snapshot is not null and released_by is not null and rendition_sha256 is not null));

-- A discarded draft gives its number back like a withdrawn one: the next draft of the document may take it.
drop index dms.document_version_number;
create unique index document_version_number on dms.document_version (document_id, major, minor)
    where (status <> 'Withdrawn' and status <> 'Discarded') or released_at is not null;

-- V18's guard, with the discarding of a draft (Draft -> Discarded) among the allowed status changes. Discarded is
-- terminal; as every status outside Draft, InReview and InApproval it freezes content, values and rendition.
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
        ('Draft', 'InReview'), ('Draft', 'Withdrawn'), ('Draft', 'Discarded'), ('InReview', 'Draft'),
        ('InReview', 'InApproval'), ('InApproval', 'Draft'), ('InApproval', 'Approved'), ('Approved', 'Published'),
        ('Published', 'ReviewDue'), ('ReviewDue', 'Published'), ('Published', 'Superseded'),
        ('ReviewDue', 'Superseded'), ('Published', 'Withdrawn'), ('ReviewDue', 'Withdrawn'),
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

-- The workflow case of a discarded draft (one that was returned and never resubmitted) ends as well.
alter table dms.workflow_case drop constraint workflow_case_status_check;
alter table dms.workflow_case
    add constraint workflow_case_status_check
        check (status in ('Running', 'Returned', 'Rendering', 'Approved', 'Discarded'));

-- The original of a file document (V27) is removed from its version only while the version is a draft, i.e.
-- when the draft is discarded; inserts and updates are checked as before.
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

grant delete on dms.document_version_file to dms_app;

-- Stored files (V26) stay insert-only. A file is deleted only when no version references it any more (neither as
-- attachment nor as original) and the document is not under legal hold: the object is then removed from object
-- storage by a job after the commit. The document row is locked first, so a hold being added concurrently is
-- either seen here or waits until the deletion is committed (like the destruction guard of V19).
create function dms.document_attachment_file_guard() returns trigger
    language plpgsql as
$$
begin
    if tg_op = 'UPDATE' then
        raise exception 'stored files of documents cannot be changed';
    end if;
    perform 1 from dms.document d where d.id = old.document_id for update;
    if dms.document_under_legal_hold(old.document_id) then
        raise exception 'file % belongs to document % under legal hold and cannot be deleted', old.id,
            old.document_id;
    end if;
    if exists (select 1 from dms.document_version_attachment a where a.file_id = old.id)
        or exists (select 1 from dms.document_version_file f where f.file_id = old.id) then
        raise exception 'file % is still referenced by a document version', old.id;
    end if;
    return old;
end
$$;

drop trigger document_attachment_file_immutable on dms.document_attachment_file;
create trigger document_attachment_file_guard
    before update or delete on dms.document_attachment_file
    for each row execute function dms.document_attachment_file_guard();

grant delete on dms.document_attachment_file to dms_app;
