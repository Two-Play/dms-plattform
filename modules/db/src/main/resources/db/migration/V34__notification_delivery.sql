-- Delivery of notifications (DMS-130, ADR 0052).
--
-- 1. dms.notification_delivery: one row per outbox event, notification kind, recipient and channel (in-app,
--    email). The unique key makes the outbox subscriber idempotent: a retried event adds nothing. A job per row
--    delivers it; status, attempts and the last error make failed deliveries visible (dead letter).
-- 2. dms.notification: the in-app notifications of a user (the bell), rendered at delivery time in the user's
--    language after the read permission was checked again. Only read_at may change.
-- 3. dms.notification_settings: per user the language of notifications and which categories are also sent by
--    email (in-app is always on).
-- 4. dms.tenant.language: the tenant's default language for users without a preference.
-- 5. dms.acknowledgement_request: when a recipient was first asked to acknowledge a version (DMS-100 follow-up).
--    Automatic reminders count from then, so people who become recipients later are asked first and reminded
--    only after a whole interval. dms.acknowledgement_request_baseline marks the versions valid before this
--    migration: their current recipients count as asked at publication and get no new request notice.

alter table dms.tenant
    add column language text not null default 'de' check (language in ('de', 'en'));

create table dms.notification_delivery (
    id          uuid primary key,
    tenant_id   uuid        not null references dms.tenant (id),
    event_id    bigint      not null,
    kind        text        not null check (length(trim(kind)) > 0),
    category    text        not null check (length(trim(category)) > 0),
    recipient   text        not null check (length(trim(recipient)) > 0),
    channel     text        not null check (channel in ('in-app', 'email')),
    document_id uuid,
    version_id  uuid,
    params      jsonb       not null default '{}',
    status      text        not null default 'pending'
        check (status in ('pending', 'retrying', 'delivered', 'skipped', 'dead')),
    attempts    integer     not null default 0,
    last_error  text,
    created_at  timestamptz not null default now(),
    finished_at timestamptz,
    unique (event_id, kind, recipient, channel)
);

create index notification_delivery_unfinished
    on dms.notification_delivery (tenant_id, status, created_at) where status in ('pending', 'retrying', 'dead');

alter table dms.notification_delivery enable row level security;
create policy tenant_isolation on dms.notification_delivery
    using (tenant_id = dms.current_tenant());

grant select, insert, update, delete on dms.notification_delivery to dms_app;

create table dms.notification (
    id          uuid primary key,
    tenant_id   uuid        not null references dms.tenant (id),
    user_id     text        not null check (length(trim(user_id)) > 0),
    kind        text        not null,
    category    text        not null,
    subject     text        not null,
    body        text        not null,
    -- Path inside the web application, e.g. /documents/<id>.
    link        text,
    document_id uuid,
    created_at  timestamptz not null default now(),
    read_at     timestamptz
);

create index notification_inbox on dms.notification (tenant_id, user_id, created_at desc, id);
create index notification_unread on dms.notification (tenant_id, user_id) where read_at is null;

alter table dms.notification enable row level security;
create policy tenant_isolation on dms.notification
    using (tenant_id = dms.current_tenant());

grant select, insert, delete on dms.notification to dms_app;
grant update (read_at) on dms.notification to dms_app;

create table dms.notification_settings (
    tenant_id  uuid        not null references dms.tenant (id),
    user_id    text        not null check (length(trim(user_id)) > 0),
    -- null: the tenant's language.
    language   text check (language in ('de', 'en')),
    -- Category -> whether it is also sent by email; missing categories use the built-in default.
    email      jsonb       not null default '{}' check (jsonb_typeof(email) = 'object'),
    updated_at timestamptz not null default now(),
    primary key (tenant_id, user_id)
);

alter table dms.notification_settings enable row level security;
create policy tenant_isolation on dms.notification_settings
    using (tenant_id = dms.current_tenant());

grant select, insert, update on dms.notification_settings to dms_app;

create table dms.acknowledgement_request (
    tenant_id    uuid        not null,
    version_id   uuid        not null,
    user_id      text        not null check (length(trim(user_id)) > 0),
    requested_at timestamptz not null default now(),
    -- false: recorded without a notice (recipients of versions valid before this migration).
    notified     boolean     not null,
    primary key (version_id, user_id),
    foreign key (tenant_id, version_id) references dms.document_version (tenant_id, id)
);

create trigger acknowledgement_request_immutable
    before update or delete on dms.acknowledgement_request
    for each row execute function dms.reject_modification();

alter table dms.acknowledgement_request enable row level security;
create policy tenant_isolation on dms.acknowledgement_request
    using (tenant_id = dms.current_tenant());

grant select, insert on dms.acknowledgement_request to dms_app;

create table dms.acknowledgement_request_baseline (
    version_id uuid primary key,
    tenant_id  uuid not null,
    foreign key (tenant_id, version_id) references dms.document_version (tenant_id, id)
);

alter table dms.acknowledgement_request_baseline enable row level security;
create policy tenant_isolation on dms.acknowledgement_request_baseline
    using (tenant_id = dms.current_tenant());

grant select, delete on dms.acknowledgement_request_baseline to dms_app;

insert into dms.acknowledgement_request_baseline (version_id, tenant_id)
select version_id, tenant_id from dms.version_acknowledgement_audience;

-- Tenant administrators set the default language of notifications.
grant update (language) on dms.tenant to dms_app;
