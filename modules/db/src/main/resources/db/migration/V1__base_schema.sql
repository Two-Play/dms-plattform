-- Base schema: tenants, outbox, audit trail.
-- Row-level security follows with T-00.9, hash computation with S-12.a.

create schema if not exists dms;

create table dms.tenant (
    id             uuid primary key,
    parent_id      uuid references dms.tenant (id),
    name           text not null,
    zitadel_org_id text unique,
    created_at     timestamptz not null default now()
);

create table dms.outbox_event (
    id             bigint generated always as identity primary key,
    tenant_id      uuid not null references dms.tenant (id),
    aggregate_type text not null,
    aggregate_id   uuid not null,
    event_type     text not null,
    payload        jsonb not null,
    correlation_id uuid,
    created_at     timestamptz not null default now(),
    published_at   timestamptz
);

create index outbox_event_unpublished on dms.outbox_event (id) where published_at is null;

create table dms.audit_event (
    seq            bigint generated always as identity primary key,
    tenant_id      uuid not null references dms.tenant (id),
    occurred_at    timestamptz not null,
    actor_id       text not null,
    actor_kind     text not null check (actor_kind in ('person', 'technical')),
    on_behalf_of   text,
    action         text not null,
    object_type    text not null,
    object_id      uuid,
    object_version text,
    details        jsonb not null default '{}',
    reason         text,
    channel        text not null check (channel in ('ui', 'api', 'automation')),
    correlation_id uuid,
    prev_hash      bytea,
    hash           bytea not null
);

create function dms.audit_event_immutable() returns trigger
    language plpgsql as
$$
begin
    raise exception 'dms.audit_event is append-only: % is not allowed', tg_op;
end
$$;

create trigger audit_event_guard
    before update or delete on dms.audit_event
    for each row execute function dms.audit_event_immutable();
