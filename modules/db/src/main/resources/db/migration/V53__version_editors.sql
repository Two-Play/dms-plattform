-- The four-eyes rule covers everyone who changed a version, not only the person who started it (DMS-159, ADR 0075).
--
-- dms.document_working_state cannot answer who changed a version: compaction removes states of open versions too
-- (V33), and change summaries, deviation requests and attachments are no working states at all. So every person who
-- changes a version is recorded here once, kept for as long as the version row and never compacted.
create table dms.document_version_editor (
    tenant_id        uuid        not null,
    version_id       uuid        not null,
    user_id          text        not null,
    first_changed_at timestamptz not null default now(),
    primary key (version_id, user_id),
    foreign key (tenant_id, version_id) references dms.document_version (tenant_id, id)
);

-- Whoever changed a version before this migration, from everything that names them. The audit trail covers the
-- working states compaction removed and the changes that left no other trace. Requests, attachments and files taken
-- over from the previous version (based_on) were not changed in this version.
insert into dms.document_version_editor (tenant_id, version_id, user_id, first_changed_at)
select tenant_id, version_id, user_id, min(changed_at)
from (
    select tenant_id, id as version_id, created_by as user_id, created_at as changed_at
    from dms.document_version
    union all
    select tenant_id, version_id, saved_by, saved_at
    from dms.document_working_state
    union all
    select tenant_id, version_id, requested_by, requested_at
    from dms.document_deviation_request
    where based_on is null
    union all
    select tenant_id, version_id, withdrawn_by, withdrawn_at
    from dms.document_deviation_request
    where withdrawn_by is not null
    union all
    select tenant_id, version_id, added_by, added_at
    from dms.document_version_attachment
    where based_on is null
    union all
    select tenant_id, version_id, set_by, set_at
    from dms.document_version_file
    where based_on is null
    union all
    select v.tenant_id, v.id, e.actor_id, e.occurred_at
    from dms.audit_event e
    join dms.document_version v on v.tenant_id = e.tenant_id and v.id::text = e.details ->> 'versionId'
    where e.object_type = 'document'
      and (e.action in ('document.draft-changed', 'document.change-reason-changed', 'document.file-uploaded',
                        'document.word-values-changed', 'document.deviation-requested',
                        'document.deviation-withdrawn', 'document.attachment-added',
                        'document.attachment-renamed', 'document.attachment-removed')
          -- On a version without a draft the number changes the document, not the version.
          or (e.action = 'document.number-assigned' and e.object_version like '%-draft.%'))
) changes
group by tenant_id, version_id, user_id;

create trigger document_version_editor_guard
    before update or delete on dms.document_version_editor
    for each row execute function dms.reject_modification();

alter table dms.document_version_editor enable row level security;
create policy tenant_isolation on dms.document_version_editor
    using (tenant_id = dms.current_tenant());

grant select, insert on dms.document_version_editor to dms_app;

-- Segregation of duties (business concept 2.4), next to the checks of V9 and V14 against the author: nobody who
-- changed a version approves it, for every writer. Like those checks, whatever the class's four-eyes setting says.
create function dms.document_version_four_eyes() returns trigger
    language plpgsql as
$$
begin
    if exists (select 1 from dms.document_version_editor e
               where e.version_id = new.id and e.user_id = new.released_by) then
        raise exception 'segregation of duties: % changed version % and cannot approve it', new.released_by, new.id;
    end if;
    return new;
end
$$;

create trigger document_version_four_eyes
    before update of released_by on dms.document_version
    for each row when (new.released_by is not null and new.released_by is distinct from old.released_by)
    execute function dms.document_version_four_eyes();

create function dms.workflow_approval_four_eyes() returns trigger
    language plpgsql as
$$
begin
    if exists (select 1 from dms.document_version_editor e
               where e.version_id = new.version_id and e.user_id in (new.approved_by, new.acting_for)) then
        raise exception 'segregation of duties: someone who changed version % cannot approve it', new.version_id;
    end if;
    return new;
end
$$;

create trigger workflow_approval_four_eyes
    before insert on dms.workflow_approval
    for each row execute function dms.workflow_approval_four_eyes();
