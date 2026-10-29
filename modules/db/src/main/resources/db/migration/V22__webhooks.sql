-- Webhooks for connected systems (US-14.1; DMS-58, ADR 0035).
--
-- Tenant administrators subscribe systems (e.g. the intranet) to document events. The outbox relay turns each
-- relevant outbox event into one delivery per matching subscription, together with a job that sends it (ADR 0010):
-- the relay only writes rows, so a receiver that is down never blocks or loses events. The delivery row is the
-- delivery log: the exact body sent, the attempts, the last answer and whether it was delivered or given up (dead).

create table dms.webhook_subscription (
    id               uuid primary key default gen_random_uuid(),
    tenant_id        uuid        not null references dms.tenant (id),
    url              text        not null check (length(url) between 1 and 2000),
    -- Signing secret (Standard Webhooks format whsec_<base64>). Shown once on creation and rotation.
    secret           text        not null check (secret like 'whsec\_%'),
    -- Subscribed event types, e.g. {document.published, document.withdrawn}.
    event_types      text[]      not null check (cardinality(event_types) > 0),
    -- Class keys; a document matches if its class or an ancestor is listed. Empty: all classes.
    document_classes text[]      not null default '{}',
    description      text check (description is null or length(description) <= 500),
    active           boolean     not null default true,
    created_by       text        not null,
    created_at       timestamptz not null default now(),
    updated_at       timestamptz not null default now(),
    -- Deleted subscriptions are kept for their delivery log.
    deleted_at       timestamptz
);

create index webhook_subscription_tenant on dms.webhook_subscription (tenant_id) where deleted_at is null;

create table dms.webhook_delivery (
    id               uuid primary key,
    tenant_id        uuid        not null references dms.tenant (id),
    subscription_id  uuid        not null references dms.webhook_subscription (id),
    -- The outbox event delivered; one delivery per subscription and event.
    event_id         bigint      not null,
    event_type       text        not null,
    document_id      uuid,
    -- The exact JSON body sent on every attempt.
    payload          jsonb       not null,
    status           text        not null default 'pending'
        check (status in ('pending', 'retrying', 'delivered', 'dead', 'skipped')),
    attempts         int         not null default 0,
    last_status_code int,
    last_error       text,
    created_at       timestamptz not null default now(),
    last_attempt_at  timestamptz,
    delivered_at     timestamptz,
    unique (subscription_id, event_id)
);

create index webhook_delivery_subscription on dms.webhook_delivery (subscription_id, created_at desc);

alter table dms.webhook_subscription enable row level security;
create policy tenant_isolation on dms.webhook_subscription
    using (tenant_id = dms.current_tenant())
    with check (tenant_id = dms.current_tenant());

alter table dms.webhook_delivery enable row level security;
create policy tenant_isolation on dms.webhook_delivery
    using (tenant_id = dms.current_tenant())
    with check (tenant_id = dms.current_tenant());

grant select, insert on dms.webhook_subscription to dms_app;
grant update (url, secret, event_types, document_classes, description, active, updated_at, deleted_at)
    on dms.webhook_subscription to dms_app;
grant select, insert on dms.webhook_delivery to dms_app;
grant update (status, attempts, last_status_code, last_error, last_attempt_at, delivered_at)
    on dms.webhook_delivery to dms_app;
