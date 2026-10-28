-- Job queue and outbox delivery in PostgreSQL (ADR 0010).
--
-- Workers claim work with a lease instead of holding a transaction open: claiming sets locked_until and commits,
-- the handler runs in its own transactions, then the worker completes or fails the item. A crashed worker's
-- lease expires and the item is claimed again (at-least-once delivery; handlers must be idempotent).
-- Workers run without tenant context, so claiming and completing go through security definer functions.

-- Tenant ids for system jobs that fan out per tenant.
create function dms.tenant_ids() returns setof uuid
    language sql stable security definer
    set search_path = dms, pg_temp as
$$
select id from dms.tenant order by id
$$;

-- ---------------------------------------------------------------------------------------------------------
-- Jobs
-- ---------------------------------------------------------------------------------------------------------

create table dms.job (
    id              bigint generated always as identity primary key,
    tenant_id       uuid references dms.tenant (id),           -- null: system job
    kind            text not null,
    payload         jsonb not null default '{}',
    idempotency_key text unique,
    run_at          timestamptz not null default now(),
    status          text not null default 'queued' check (status in ('queued', 'running', 'done', 'dead')),
    attempts        int not null default 0,
    max_attempts    int not null default 10 check (max_attempts > 0),
    locked_until    timestamptz,
    locked_by       text,
    last_error      text,
    created_at      timestamptz not null default now(),
    finished_at     timestamptz
);

create index job_due on dms.job (run_at, id) where status in ('queued', 'running');

alter table dms.job enable row level security;
create policy tenant_isolation on dms.job
    using (tenant_id = dms.current_tenant());
grant select on dms.job to dms_app;

-- Enqueues a job. A tenant job must belong to the current tenant; tenant = null enqueues a system job.
-- Returns null if a job with the same idempotency key exists.
create function dms.job_enqueue(tenant uuid, job_kind text, job_payload jsonb, key text, run_at_time timestamptz,
                                attempts_max int)
    returns bigint
    language plpgsql security definer
    set search_path = dms, pg_temp as
$$
declare
    new_id bigint;
begin
    if tenant is not null then
        if tenant <> dms.current_tenant() then
            raise exception 'jobs can only be enqueued for the current tenant' using errcode = '42501';
        end if;
    end if;
    insert into dms.job (tenant_id, kind, payload, idempotency_key, run_at, max_attempts)
    values (tenant, job_kind, job_payload, key, coalesce(run_at_time, now()), attempts_max)
    on conflict (idempotency_key) do nothing
    returning id into new_id;
    return new_id;
end
$$;

-- Claims up to max_jobs due jobs of the given kinds for worker, so a worker never takes jobs it cannot handle
-- (e.g. an older instance during a rolling update). Expired leases of jobs out of attempts become dead first.
create function dms.job_claim(worker text, max_jobs int, lease interval, kinds text[]) returns setof dms.job
    language plpgsql security definer
    set search_path = dms, pg_temp as
$$
begin
    update dms.job set status = 'dead', finished_at = now(), locked_until = null,
                       last_error = coalesce(last_error, 'lease expired after the last attempt')
    where status = 'running' and locked_until < now() and attempts >= max_attempts;

    return query
        update dms.job j
        set status = 'running', attempts = j.attempts + 1, locked_until = now() + lease, locked_by = worker
        where j.id in (
            select id from dms.job
            where run_at <= now()
              and kind = any (kinds)
              and (status = 'queued' or (status = 'running' and locked_until < now()))
            order by run_at, id
            limit max_jobs
            for update skip locked
        )
        returning j.*;
end
$$;

create function dms.job_complete(job_id bigint, worker text) returns boolean
    language sql security definer
    set search_path = dms, pg_temp as
$$
update dms.job set status = 'done', finished_at = now(), locked_until = null, last_error = null
where id = job_id and status = 'running' and locked_by = worker
returning true
$$;

-- Records a failure; the job is retried after retry_after or becomes dead when out of attempts.
create function dms.job_fail(job_id bigint, worker text, error text, retry_after interval) returns text
    language sql security definer
    set search_path = dms, pg_temp as
$$
update dms.job
set status       = case when attempts >= max_attempts then 'dead' else 'queued' end,
    run_at       = now() + retry_after,
    finished_at  = case when attempts >= max_attempts then now() end,
    locked_until = null,
    last_error   = error
where id = job_id and status = 'running' and locked_by = worker
returning status
$$;

-- ---------------------------------------------------------------------------------------------------------
-- Outbox delivery (table from V1, RLS from V2)
-- ---------------------------------------------------------------------------------------------------------

alter table dms.outbox_event
    add column attempts        int not null default 0,
    add column next_attempt_at timestamptz not null default now(),
    add column locked_until    timestamptz,
    add column locked_by       text,
    add column last_error      text;

drop index dms.outbox_event_unpublished;
create index outbox_event_due on dms.outbox_event (next_attempt_at, id) where published_at is null;

-- Claims up to max_events undelivered events in creation order.
create function dms.outbox_claim(worker text, max_events int, lease interval) returns setof dms.outbox_event
    language sql security definer
    set search_path = dms, pg_temp as
$$
update dms.outbox_event e
set attempts = e.attempts + 1, locked_until = now() + lease, locked_by = worker
where e.id in (
    select id from dms.outbox_event
    where published_at is null and next_attempt_at <= now() and (locked_until is null or locked_until < now())
    order by id
    limit max_events
    for update skip locked
)
returning e.*
$$;

create function dms.outbox_complete(event_id bigint, worker text) returns boolean
    language sql security definer
    set search_path = dms, pg_temp as
$$
update dms.outbox_event set published_at = now(), locked_until = null, last_error = null
where id = event_id and published_at is null and locked_by = worker
returning true
$$;

create function dms.outbox_fail(event_id bigint, worker text, error text, retry_after interval) returns boolean
    language sql security definer
    set search_path = dms, pg_temp as
$$
update dms.outbox_event set next_attempt_at = now() + retry_after, locked_until = null, last_error = error
where id = event_id and published_at is null and locked_by = worker
returning true
$$;

revoke all on function dms.tenant_ids(), dms.job_enqueue(uuid, text, jsonb, text, timestamptz, int),
    dms.job_claim(text, int, interval, text[]), dms.job_complete(bigint, text), dms.job_fail(bigint, text, text, interval),
    dms.outbox_claim(text, int, interval), dms.outbox_complete(bigint, text),
    dms.outbox_fail(bigint, text, text, interval) from public;
grant execute on function dms.tenant_ids(), dms.job_enqueue(uuid, text, jsonb, text, timestamptz, int),
    dms.job_claim(text, int, interval, text[]), dms.job_complete(bigint, text), dms.job_fail(bigint, text, text, interval),
    dms.outbox_claim(text, int, interval), dms.outbox_complete(bigint, text),
    dms.outbox_fail(bigint, text, text, interval) to dms_app;
