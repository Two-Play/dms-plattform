-- Notices of jobs that failed for good (DMS-142, ADR 0058).
--
-- A rendering job ('document.render-rendition') or publication job ('document.publish') that runs out of attempts is
-- dead. A job dies on two paths: dms.job_fail after the last attempt (also after a timeout, which interrupts the
-- handler) and dms.job_claim when the lease of the last attempt expired (the worker crashed, no handler code runs). So
-- the database announces the death: the trigger below writes one outbox event in the transaction that sets the status,
-- for every transition to 'dead'. Single retries write nothing. dms.job_retry brings a job back; if it dies again, that
-- is a new failure and a new event.
--
-- The payload carries ids and a failure code, never the error text: the technical text can quote document content,
-- and outbox payloads stay free of it (ADR 0052). The text stays in dms.job for those who may retry.

-- The stable code of a job error: the code in brackets at its start (ADR 0055), 'timeout' for what the job runner and
-- dms.job_claim write themselves, null for anything else.
create function dms.job_failure_code(error text) returns text
    language sql immutable
    set search_path = dms, pg_temp as
$$
select coalesce(substring(error from '^\[([a-z_]+)\]'),
                case when error like 'timed out%' or error like 'lease expired%' then 'timeout' end)
$$;

-- security definer: dead jobs come from workers without tenant context, so the insert must not depend on it.
create function dms.job_dead_event() returns trigger
    language plpgsql security definer
    set search_path = dms, pg_temp as
$$
declare
    document uuid;
    version  uuid;
begin
    -- Compared as text: a malformed payload must not make the failing job's own update fail.
    select v.document_id, v.id into document, version
    from dms.document_version v
    where v.tenant_id = new.tenant_id and v.id::text = new.payload ->> 'versionId';
    if found then
        insert into dms.outbox_event (tenant_id, aggregate_type, aggregate_id, event_type, payload)
        values (new.tenant_id, 'document', document,
                case new.kind when 'document.publish' then 'document.publication-dead'
                              else 'document.rendition-dead' end,
                jsonb_build_object('documentId', document, 'versionId', version, 'jobId', new.id,
                                   'attempts', new.attempts, 'reason', dms.job_failure_code(new.last_error)));
    end if;
    return new;
end
$$;

revoke all on function dms.job_dead_event() from public;

create trigger job_dead_event
    after update of status on dms.job
    for each row
    when (new.status = 'dead' and old.status <> 'dead'
        and new.kind in ('document.render-rendition', 'document.publish'))
    execute function dms.job_dead_event();

-- Looking up the publication job of a version (its key also holds the due moment, so it is not known up front).
create index job_publication_version on dms.job ((payload ->> 'versionId')) where kind = 'document.publish';

-- Failures from before this migration that still keep a version from taking effect were never announced.
insert into dms.outbox_event (tenant_id, aggregate_type, aggregate_id, event_type, payload)
select j.tenant_id, 'document', v.document_id,
       case j.kind when 'document.publish' then 'document.publication-dead' else 'document.rendition-dead' end,
       jsonb_build_object('documentId', v.document_id, 'versionId', v.id, 'jobId', j.id, 'attempts', j.attempts,
                          'reason', dms.job_failure_code(j.last_error))
from dms.job j
join dms.document_version v on v.tenant_id = j.tenant_id and v.id::text = j.payload ->> 'versionId'
where j.status = 'dead'
  and ((j.kind = 'document.render-rendition' and v.status = 'InApproval' and v.rendition_key is null)
    or (j.kind = 'document.publish' and v.status = 'Approved'
        and j.id = (select max(newest.id) from dms.job newest
                    where newest.kind = j.kind and newest.payload ->> 'versionId' = j.payload ->> 'versionId')));
