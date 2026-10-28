-- Spike schema for DMS-21 (database access library). Applied as superuser `dms`
-- to the throwaway database `spike_db_access`.

create schema spike;

-- 1) Tenant-scoped table protected by row-level security.
create table spike.document (
    id        uuid primary key default gen_random_uuid(),
    tenant_id uuid not null,
    title     text not null,
    metadata  jsonb not null default '{}'
);
alter table spike.document enable row level security;
-- Without WITH CHECK, USING also guards INSERT/UPDATE.
create policy tenant_isolation on spike.document
    using (tenant_id = current_setting('app.tenant_id')::uuid);

-- 2) Job queue for FOR UPDATE SKIP LOCKED.
create table spike.job (
    id         bigint generated always as identity primary key,
    batch      text not null,
    status     text not null default 'new',
    claimed_by text,
    payload    jsonb not null default '{}'
);

-- 4) Append-only audit table (same trigger pattern as V1__grundschema.sql).
create table spike.audit_event (
    seq         bigint generated always as identity primary key,
    tenant_id   uuid not null,
    action      text not null,
    details     jsonb not null default '{}',
    occurred_at timestamptz not null default now()
);
create function spike.audit_event_immutable() returns trigger
    language plpgsql as
$$
begin
    raise exception 'spike.audit_event is append-only: % is not allowed', tg_op;
end
$$;
create trigger audit_event_guard
    before update or delete on spike.audit_event
    for each row execute function spike.audit_event_immutable();

-- 5) Stand-in for a Flowable engine table (ACT_RU_*), written by "another component".
create table spike.engine_record (
    id   bigint generated always as identity primary key,
    note text not null
);

-- Constraint checked at COMMIT time, to see how a failing commit surfaces.
create table spike.deferred_check (
    k int not null,
    constraint deferred_check_k_unique unique (k) deferrable initially deferred
);

-- Non-superuser application role, so RLS applies.
grant usage on schema spike to spike_db_access_app;
grant select, insert, update, delete on spike.document, spike.job, spike.engine_record, spike.deferred_check to spike_db_access_app;
-- UPDATE is granted on purpose so the trigger (not missing privileges) rejects it;
-- DELETE is not granted to show the privilege-based variant.
grant select, insert, update on spike.audit_event to spike_db_access_app;
