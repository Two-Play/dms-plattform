-- Rendition robustness and Object Lock retention (DMS-93, ADR 0055).
--
-- 1. dms.object_retention: the Object Lock retention the platform set on stored objects (renditions, originals of
--    file documents and attachment files of approved versions). Object storage holds the authoritative lock; this
--    table is the queryable record of it (one row per object version, the latest retain-until date), so that the
--    destruction path and the orphan clean-up (DMS-82) can tell locked objects apart without asking the store.
-- 2. dms.rendition_provenance: what produced the rendition of a version (renderer and worker versions, the worker
--    image and its build fingerprint, the validator), for reproducibility in the evidence report. Insert-only.
-- 3. When a version is approved, a job 'archive.object-retention' sets the retention on its objects after the
--    commit (the object store is not transactional); the period comes from its retention rule (dms.retention_rule,
--    V33). It runs again when the version goes out of force and when the rule is created or changed (locks are only
--    extended). Approved versions from before this migration are queued once.
-- 4. dms.job_retry: requeues a failed (dead) or backing-off job of the current tenant, for the retry action of
--    failed renditions.

create table dms.object_retention (
    tenant_id      uuid        not null references dms.tenant (id),
    object_key     text        not null check (length(trim(object_key)) > 0),
    object_version text        not null check (length(trim(object_version)) > 0),
    -- S3 Object Lock retention mode as set in object storage.
    mode           text        not null check (mode in ('Governance', 'Compliance')),
    retain_until   timestamptz not null,
    document_id    uuid        not null,
    -- The approved version whose retention rule determined retain_until (the latest one if several versions
    -- share the object, e.g. an attachment taken over unchanged).
    version_id     uuid        not null,
    retention_rule text,
    set_at         timestamptz not null default now(),
    primary key (tenant_id, object_key, object_version),
    foreign key (tenant_id, document_id) references dms.document (tenant_id, id),
    foreign key (tenant_id, version_id) references dms.document_version (tenant_id, id)
);

create index object_retention_document on dms.object_retention (document_id);

-- A retention is only ever extended, never shortened or weakened (compliance never becomes governance).
create function dms.object_retention_guard() returns trigger
    language plpgsql as
$$
begin
    if tg_op = 'DELETE' then
        raise exception 'the retention of object % (%) cannot be deleted', old.object_key, old.object_version;
    end if;
    if new.retain_until < old.retain_until then
        raise exception 'the retention of object % (%) cannot be shortened', old.object_key, old.object_version;
    end if;
    if old.mode = 'Compliance' and new.mode <> 'Compliance' then
        raise exception 'the compliance retention of object % (%) cannot be weakened', old.object_key,
            old.object_version;
    end if;
    return new;
end
$$;

create trigger object_retention_guard
    before update or delete on dms.object_retention
    for each row execute function dms.object_retention_guard();

alter table dms.object_retention enable row level security;
create policy tenant_isolation on dms.object_retention
    using (tenant_id = dms.current_tenant());
grant select, insert on dms.object_retention to dms_app;
grant update (mode, retain_until, version_id, retention_rule, set_at) on dms.object_retention to dms_app;

create table dms.rendition_provenance (
    version_id   uuid primary key,
    tenant_id    uuid        not null,
    -- Versions of renderer or converter and worker as reported by the worker, e.g. "WeasyPrint 66.0; …".
    renderer     text        not null,
    -- Image reference of the worker (name and digest) as configured for the worker; null if not configured.
    worker_image text,
    -- SHA-256 over the worker's installed packages and code, computed by the worker at start; null if unknown.
    worker_build text,
    standard     text        not null,
    validator    text        not null,
    pages        int,
    created_at   timestamptz not null default now(),
    unique (tenant_id, version_id),
    foreign key (tenant_id, version_id) references dms.document_version (tenant_id, id)
);

alter table dms.rendition_provenance enable row level security;
create policy tenant_isolation on dms.rendition_provenance
    using (tenant_id = dms.current_tenant());
grant select, insert on dms.rendition_provenance to dms_app;

-- The approval of a version queues the retention of its objects (archive module, ADR 0055).
create function dms.document_version_object_retention() returns trigger
    language plpgsql as
$$
begin
    if new.released_at is not null and old.released_at is null then
        perform dms.job_enqueue(new.tenant_id, 'archive.object-retention',
                                jsonb_build_object('documentId', new.document_id, 'versionId', new.id),
                                'object-retention:' || new.id, null, 10);
    end if;
    return new;
end
$$;

create trigger document_version_object_retention
    after update of released_at on dms.document_version
    for each row execute function dms.document_version_object_retention();

-- The lock is counted from the trigger of the version's retention rule (dms.retention_rule, V33). When the version goes
-- out of force (the trigger of rules with EndOfValidity), and when a rule is created or changed, the job runs again for
-- the versions concerned; it only ever extends a lock. Keys are unique per occasion.
create function dms.document_version_out_of_force_retention() returns trigger
    language plpgsql as
$$
begin
    if new.released_at is not null and new.status in ('Superseded', 'Withdrawn')
        and old.status is distinct from new.status then
        perform dms.job_enqueue(new.tenant_id, 'archive.object-retention',
                                jsonb_build_object('documentId', new.document_id, 'versionId', new.id),
                                'object-retention:' || new.id || ':' || new.status, null, 10);
    end if;
    return new;
end
$$;

create trigger document_version_out_of_force_retention
    after update of status on dms.document_version
    for each row execute function dms.document_version_out_of_force_retention();

create function dms.retention_rule_object_retention() returns trigger
    language plpgsql as
$$
begin
    if tg_op = 'INSERT' or (new.period_months, new.trigger_event, new.from_year_end)
        is distinct from (old.period_months, old.trigger_event, old.from_year_end) then
        perform dms.job_enqueue(v.tenant_id, 'archive.object-retention',
                                jsonb_build_object('documentId', v.document_id, 'versionId', v.id),
                                'object-retention:' || v.id || ':rule:' || new.key || ':' ||
                                extract(epoch from new.updated_at)::bigint, null, 10)
        from dms.document_version v
        where v.tenant_id = new.tenant_id and v.released_at is not null and v.status <> 'Destroyed'
          and v.metadata_snapshot #>> '{values,retentionRule,value}' = new.key;
    end if;
    return new;
end
$$;

create trigger retention_rule_object_retention
    after insert or update on dms.retention_rule
    for each row execute function dms.retention_rule_object_retention();

-- Approved versions from before this migration.
insert into dms.job (tenant_id, kind, payload, idempotency_key)
select tenant_id, 'archive.object-retention',
       jsonb_build_object('documentId', document_id, 'versionId', id), 'object-retention:' || id
from dms.document_version
where released_at is not null
on conflict (idempotency_key) do nothing;

-- Requeues a job of the current tenant by kind and idempotency key: a dead job starts over with all its attempts,
-- a job waiting for its next attempt runs now. Returns the new status ('queued'), or null if there is no such job
-- in one of these states (done, running, or missing).
create function dms.job_retry(job_kind text, key text) returns text
    language sql security definer
    set search_path = dms, pg_temp as
$$
update dms.job
set status       = 'queued',
    attempts     = case when status = 'dead' then 0 else attempts end,
    run_at       = now(),
    finished_at  = null,
    locked_until = null,
    locked_by    = null
where kind = job_kind
  and idempotency_key = key
  and tenant_id = dms.current_tenant()
  and (status = 'dead' or (status = 'queued' and attempts > 0))
returning status
$$;

revoke all on function dms.job_retry(text, text) from public;
grant execute on function dms.job_retry(text, text) to dms_app;
